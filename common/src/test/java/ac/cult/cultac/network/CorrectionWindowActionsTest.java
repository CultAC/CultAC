package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.checks.impl.visibility.BreakVisibility;
import ac.cult.cultac.checks.impl.visibility.InteractEntityVisibility;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ServerPlayerGameMode#handleBlockBreakAction and ServerGamePacketListenerImpl#handleInteract judge digs and entity
 * interactions from the position the server holds for the player. A movement withheld from the server leaves it at
 * the last forwarded one until the client accepts the correction.
 */
class CorrectionWindowActionsTest {
    private static final BlockPos BEHIND_WALL = new BlockPos(0, 64, 4);
    private static final BlockPos IN_FRONT = new BlockPos(0, 64, 1);
    private static final int COW = 42;

    @BeforeAll
    static void registerVerboseTags() {
        VerboseCodecs.ensureRegistered();
    }

    @Test
    void digsAndEntityInteractionsWaitForTheAcceptedCorrection() throws Exception {
        try (var s = new Setup()) {
            var visibility = s.player.checkManager.getCheck(BreakVisibility.class);
            visibility.setEnabled(true);
            s.player.checkManager.getCheck(InteractEntityVisibility.class).setEnabled(true);
            s.player.compensatedEntities.addEntity(COW, EntityTypeIds.COW, new Vec3(.5, 64, 4.5), 0, 0, 0);
            assertTrue(s.dig(BEHIND_WALL, Direction.NORTH).isCancelled());
            assertTrue(s.interact().isCancelled());
            assertFalse(s.dig(IN_FRONT, Direction.NORTH).isCancelled());

            assertTrue(s.move(.5, 64, 3.5).isCancelled());
            assertTrue(s.player.getSetbackTeleportUtil().isPendingSetback());
            double violations = visibility.violations;
            assertTrue(s.dig(BEHIND_WALL, Direction.NORTH).isCancelled());
            assertTrue(s.interact().isCancelled());
            s.endTick();
            assertTrue(s.move(.5, 64, 3.6).isCancelled());
            assertTrue(s.dig(BEHIND_WALL, Direction.NORTH).isCancelled());
            assertTrue(s.interact().isCancelled());
            assertEquals(violations, visibility.violations);

            s.acceptCorrection();
            assertFalse(s.player.getSetbackTeleportUtil().isPendingSetback());
            assertFalse(s.dig(IN_FRONT, Direction.NORTH).isCancelled());
            assertTrue(s.dig(BEHIND_WALL, Direction.NORTH).isCancelled());
        }
    }

