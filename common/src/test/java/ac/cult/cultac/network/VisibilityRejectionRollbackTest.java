package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.visibility.BreakVisibility;
import ac.cult.cultac.checks.impl.visibility.PlaceVisibility;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.worldreader.BasePacketWorldReader;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.packet.WorldPackets.BlockUpdate;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.*;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisibilityRejectionRollbackTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);

    @Test
    void rejectedPlaceSendsBlockInventoryAndAckCorrectionsWithoutApplyingOrTrackingIt() throws Exception {
        try (var setup = new Setup()) {
            setup.held("stone", 3);
            setup.reject(place(TARGET, Direction.NORTH, 1));
            var player = setup.player;
            assertEquals(state("air"), player.compensatedWorld.getBlockStateIdAt(TARGET.north()));
            assertEquals(3, player.getInventory().getHeldItem().count());
            assertTrue(player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
            assertTrue(player.getGhostBlockMitigator().unknownStuff.isEmpty());
            var packets = setup.output();
            assertEquals(1, ack(packets));
            assertEquals(state("air"), update(packets, TARGET.north()).state());
            var slot = packets.stream()
                    .filter(InventoryPackets.Slot.class::isInstance)
                    .map(InventoryPackets.Slot.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals(36, slot.slot());
            assertEquals(3, slot.item().count());
            assertTrue(packets.indexOf(slot) < packets.indexOf(new ClientboundBlockChangedAck(1)));
            setup.confirm(packets);
            assertEquals(3, player.getInventory().getHeldItem().count());
        }
    }

    @Test
    void rejectedInstantStartAndCompletedDigKeepTheBlockAndSendAnAck() throws Exception {
        for (PlayerAction action : List.of(PlayerAction.START_DESTROY_BLOCK, PlayerAction.STOP_DESTROY_BLOCK)) {
            try (var setup = new Setup()) {
                setup.player.gamemode = GameMode.CREATIVE;
                setup.player.canInstabuild = true;
                setup.reject(new ServerboundPlayerAction(action, TARGET, Direction.NORTH, 1));
                assertEquals(state("stone"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET));
                assertTrue(setup.player
                        .compensatedWorld
                        .pendingPredictionSnapshot()
                        .isEmpty());
                assertTrue(setup.player.getGhostBlockMitigator().unknownStuff.isEmpty());
                var packets = setup.output();
                assertEquals(1, ack(packets));
                assertEquals(state("stone"), update(packets, TARGET).state());
                setup.confirm(packets);
                assertEquals(state("stone"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET));
            }
        }
    }

    @Test
    void rejectedDoorPlacementCorrectsBothHalvesWithoutInstallingEither() throws Exception {
        try (var setup = new Setup()) {
            setup.held("oak_door", 3);
            BlockPos support = TARGET.below();
            setup.block(support, "stone");
            setup.block(TARGET, "air");
            setup.reject(place(support, Direction.UP, 1));
            var packets = setup.output();
            assertEquals(state("air"), update(packets, TARGET).state());
            assertEquals(state("air"), update(packets, TARGET.above()).state());
            assertEquals(state("air"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET.above()));
            assertEquals(3, setup.player.getInventory().getHeldItem().count());
            assertTrue(setup.player.getGhostBlockMitigator().unknownStuff.isEmpty());
        }
    }

    @Test
    void rejectedActionWaitsForEarlierBackendAckAndUsesItsInventoryCorrection() throws Exception {
        try (var setup = new Setup()) {
            setup.held("stone", 3);
            BlockPos support = new BlockPos(2, 63, 0);
            setup.block(support, "stone");
            setup.fixture.forward(setup.encode(place(support, Direction.UP, 1)));
            assertEquals(state("stone"), setup.player.compensatedWorld.getBlockStateIdAt(support.above()));
            assertEquals(2, setup.player.getInventory().getHeldItem().count());
            setup.output();
            setup.reject(place(TARGET, Direction.NORTH, 2));
            assertTrue(setup.output().isEmpty(), "Rejected sequence 2 cannot acknowledge pending sequence 1");

            setup.fixture.user.write(new BlockUpdate(support.above(), state("stone")));
            setup.fixture.user.write(
                    new InventoryPackets.Slot(0, 9, 36, ItemUtil.modelItems().stack("minecraft:stone", 2)));
            setup.fixture.user.write(new ClientboundBlockChangedAck(1));
            var backend = setup.output();
            assertEquals(1, ack(backend));
            setup.confirm(backend);
            var correction = setup.output();
            assertEquals(2, ack(correction));
            var slot = correction.stream()
                    .filter(InventoryPackets.Slot.class::isInstance)
                    .map(InventoryPackets.Slot.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals(2, slot.item().count());
            assertEquals(9, slot.stateId());
            setup.confirm(correction);
            assertEquals(state("stone"), setup.player.compensatedWorld.getBlockStateIdAt(support.above()));
            assertEquals(state("air"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET.north()));
            assertTrue(setup.player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void cancelledActionsStillAdvanceTheLocalPredictionCounter() throws Exception {
        try (var setup = new Setup()) {
            setup.held("stone", 3);
            setup.reject(place(TARGET, Direction.NORTH, 1));
            setup.confirm(setup.output());
            setup.block(new BlockPos(2, 63, 0), "stone");
            setup.fixture.forward(setup.encode(place(new BlockPos(2, 63, 0), Direction.UP, 2)));
            assertEquals(
                    2,
                    setup.player
                            .compensatedWorld
                            .pendingPredictionSnapshot()
                            .getFirst()
                            .sequence());
        }
    }

    @Test
    void rejectedAckDoesNotEndALaterLegalPrediction() throws Exception {
        try (var setup = new Setup()) {
            setup.held("stone", 3);
            BlockPos first = new BlockPos(2, 63, 0);
            BlockPos later = new BlockPos(3, 63, -1);
            setup.block(first, "stone");
            setup.block(later, "stone");
            setup.fixture.forward(setup.encode(place(first, Direction.UP, 1)));
            setup.reject(place(TARGET, Direction.NORTH, 2));
            // A fresh client tick picks the later support; earlier acknowledgements can still be pending.
            setup.fixture.forward(setup.fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
            setup.fixture.forward(setup.encode(place(later, Direction.UP, 3)));
            assertEquals(
                    state("stone"),
                    setup.player.compensatedWorld.getBlockStateIdAt(later.above()),
                    "The later native placement must actually have predicted a block");
            setup.output();
            setup.fixture.user.write(new BlockUpdate(first.above(), state("stone")));
            setup.fixture.user.write(new ClientboundBlockChangedAck(1));
            setup.confirm(setup.output());
            var correction = setup.output();
            assertEquals(2, ack(correction));
            setup.confirm(correction);
            var pending = setup.player.compensatedWorld.pendingPredictionSnapshot();
            assertEquals(1, pending.size());
            assertEquals(3, pending.getFirst().sequence());
            assertEquals(later.above(), pending.getFirst().position());
            assertEquals(state("stone"), setup.player.compensatedWorld.getBlockStateIdAt(later.above()));
        }
    }

    @Test
    void rejectedOffhandPlacementRestoresTheOffhandSlot() throws Exception {
        try (var setup = new Setup()) {
            setup.fixture.user.write(
                    new InventoryPackets.Slot(0, 7, 45, ItemUtil.modelItems().stack("minecraft:stone", 3)));
            setup.confirm(setup.output());
            var base = place(TARGET, Direction.NORTH, 1);
            setup.reject(
                    new ServerboundUseItemOn(Hand.OFF_HAND, TARGET, base.blockFace(), base.cursor(), false, false, 1));
            var packets = setup.output();
            var slots = packets.stream()
                    .filter(InventoryPackets.Slot.class::isInstance)
                    .map(InventoryPackets.Slot.class::cast)
                    .toList();
            assertEquals(1, slots.size());
            assertEquals(45, slots.getFirst().slot());
            assertEquals(3, slots.getFirst().item().count());
            assertEquals(7, slots.getFirst().stateId());
            assertEquals(3, setup.player.getInventory().getOffHand().count());
        }
    }

    private static ServerboundUseItemOn place(BlockPos pos, Direction face, int sequence) {
        return new ServerboundUseItemOn(
                Hand.MAIN_HAND,
                pos,
                face,
                new Vec3d(.5, face == Direction.UP ? 1 : .5, face == Direction.NORTH ? 0 : .5),
                false,
                false,
                sequence);
    }

    private static int ack(List<Object> packets) {
        return packets.stream()
                .filter(ClientboundBlockChangedAck.class::isInstance)
                .map(ClientboundBlockChangedAck.class::cast)
                .mapToInt(ClientboundBlockChangedAck::sequence)
                .max()
                .orElseThrow();
    }

    private static BlockUpdate update(List<Object> packets, BlockPos pos) {
        return packets.stream()
                .filter(BlockUpdate.class::isInstance)
                .map(BlockUpdate.class::cast)
                .filter(update -> update.position().equals(pos))
                .findFirst()
                .orElseThrow();
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, false);
        final ac.cult.cultac.player.CultPlayer player;

        Setup() throws Exception {
            services.useNetwork(fixture.manager);
            ac.cult.cultac.CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            assertNotNull(player);
            player.platformPlayer = mock(PlatformPlayer.class);
            var platformWorld = mock(ac.cult.cultac.platform.api.world.PlatformWorld.class);
            when(player.platformPlayer.getWorld()).thenReturn(platformWorld);
            when(platformWorld.getName()).thenReturn("world");
            fixture.user.bind(player.platformPlayer);
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            player.checkManager.getCheck(PlaceVisibility.class).setEnabled(true);
            player.checkManager.getCheck(BreakVisibility.class).setEnabled(true);
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
            block(TARGET, "stone");
            block(new BlockPos(0, 64, 0), "stone");
            block(new BlockPos(0, 65, 0), "stone");
            fixture.registrar.register(routes -> {
                var checks = new CheckManagerListener();
                checks.registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW);
                routes.registerReceiveListener(PacketListenerPriority.LOW, checks);
                checks.registerForwardingSendPackets(routes, PacketListenerPriority.LOW);
                routes.registerListener(PacketListenerPriority.LOWEST, new PacketPingListener());
                routes.registerSendListener(PacketListenerPriority.HIGH, new BasePacketWorldReader());
            });
        }

        void held(String item, int count) {
            player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:" + item, count));
        }

        void block(BlockPos pos, String name) {
            player.compensatedWorld.applyBlockChangeRawDANGER(pos.x(), pos.y(), pos.z(), state(name));
        }

        void reject(ServerboundUseItemOn packet) {
            assertFalse(fixture.channel.writeInbound(encode(packet)));
            assertNull(fixture.channel.readInbound());
        }

        void reject(ServerboundPlayerAction packet) {
            var frame = fixture.id(ServerboundPackets.PLAYER_ACTION, "player_action");
            Wire.writeVarInt(frame, packet.action().ordinal());
            Wire.writeBlockPos(frame, packet.position());
            frame.writeByte(packet.direction().ordinal());
            Wire.writeVarInt(frame, packet.sequence());
            assertFalse(fixture.channel.writeInbound(frame));
            assertNull(fixture.channel.readInbound());
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
