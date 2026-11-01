package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.worldreader.BasePacketWorldReader;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.packet.WorldPackets.BlockUpdate;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.blockplace.GhostBlock;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ClientLevel#handleBlockChangedAck ends every block prediction up to the acknowledged sequence. */
class GhostBlockAckTest {
    private static final BlockPos FIRST_SUPPORT = new BlockPos(2, 63, 0);
    private static final BlockPos SECOND_SUPPORT = new BlockPos(3, 63, -1);

    @Test
    @DisplayName("A placement the server acknowledges without a block update reverts and leaves no ghost position")
    void rejectedPlacementAckedWithoutBlockUpdateClearsItsGhostPositions() throws Exception {
        try (var setup = new Setup()) {
            BlockPos placed = FIRST_SUPPORT.above();
            setup.place(FIRST_SUPPORT, 1);
            assertEquals(state("stone"), setup.world(placed), "The client predicts the placed block");
            assertTrue(setup.mitigator().unknownStuff.containsKey(placed));
            assertTrue(pseudoPlaces(setup.mitigator()).containsKey(FIRST_SUPPORT));

            setup.fixture.user.write(new ClientboundBlockChangedAck(1));
            setup.confirm(setup.output());

            assertEquals(state("air"), setup.world(placed), "BlockStatePredictionHandler#endPredictionsUpTo");
            setup.assertMovementIntoIsJudgedNormally(placed);
            setup.assertNoGhostPositions();
        }
    }

    @Test
    @DisplayName("An accepted placement keeps the block and leaves no ghost position after its acknowledgement")
    void acceptedPlacementKeepsTheBlockAndClearsItsGhostPositions() throws Exception {
        try (var setup = new Setup()) {
            BlockPos placed = FIRST_SUPPORT.above();
            setup.place(FIRST_SUPPORT, 1);

            setup.fixture.user.write(new BlockUpdate(FIRST_SUPPORT, state("stone")));
            setup.fixture.user.write(new BlockUpdate(placed, state("stone")));
            setup.fixture.user.write(new ClientboundBlockChangedAck(1));
            setup.confirm(setup.output());

            assertEquals(state("stone"), setup.world(placed));
            setup.assertNoGhostPositions();
            setup.assertMovementIntoIsJudgedNormally(placed);
        }
    }