    @Test
    void aWithheldEntityInteractionGivesBackTheSlotsTheClientCanHaveChanged() throws Exception {
        try (var s = new Setup()) {
            s.holdBuckets(2);
            s.serverSlot(Inventory.HOTBAR_OFFSET + 1, ItemUtil.modelItems().stack("minecraft:dirt", 5));
            s.serverSlot(Inventory.HOTBAR_OFFSET + 2, SimItemStack.EMPTY);
            s.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET + 1, ItemUtil.modelItems().stack("minecraft:dirt", 5));

            assertTrue(s.move(.5, 64, 3.5).isCancelled());
            s.output();
            assertTrue(s.interact(InteractAction.INTERACT).isCancelled());
            List<InventoryPackets.Slot> slots = slots(s.output());
            assertEquals(List.of(s.menuSlot(Inventory.HOTBAR_OFFSET), s.menuSlot(Inventory.HOTBAR_OFFSET + 2)),
                    slots.stream().map(InventoryPackets.Slot::slot).toList());
            assertEquals("minecraft:bucket", slots.get(0).item().itemKey());
            assertEquals(2, slots.get(0).item().getCount());
            assertTrue(slots.get(1).item().isEmpty());
        }
    }

    @Test
    void withheldEntityInteractionsAreCorrectedOnceATick() throws Exception {
        try (var s = new Setup()) {
            s.holdBuckets(1);
            assertTrue(s.move(.5, 64, 3.5).isCancelled());
            s.output();
            for (int i = 0; i < 4; i++) assertTrue(s.interact(InteractAction.INTERACT).isCancelled());
            assertEquals(1, slots(s.output()).size());
            s.player.onEndOfTickEvent();
            assertEquals(1, slots(s.output()).size());
            s.player.onEndOfTickEvent();
            assertTrue(slots(s.output()).isEmpty());
        }
    }

    @Test
    void theInteractionCorrectionFollowsTheServersHandlingOfEarlierActions() throws Exception {
        try (var s = new Setup()) {
            s.holdBuckets(1);
            assertFalse(s.dig(IN_FRONT, Direction.NORTH).isCancelled());
            assertTrue(s.move(.5, 64, 3.5).isCancelled());
            s.output();
            assertTrue(s.interact(InteractAction.INTERACT).isCancelled());
            assertTrue(slots(s.output()).isEmpty());
            s.player.blockActionReconciler.confirmServerAck(s.sequence);
            assertEquals(List.of(s.menuSlot(Inventory.HOTBAR_OFFSET)),
                    slots(s.output()).stream().map(InventoryPackets.Slot::slot).toList());
        }
    }

    @Test
    void aBlockUseWaitsWhileMovementIsWithheld() throws Exception {
        try (var s = new Setup()) {
            SetbackFixtures.setBlockOffsets(s.player.getSetbackTeleportUtil(), true);
            assertTrue(s.useItemOn(IN_FRONT).isCancelled());
        }
    }

    @Test
    void anAttackWaitsForTheCorrectionNextToAnUnloadedChunk() throws Exception {
        try (var s = new Setup()) {
            s.player.compensatedEntities.addEntity(COW, EntityTypeIds.COW, new Vec3(.5, 64, -18.5), 0, 0, 0);
            SetbackFixtures.requireCorrection(s.player.getSetbackTeleportUtil(), correction(new Vec3(.5, 64, -15)));
            s.player.z = s.player.lastZ = -17.5;
            assertTrue(s.player.getSetbackTeleportUtil().insideUnloadedChunk());
            assertTrue(s.interact(InteractAction.ATTACK).isCancelled());
        }
    }

    @Test
    void aDisabledPlayersDigWaitsForTheCorrectionItsMovementWaitsFor() throws Exception {
        try (var s = new Setup()) {
            setField(CultPlayer.class, s.player, "disabled", true);
            SetbackFixtures.requireCorrection(s.player.getSetbackTeleportUtil(), correction(new Vec3(.5, 64, .5)));
            assertTrue(s.dig(IN_FRONT, Direction.NORTH).isCancelled());
        }
    }

    private static SetBackData correction(Vec3 at) {
        return new SetBackData(new TeleportData(at, new RelativeFlag(0), 20, 1), 0, 0, Vec3.ZERO, false, false);
    }

    private static List<InventoryPackets.Slot> slots(List<Object> packets) {
        return packets.stream()
                .filter(InventoryPackets.Slot.class::isInstance)
                .map(InventoryPackets.Slot.class::cast)
                .toList();
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }

    private static void setField(Class<?> owner, Object value, String name, Object setting) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(value, setting);
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services;
        final RecordReceiveFixture fixture;
        final CultPlayer player;
        final CheckManagerListener listener = new CheckManagerListener();
        int sequence;

        Setup() throws Exception {
            services = new RecordConsumerServices(ProtocolVersion.V26_3);
            fixture = RecordReceiveFixture.olderHost(ProtocolVersion.V26_3);
            services.useNetwork(fixture.manager);
            player = fixture.player;
            setField(CultPlayer.class, player, "resolvedClientVersion", ClientVersion.V_26_3);
            player.gamemode = GameMode.SURVIVAL;
            player.registryState = new ClientComponentRegistries();
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            player.lastTransactionReceived.set(10);
            player.x = player.lastX = .5;
            player.y = player.lastY = 64;
            player.z = player.lastZ = .5;
            player.onGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, .5, 64, .5);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) world.ensureValidationChunkLoaded(x, z);
            }
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 6; z++) world.updateBlock(new BlockPos(x, 63, z), BlockIds.STONE.defaultState());
            }
            for (int x = -3; x <= 3; x++) {
                for (int y = 64; y <= 67; y++) world.applyBlockChangeRawDANGER(x, y, 2, state("stone"));
            }
            world.applyBlockChangeRawDANGER(BEHIND_WALL.x(), BEHIND_WALL.y(), BEHIND_WALL.z(), state("stone"));
            world.applyBlockChangeRawDANGER(IN_FRONT.x(), IN_FRONT.y(), IN_FRONT.z(), state("stone"));
            var teleports = player.getSetbackTeleportUtil();
            SetbackFixtures.setLoaded(teleports, true);
            SetbackFixtures.seedSafePosition(teleports, new SetbackPosWithVector(new Vec3(.5, 64, .5), Vec3.ZERO));
            player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, -.0784000015258789, 0));
            // These synchronous fixtures exercise packet handling, not wall-clock pacing.
            setField(
                    AbstractTimerCheck.class,
                    player.checkManager.getCheck(TimerCheck.class),
                    "timerBalanceRealTime",
                    System.nanoTime() - 10_000_000_000L);
            setField(
                    DumbTimer.class,
                    player.checkManager.getListener(DumbTimer.class),
                    "balance",
                    (double) System.nanoTime() - 10_000_000_000L);
            player.packetStateData.playerLoadedIntoLevel = true;
            player.packetStateData.localPlayerTickedSinceSpawn = true;
        }

        PacketReceiveEvent<ServerboundMovePlayer> move(double x, double y, double z) {
            var packet = new ServerboundMovePlayer(x, y, z, 0, 0, true, false, true, true);
            var event = route(ServerboundPackets.MOVE_PLAYER, packet);
            listener.onMovePlayer(event, player, packet);
            return event;
        }

        void endTick() {
            listener.processClientTickEndReceive(
                    new PacketReceiveEvent<>(
                            fixture.user,
                            ConnectionPhase.PLAY,
                            ServerboundPackets.CLIENT_TICK_END,
                            ServerboundPackets.CLIENT_TICK_END.opaqueValue()),
                    player);
        }

        // ClientPacketListener#handleMovePlayer answers with the position it took.
        void acceptCorrection() {
            player.lastTransactionReceived.set(player.lastTransactionSent.get());
            for (TeleportData teleport : List.copyOf(player.getSetbackTeleportUtil().pendingTeleports)) {
                Vec3 at = teleport.getLocation();
                var packet = new ServerboundAcceptTeleportation(
                        teleport.getTeleportId(),
                        new Vec3d(at.x, at.y, at.z),
                        teleport.getFinalYaw(),
                        teleport.getFinalPitch());
                var event = route(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
                new PacketServerTeleport().onAcceptTeleportation(event, player, packet);
                listener.onAcceptTeleportation(event, player, packet);
            }
        }

        PacketReceiveEvent<ServerboundPlayerAction> dig(BlockPos pos, Direction face) {
            var packet = new ServerboundPlayerAction(PlayerAction.START_DESTROY_BLOCK, pos, face, ++sequence);
            var event = route(ServerboundPackets.PLAYER_ACTION, packet);
            if (!event.isCancelled()) listener.onPlayerAction(event, player, packet);
            return event;
        }

        PacketReceiveEvent<ServerboundUseItemOn> useItemOn(BlockPos pos) {
            var packet = new ServerboundUseItemOn(
                    Hand.MAIN_HAND, pos, Direction.SOUTH, new Vec3d(.5, .5, 0), false, false, ++sequence);
            var event = route(ServerboundPackets.USE_ITEM_ON, packet);
            if (!event.isCancelled()) listener.onUseItemOn(event, player, packet);
            return event;
        }

        PacketReceiveEvent<ServerboundInteract> interact() {
            return interact(new ServerboundInteract(
                    COW, InteractAction.INTERACT_AT, Hand.MAIN_HAND, Optional.of(new Vec3d(0, .5, -.3)), false));
        }

        PacketReceiveEvent<ServerboundInteract> interact(InteractAction action) {
            return interact(new ServerboundInteract(COW, action, Hand.MAIN_HAND, Optional.empty(), false));
        }

        private PacketReceiveEvent<ServerboundInteract> interact(ServerboundInteract packet) {
            var event = route(ServerboundPackets.INTERACT, packet);
            if (!event.isCancelled()) {
                player.checkManager.dispatchPrePredictionReceive(event);
                player.checkManager.dispatchReceiveHandlers(event);
            }
            return event;
        }

        // A cow behind the player, who holds buckets in the first hotbar slot.
        void holdBuckets(int count) {
            player.compensatedEntities.addEntity(COW, EntityTypeIds.COW, new Vec3(.5, 64, -1), 0, 0, 0);
            SimItemStack bucket = ItemUtil.modelItems().stack("minecraft:bucket", count);
            serverSlot(Inventory.HOTBAR_OFFSET, bucket);
            player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET, bucket);
            player.getInventory().selectHotbarSlot(0);
        }

        int menuSlot(int storageSlot) {
            return player.getInventory().inventory.menuSlotForStorage(storageSlot);
        }

        // The server's value for a player storage slot, applied once the client answers its ping.
        void serverSlot(int storageSlot, SimItemStack item) {
            int menuSlot = player.getInventory().inventory.menuSlotForStorage(storageSlot);
            var packet = new InventoryPackets.Slot(0, 1, menuSlot, item);
            var event = new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, slotType(), packet, false);
            player.getInventory().onContainerSetSlot(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            for (int id = player.lastTransactionReceived.get() + 1; id <= player.lastTransactionSent.get(); id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
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

        @SuppressWarnings("unchecked")
        private static PacketType<InventoryPackets.Slot> slotType() {
            return (PacketType<InventoryPackets.Slot>) TestProtocolRuntime.catalog().stream()
                    .filter(type -> type.recordClass() == InventoryPackets.Slot.class)
                    .findFirst()
                    .orElseThrow();
        }

        <T extends ServerboundPacket> PacketReceiveEvent<T> route(PacketType<T> type, T packet) {
            var event = new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, type, packet);
            player.checkManager.dispatchEarlyReceive(event);
            return event;
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
