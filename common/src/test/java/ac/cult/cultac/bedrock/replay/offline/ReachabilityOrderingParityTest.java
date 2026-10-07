package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsW;
import ac.cult.cultac.checks.impl.elytra.ElytraA;
import ac.cult.cultac.checks.impl.elytra.ElytraB;
import ac.cult.cultac.checks.impl.movement.VehiclePredictionRunner;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.ping.TransactionOrder;
import ac.cult.cultac.checks.impl.vehicle.VehicleC;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.network.PacketReceiveHandler;
import ac.cult.cultac.network.PacketReceiveRoute;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.inventory.Inventory;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Queue;
import java.util.UUID;
import org.junit.Test;

public final class ReachabilityOrderingParityTest {
    @Test
    public void invalidEntityTargetCancellationStopsOrdinaryReceivePhase() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        try {
            BadPacketsW badPacketsW = player.checkManager.getListener(BadPacketsW.class);
            player.setExperimentalChecks(true);
            badPacketsW.setEnabled(true);
            PacketReceiveEvent event = RecordReceiveTestEvents.attack(player, 1_000_000);
            int[] ordinaryCalls = {0};

            dispatchThroughReceivePipeline(player, event, (receiveEvent, routedPlayer, packet) -> ordinaryCalls[0]++);

            assertTrue(event.isCancelled());
            assertEquals(1.0D, badPacketsW.violations, 0.0D);
            assertEquals(0, ordinaryCalls[0]);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockAttackDoesNotInvokeJavaOnlyBadPacketsW() throws Exception {
        CultPlayer player = offlineBedrockPlayer();
        try {
            BadPacketsW badPacketsW = player.checkManager.getListener(BadPacketsW.class);
            player.setExperimentalChecks(true);
            badPacketsW.setEnabled(true);
            PacketReceiveEvent event = RecordReceiveTestEvents.attack(player, 1_000_000);
            int[] ordinaryCalls = {0};
            PacketPlayerAttack attackListener = new PacketPlayerAttack();

            dispatchThroughReceivePipeline(
                    player,
                    event,
                    (receiveEvent, routedPlayer, routedPacket) ->
                            attackListener.onInteract(receiveEvent, routedPlayer, (ServerboundInteract) routedPacket),
                    (receiveEvent, routedPlayer, routedPacket) -> ordinaryCalls[0]++);

            assertFalse(event.isCancelled());
            assertEquals(0.0D, badPacketsW.violations, 0.0D);
            assertEquals(1, ordinaryCalls[0]);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void groundedGlideStartIsRejectedBeforeElytraA() {
        CultPlayer player = offlineJavaPlayer();
        try {
            TrackingElytraA elytraA = installTrackingElytraA(player);
            player.onGround = true;
            player.lastOnGround = false;
            player.isGliding = true;
            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));

            PacketReceiveEvent event = receiveEvent(player, glideStartPacket());
            new PacketEntityAction().onPlayerCommand(event, player, (ServerboundPlayerCommand) event.getPacket());

            assertTrue(event.isCancelled());
            assertEquals(0, elytraA.calls);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void elytraARunsBeforeInvalidEquipmentIsRejected() {
        CultPlayer player = offlineJavaPlayer();
        try {
            TrackingElytraA elytraA = installTrackingElytraA(player);
            player.onGround = false;
            player.lastOnGround = false;

            PacketReceiveEvent event = receiveEvent(player, glideStartPacket());
            new PacketEntityAction().onPlayerCommand(event, player, (ServerboundPlayerCommand) event.getPacket());

            assertEquals(1, elytraA.calls);
            assertFalse(elytraA.cancelledAtCall);
            assertTrue(event.isCancelled());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockTranslatedGlideCommandPreservesAuthInputState() {
        CultPlayer player = offlineBedrockPlayer();
        try {
            TrackingElytraA elytraA = installTrackingElytraA(player);
            player.onGround = false;
            player.lastOnGround = false;

            // The preserved G0 handler ignores translated Bedrock commands:
            // their actions belong to the preceding Bedrock movement phase.
            for (boolean gliding : new boolean[] {false, true}) {
                player.isGliding = gliding;
                PacketReceiveEvent event = receiveEvent(player, glideStartPacket());
                new PacketEntityAction().onPlayerCommand(event, player, (ServerboundPlayerCommand) event.getPacket());

                assertEquals(0, elytraA.calls);
                assertFalse(event.isCancelled());
                assertEquals(gliding, player.isGliding);
            }
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void groundedGlideRejectionDoesNotReachOrdinaryElytraChecks() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        try {
            player.onGround = true;
            player.lastOnGround = false;
            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));

            PacketReceiveEvent event = dispatchGlideStartThroughManagers(player);

            assertTrue(event.isCancelled());
            assertFalse(booleanField(player.checkManager.getListener(ElytraB.class), "glide"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void invalidEquipmentGlideRejectionDoesNotReachOrdinaryElytraChecks() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        try {
            player.onGround = false;
            player.lastOnGround = false;

            PacketReceiveEvent event = dispatchGlideStartThroughManagers(player);

            assertTrue(event.isCancelled());
            assertFalse(booleanField(player.checkManager.getListener(ElytraB.class), "glide"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void validGlideStartStillReachesOrdinaryElytraChecks() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        try {
            player.onGround = false;
            player.lastOnGround = false;
            ServerboundPlayerCommand packet = glideStartPacket();
            PacketReceiveEvent event = receiveEvent(player, packet);
            // PacketEntityAction hands accepted starts to the manager as an
            // uncancelled command. Exercise that boundary without constructing
            // a server-bound CraftItemStack in the offline harness.
            new CheckManagerListener().onPlayerCommand(event, player, packet);

            assertFalse(event.isCancelled());
            assertTrue(booleanField(player.checkManager.getListener(ElytraB.class), "glide"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void transactionOrderObservesQueueBeforeAcknowledgementMutation() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        try {
            player.keepAliveProcessor.lastKeepAlivePing = 1L;
            enqueueSentTransaction(player, 1, 101);
            enqueueSentTransaction(player, 2, 202);

            TrackingTransactionOrder order = new TrackingTransactionOrder(player);
            player.checkManager.allChecks.put(TransactionOrder.class, order);

            assertTrue(player.addTransactionResponse(202));
            assertEquals(1, order.skipped);
            assertEquals(2, order.queueSizeAtCallback);
            assertEquals(0, order.receivedAtCallback);
            assertEquals(0, sentTransactions(player).size());
            assertEquals(2, player.lastTransactionReceived.get());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockTransactionAcknowledgementSkipsJavaTransactionOrderCheck() throws Exception {
        CultPlayer player = offlineBedrockPlayer();
        try {
            player.keepAliveProcessor.lastKeepAlivePing = 1L;
            enqueueSentTransaction(player, 1, 101);
            enqueueSentTransaction(player, 2, 202);

            TrackingTransactionOrder order = new TrackingTransactionOrder(player);
            player.checkManager.allChecks.put(TransactionOrder.class, order);

            player.markBedrockTransactionClientbound(101);
            player.markBedrockTransactionClientbound(202);
            assertTrue(player.addTransactionResponse(202));
            assertEquals(0, order.skipped);
            assertEquals(0, sentTransactions(player).size());
            assertEquals(2, player.lastTransactionReceived.get());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void vehicleCRestoresPigAndStriderSelectedHandChecks() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        try {
            TrackingVehicleC vehicleC = new TrackingVehicleC(player);
            player.checkManager.allChecks.put(VehicleC.class, vehicleC);
            VehiclePredictionRunner runner = player.checkManager.getListener(VehiclePredictionRunner.class);
            Method evaluate = VehiclePredictionRunner.class.getDeclaredMethod(
                    "evaluateItemControlledVehicle", PacketEntity.class);
            evaluate.setAccessible(true);

            PacketEntity pig = new PacketEntity(EntityTypeIds.PIG, 10);
            evaluate.invoke(runner, pig);
            assertEquals(1, vehicleC.flags);

            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET, OfflineCultTestBootstrap.item("minecraft:carrot_on_a_stick"));
            evaluate.invoke(runner, pig);
            assertEquals(1, vehicleC.flags);

            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET, ac.cult.blocksim.engine.SimItemStack.EMPTY);
            PacketEntity strider = new PacketEntity(EntityTypeIds.STRIDER, 11);
            evaluate.invoke(runner, strider);
            assertEquals(2, vehicleC.flags);

            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(
                            Inventory.SLOT_OFFHAND,
                            OfflineCultTestBootstrap.item("minecraft:warped_fungus_on_a_stick"));
            evaluate.invoke(runner, strider);
            assertEquals(2, vehicleC.flags);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void vehicleCRemainsIsolatedFromBedrockVehiclePrediction() throws Exception {
        CultPlayer player = offlineBedrockPlayer();
        try {
            TrackingVehicleC vehicleC = new TrackingVehicleC(player);
            player.checkManager.allChecks.put(VehicleC.class, vehicleC);
            VehiclePredictionRunner runner = player.checkManager.getListener(VehiclePredictionRunner.class);
            Method evaluate = VehiclePredictionRunner.class.getDeclaredMethod(
                    "evaluateItemControlledVehicle", PacketEntity.class);
            evaluate.setAccessible(true);

            evaluate.invoke(runner, new PacketEntity(EntityTypeIds.PIG, 10));
            evaluate.invoke(runner, new PacketEntity(EntityTypeIds.STRIDER, 11));

            assertEquals(0, vehicleC.flags);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void rejectedVehicleMovementStillAdvancesVehicleTimerOnce() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            VehicleTimer timer = player.checkManager.getListener(VehicleTimer.class);
            long now = System.nanoTime();
            setLongField(timer, "timerBalanceRealTime", now - 1_000_000_000L);
            setLongField(timer, "lastMovementPlayerClock", now - 2_000_000_000L);
            long initial = longField(timer, "timerBalanceRealTime");

            ServerboundMoveVehicle packet = new ServerboundMoveVehicle(new Vec3d(0, 0, 0), 0.0F, 0.0F, false, true);
            PacketReceiveEvent event = receiveEvent(player, packet);
            new CheckManagerListener().onMoveVehicle(event, player, packet);

            assertTrue(event.isCancelled());
            assertEquals(50_000_000L, longField(timer, "timerBalanceRealTime") - initial);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void rejectedBedrockVehicleMovementSkipsJavaVehicleTimerCheck() {
        CultPlayer player = offlineBedrockPlayer();
        try {
            TrackingVehicleTimer timer = new TrackingVehicleTimer(player);
            player.checkManager.allChecks.put(VehicleTimer.class, timer);
            ServerboundMoveVehicle packet = new ServerboundMoveVehicle(new Vec3d(0, 0, 0), 0.0F, 0.0F, false, true);
            PacketReceiveEvent event = receiveEvent(player, packet);

            new CheckManagerListener().onMoveVehicle(event, player, packet);

            assertTrue(event.isCancelled());
            assertEquals(0, timer.calls);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void vehicleTimerKeepsCurrentConfigIdentity() {
        CultPlayer player = offlineJavaPlayer();
        try {
            assertEquals(
                    "TimerVehicle",
                    player.checkManager.getListener(VehicleTimer.class).getConfigName());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static TrackingElytraA installTrackingElytraA(CultPlayer player) {
        TrackingElytraA check = new TrackingElytraA(player);
        player.checkManager.allChecks.put(ElytraA.class, check);
        return check;
    }

    private static ServerboundPlayerCommand glideStartPacket() {
        return new ServerboundPlayerCommand(0, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
    }

    private static PacketReceiveEvent<ServerboundMoveVehicle> receiveEvent(
            CultPlayer player, ServerboundMoveVehicle packet) {
        return RecordReceiveTestEvents.vehicle(player, packet);
    }

    private static PacketReceiveEvent<ServerboundPlayerCommand> receiveEvent(
            CultPlayer player, ServerboundPlayerCommand packet) {
        return RecordReceiveTestEvents.playerCommand(player, packet);
    }

    @SafeVarargs
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void dispatchThroughReceivePipeline(
            CultPlayer player, PacketReceiveEvent event, PacketReceiveHandler<Object>... ordinaryHandlers)
            throws ReflectiveOperationException {
        PacketReceiveHandler<Object> earlyManager =
                (receiveEvent, routedPlayer, packet) -> routedPlayer.checkManager.dispatchEarlyReceive(receiveEvent);
        new ac.cult.cultac.network.PacketDispatcher.ReceiveRoute(
                        PacketReceiveRoute.of(new PacketReceiveHandler[] {earlyManager}),
                        PacketReceiveRoute.of(ordinaryHandlers),
                        PacketReceiveRoute.EMPTY)
                .dispatch(event, player);
    }

    private static PacketReceiveEvent dispatchGlideStartThroughManagers(CultPlayer player) {
        ServerboundPlayerCommand packet = glideStartPacket();
        PacketReceiveEvent event = receiveEvent(player, packet);
        new PacketEntityAction().onPlayerCommand(event, player, packet);
        new CheckManagerListener().onPlayerCommand(event, player, packet);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Queue<Object> sentTransactions(CultPlayer player) throws ReflectiveOperationException {
        Field field = CultPlayer.class.getDeclaredField("transactionsSent");
        field.setAccessible(true);
        return (Queue<Object>) field.get(player);
    }

    private static void enqueueSentTransaction(CultPlayer player, int transaction, int id)
            throws ReflectiveOperationException {
        Class<?> sentTransaction = Class.forName("ac.cult.cultac.player.CultPlayer$"
                + (player.isBedrockMovement() ? "BedrockTransaction" : "SentTransaction"));
        Constructor<?> constructor = player.isBedrockMovement()
                ? sentTransaction.getDeclaredConstructor(int.class, int.class)
                : sentTransaction.getDeclaredConstructor(int.class, int.class, long.class);
        constructor.setAccessible(true);
        sentTransactions(player)
                .add(
                        player.isBedrockMovement()
                                ? constructor.newInstance(transaction, id)
                                : constructor.newInstance(transaction, id, System.nanoTime()));
    }

    private static long longField(Object target, String name) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        return field.getLong(target);
    }

    private static boolean booleanField(Object target, String name) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        return field.getBoolean(target);
    }

    private static void setLongField(Object target, String name, long value) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        field.setLong(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static CultPlayer offlineJavaPlayer() {
        OfflineCultTestBootstrap.installConfig();
        UUID playerId = UUID.randomUUID();
        User user = ac.cult.cultac.network.TestUsers.create(
                new User.Profile(playerId, ".Reachability_Order_Test"), new EmbeddedChannel());
        return new CultPlayer(user);
    }

    private static CultPlayer offlineBedrockPlayer() {
        OfflineCultTestBootstrap.installConfig();
        UUID playerId = UUID.randomUUID();
        User user = ac.cult.cultac.network.TestUsers.create(
                new User.Profile(playerId, ".Reachability_Bedrock_Test"), new EmbeddedChannel());
        return new CultPlayer(user, MovementPlatform.BEDROCK, new BedrockPlayerState(playerId));
    }

    private static final class TrackingElytraA extends ElytraA {
        private int calls;
        private boolean cancelledAtCall;

        private TrackingElytraA(CultPlayer player) {
            super(player);
        }

        @Override
        public void onStartGliding(PacketReceiveEvent event) {
            calls++;
            cancelledAtCall = event.isCancelled();
        }
    }

    private static final class TrackingTransactionOrder extends TransactionOrder {
        private int skipped;
        private int queueSizeAtCallback;
        private int receivedAtCallback;

        private TrackingTransactionOrder(CultPlayer player) {
            super(player);
        }

        @Override
        public void skipped(int skipped) {
            this.skipped = skipped;
            try {
                queueSizeAtCallback = sentTransactions(player).size();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
            receivedAtCallback = player.lastTransactionReceived.get();
        }
    }

    private static final class TrackingVehicleTimer extends VehicleTimer {
        private int calls;

        private TrackingVehicleTimer(CultPlayer player) {
            super(player);
        }

        @Override
        public void onMoveVehicle(PacketReceiveEvent event, CultPlayer player, ServerboundMoveVehicle packet) {
            calls++;
        }
    }

    private static final class TrackingVehicleC extends VehicleC {
        private int flags;

        private TrackingVehicleC(CultPlayer player) {
            super(player);
        }

        @Override
        public boolean flag(String verbose) {
            flags++;
            return true;
        }
    }
}
