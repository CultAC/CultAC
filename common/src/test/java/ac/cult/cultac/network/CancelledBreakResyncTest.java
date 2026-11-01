package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.impl.breaking.FastBreak;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
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
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MultiPlayerGameMode#continueDestroyBlock destroys the block inside BlockStatePredictionHandler#startPredicting
 * before it sends STOP_DESTROY_BLOCK with that prediction's sequence. ClientLevel#setServerVerifiedBlockState keeps a
 * block update for a predicted position as its known server state, and ClientLevel#handleBlockChangedAck restores
 * that state only once an acknowledgement reaches the prediction's sequence. ServerPlayerGameMode#tick sends level
 * events 2019 and 2020 each tick of a destroy session, which only a STOP at its position or an ABORT ends, and
 * ServerGamePacketListenerImpl#handlePlayerAction acknowledges either. A break the server never receives is
 * acknowledged by no one else, so the correction must carry the server's block and an acknowledgement of the break.
 */
class CancelledBreakResyncTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);
    private static final BlockPos OTHER = new BlockPos(1, 64, 2);

    @BeforeAll
    static void registerVerboseTags() {
        VerboseCodecs.ensureRegistered();
    }

    @Test
    void aCancelledFinishedBreakRestoresTheServersBlockOnTheClient() throws Exception {
        try (var setup = new Setup()) {
            var client = new ClientPredictions();
            // Survival bare hands on stone: START predicts no block change, the sequence still advances.
            client.startPredicting();
            setup.forward(new ServerboundPlayerAction(PlayerAction.START_DESTROY_BLOCK, TARGET, Direction.NORTH, 1));
            setup.fixture.user.write(new ClientboundBlockChangedAck(1));
            var backend = setup.output();
            client.receive(backend);
            setup.confirm(backend);

            client.startPredicting();
            client.destroy(TARGET, state("stone"));
            var received = setup.replaced(
                    new ServerboundPlayerAction(PlayerAction.STOP_DESTROY_BLOCK, TARGET, Direction.NORTH, 2));
            assertTrue(setup.check.getViolations() > 0, "FastBreak let the break through");
            assertEquals(
                    new ServerboundPlayerAction(PlayerAction.ABORT_DESTROY_BLOCK, TARGET, Direction.NORTH, 2),
                    received);
            assertEquals(List.of(), setup.output());

            setup.fixture.user.write(new ClientboundBlockChangedAck(2));
            var acknowledgement = setup.output();
            client.receive(acknowledgement);
            assertEquals(state("stone"), client.blockAt(TARGET));
            assertTrue(client.pending.isEmpty(), "the break is still predicted");

            setup.confirm(acknowledgement);
            assertEquals(state("stone"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET));
            assertTrue(setup.player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void aStopAtAnotherPositionLeavesTheServersDestroySession() throws Exception {
        try (var setup = new Setup()) {
            setup.forward(new ServerboundPlayerAction(PlayerAction.START_DESTROY_BLOCK, TARGET, Direction.NORTH, 1));
            setup.player.blockActionReconciler.digReachedServer(
                    new ServerboundPlayerAction(PlayerAction.STOP_DESTROY_BLOCK, OTHER, Direction.NORTH, 2));
            setup.fixture.user.write(new ClientboundBlockChangedAck(2));
            setup.confirm(setup.output());

            var received = setup.replaced(
                    new ServerboundPlayerAction(PlayerAction.STOP_DESTROY_BLOCK, TARGET, Direction.NORTH, 3));
            assertEquals(
                    new ServerboundPlayerAction(PlayerAction.ABORT_DESTROY_BLOCK, TARGET, Direction.NORTH, 3),
                    received);
        }
    }

    @Test
    void theCorrectionIsHeldUntilTheServerAcknowledgesTheStart() throws Exception {
        try (var setup = new Setup()) {
            setup.player.compensatedWorld.applyBlockChangeRawDANGER(OTHER.x(), OTHER.y(), OTHER.z(), state("stone"));
            setup.forward(new ServerboundPlayerAction(PlayerAction.START_DESTROY_BLOCK, OTHER, Direction.NORTH, 1));
            setup.output();
            setup.reject(new ServerboundPlayerAction(PlayerAction.STOP_DESTROY_BLOCK, TARGET, Direction.NORTH, 2));
            assertTrue(setup.output().isEmpty(), "an acknowledgement of 2 would end the server's pending 1");

            setup.fixture.user.write(new ClientboundBlockChangedAck(1));
            setup.confirm(setup.output());
            var correction = setup.output();
            int update = indexOf(correction, BlockUpdate.class);
            int ack = indexOf(correction, ClientboundBlockChangedAck.class);
            assertEquals(new BlockUpdate(TARGET, state("stone")), correction.get(update));
            assertEquals(new ClientboundBlockChangedAck(2), correction.get(ack));
            assertTrue(update < ack, "the update must arrive while the break is still predicted");
        }
    }

    @Test
    void aRejectedBedrockDigSendsNoJavaCorrection() throws Exception {
        try (var setup = new Setup()) {
            setup.player.movementPlatform = MovementPlatform.BEDROCK;
            setup.player.blockActionReconciler.rejectDig(
                    new ServerboundPlayerAction(PlayerAction.STOP_DESTROY_BLOCK, TARGET, Direction.NORTH, 2));

            assertEquals(List.of(), setup.output());
        }
    }

    private static int indexOf(List<Object> packets, Class<?> type) {
        for (int i = 0; i < packets.size(); i++) {
            if (type.isInstance(packets.get(i))) return i;
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + packets);
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }

    /** ClientLevel's view of the blocks under BlockStatePredictionHandler. */
    private static final class ClientPredictions {
        final Map<BlockPos, Integer> world = new HashMap<>();
        final Map<BlockPos, int[]> pending = new HashMap<>();
        int sequence;

        void startPredicting() {
            sequence++;
        }

        // retainKnownServerState, then the predicted break to air.
        void destroy(BlockPos pos, int serverState) {
            pending.put(pos, new int[] {sequence, serverState});
            world.put(pos, state("air"));
        }

        void receive(List<Object> packets) {
            for (Object packet : packets) {
                if (packet instanceof BlockUpdate update) {
                    // ClientLevel#setServerVerifiedBlockState
                    int[] known = pending.get(update.position());
                    if (known != null) known[1] = update.state();
                    else world.put(update.position(), update.state());
                } else if (packet instanceof ClientboundBlockChangedAck ack) {
                    // BlockStatePredictionHandler#endPredictionsUpTo
                    pending.entrySet().removeIf(entry -> {
                        if (entry.getValue()[0] > ack.sequence()) return false;
                        world.put(entry.getKey(), entry.getValue()[1]);
                        return true;
                    });
                }
            }
        }

        int blockAt(BlockPos pos) {
            return world.getOrDefault(pos, state("stone"));
        }
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, false);
        final CultPlayer player;
        final FastBreak check;

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
            check = player.checkManager.getCheck(FastBreak.class);
            check.setEnabled(true);
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
            world.applyBlockChangeRawDANGER(TARGET.x(), TARGET.y(), TARGET.z(), state("stone"));
            // PacketManager's order: the digging listener runs before the check manager's.
            fixture.registrar.register(routes -> {
                var checks = new CheckManagerListener();
                checks.registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW);
                routes.registerReceiveListener(PacketListenerPriority.LOW, new PacketPlayerDigging());
                routes.registerReceiveListener(PacketListenerPriority.LOW, checks);
                checks.registerForwardingSendPackets(routes, PacketListenerPriority.LOW);
                routes.registerListener(PacketListenerPriority.LOWEST, new PacketPingListener());
                routes.registerSendListener(PacketListenerPriority.HIGH, new BasePacketWorldReader());
            });
        }

        void forward(ServerboundPlayerAction packet) {
            fixture.forward(encode(packet));
        }

        void reject(ServerboundPlayerAction packet) {
            assertFalse(fixture.channel.writeInbound(encode(packet)));
            assertNull(fixture.channel.readInbound());
        }

        ServerboundPlayerAction replaced(ServerboundPlayerAction packet) {
            assertTrue(fixture.channel.writeInbound(encode(packet)));
            ByteBuf frame = fixture.channel.readInbound();
            try {
                int id = Wire.readVarInt(frame);
                return (ServerboundPlayerAction) fixture.codec.read(PacketDirection.SERVERBOUND, id, frame);
            } finally {
                frame.release();
            }
        }

        ByteBuf encode(ServerboundPlayerAction packet) {
            var frame = fixture.id(ServerboundPackets.PLAYER_ACTION, "player_action");
            Wire.writeVarInt(frame, packet.action().ordinal());
            Wire.writeBlockPos(frame, packet.position());
            frame.writeByte(packet.direction().ordinal());
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