    @Test
    @DisplayName("One acknowledgement ends every placement up to its sequence")
    void oneAckEndsEveryPlacementUpToItsSequence() throws Exception {
        try (var setup = new Setup()) {
            BlockPos first = FIRST_SUPPORT.above();
            BlockPos second = SECOND_SUPPORT.above();
            setup.place(FIRST_SUPPORT, 1);
            setup.fixture.forward(setup.fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
            setup.place(SECOND_SUPPORT, 2);
            assertEquals(state("stone"), setup.world(first));
            assertEquals(state("stone"), setup.world(second));

            setup.fixture.user.write(new ClientboundBlockChangedAck(2));
            setup.confirm(setup.output());

            assertEquals(state("air"), setup.world(first));
            assertEquals(state("air"), setup.world(second));
            setup.assertNoGhostPositions();
            setup.assertMovementIntoIsJudgedNormally(first);
            setup.assertMovementIntoIsJudgedNormally(second);
        }
    }

    @Test
    @DisplayName("An acknowledgement keeps the ghost positions of later placements")
    void ackKeepsGhostPositionsOfLaterPlacements() throws Exception {
        try (var setup = new Setup()) {
            BlockPos first = FIRST_SUPPORT.above();
            BlockPos second = SECOND_SUPPORT.above();
            setup.place(FIRST_SUPPORT, 1);
            setup.fixture.forward(setup.fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
            setup.place(SECOND_SUPPORT, 2);

            setup.fixture.user.write(new ClientboundBlockChangedAck(1));
            setup.confirm(setup.output());

            assertEquals(state("air"), setup.world(first));
            assertEquals(state("stone"), setup.world(second));
            var mitigator = setup.mitigator();
            assertFalse(mitigator.unknownStuff.containsKey(first));
            assertFalse(pseudoPlaces(mitigator).containsKey(FIRST_SUPPORT));
            assertTrue(mitigator.unknownStuff.containsKey(second));
            assertTrue(pseudoPlaces(mitigator).containsKey(SECOND_SUPPORT));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, GhostBlock> pseudoPlaces(GhostBlockMitigator mitigator) throws Exception {
        Field field = GhostBlockMitigator.class.getDeclaredField("pseudoPlaces");
        field.setAccessible(true);
        return (Map<BlockPos, GhostBlock>) field.get(mitigator);
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, false);
        final CultPlayer player;

        Setup() throws Exception {
            services.useNetwork(fixture.manager);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            assertNotNull(player);
            player.platformPlayer = mock(PlatformPlayer.class);
            var platformWorld = mock(PlatformWorld.class);
            when(player.platformPlayer.getWorld()).thenReturn(platformWorld);
            when(platformWorld.getName()).thenReturn("world");
            fixture.user.bind(player.platformPlayer);
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -1.5;
            player.packetStateData.clientSidePosition = new Vec3(.5, 64, -1.5);
            player.boundingBox = new SimpleCollisionBox(.2, 64, -1.8, .8, 65.8, -1.2);
            player.registryState = new ClientComponentRegistries();
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            block(FIRST_SUPPORT, "stone");
            block(SECOND_SUPPORT, "stone");
            player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:stone", 8));
            fixture.registrar.register(routes -> {
                var checks = new CheckManagerListener();
                checks.registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW);
                routes.registerReceiveListener(PacketListenerPriority.LOW, checks);
                checks.registerForwardingSendPackets(routes, PacketListenerPriority.LOW);
                routes.registerListener(PacketListenerPriority.LOWEST, new PacketPingListener());
                routes.registerSendListener(PacketListenerPriority.HIGH, new BasePacketWorldReader());
            });
        }

        GhostBlockMitigator mitigator() {
            return player.getGhostBlockMitigator();
        }

        int world(BlockPos pos) {
            return player.compensatedWorld.getBlockStateIdAt(pos);
        }

        void block(BlockPos pos, String name) {
            player.compensatedWorld.applyBlockChangeRawDANGER(pos.x(), pos.y(), pos.z(), state(name));
        }

        void place(BlockPos support, int sequence) {
            fixture.forward(encode(new ServerboundUseItemOn(
                    Hand.MAIN_HAND, support, Direction.UP, new Vec3d(.5, 1, .5), false, false, sequence)));
            output();
        }

        void assertNoGhostPositions() throws Exception {
            assertTrue(
                    mitigator().unknownStuff.isEmpty(),
                    () -> "unknown " + mitigator().unknownStuff.keySet());
            assertTrue(pseudoPlaces(mitigator()).isEmpty(), () -> {
                try {
                    return "pseudo " + pseudoPlaces(mitigator()).keySet();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }

        void assertMovementIntoIsJudgedNormally(BlockPos pos) {
            var context = mock(SimulationContext.class);
            when(context.getStart()).thenReturn(new Vec3(pos.x() + .5, pos.y(), pos.z() + .5));
            when(context.getEnd()).thenReturn(new Vec3(pos.x() + .5, pos.y() + .1, pos.z() + .5));
            var result = mock(PredictionResult.class);
            when(result.getFlagSeverity()).thenReturn(1.0);
            when(result.getSimulationContext()).thenReturn(context);
            mitigator().onPredictionComplete(new PredictionComplete(result));
            verify(result, never()).exempt();
        }

        ByteBuf encode(ServerboundUseItemOn packet) {
            var frame = fixture.id(ServerboundPackets.USE_ITEM_ON, "use_item_on");
            Wire.writeVarInt(frame, packet.hand().ordinal());
            Wire.writeBlockPos(frame, packet.blockPosition());
            Wire.writeVarInt(frame, packet.blockFace().ordinal());
            frame.writeFloat((float) packet.cursor().x());
            frame.writeFloat((float) packet.cursor().y());
            frame.writeFloat((float) packet.cursor().z());
            frame.writeBoolean(packet.insideBlock()).writeBoolean(false);
            Wire.writeVarInt(frame, packet.sequence());
            return frame;
        }

        List<Object> output() {
            List<Object> packets = new ArrayList<>();
            ByteBuf frame;
            while ((frame = fixture.channel.readOutbound()) != null) {
                try {
                    int id = Wire.readVarInt(frame);
                    Object packet = fixture.codec.read(PacketDirection.CLIENTBOUND, id, frame);
                    if (packet != null) packets.add(packet);
                } finally {
                    frame.release();
                }
            }
            return packets;
        }

        void confirm(List<Object> packets) {
            packets.stream()
                    .filter(ClientboundPing.class::isInstance)
                    .map(ClientboundPing.class::cast)
                    .forEach(ping -> fixture.channel.writeInbound(
                            fixture.encode(ServerboundPackets.PONG, new ServerboundPong(ping.id()))));
        }

        @Override
        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                services.close();
            }
        }
    }
}
