package ac.grim.grimac.bedrock.replay.offline;

import ac.grim.grimac.bedrock.MovementPlatform;
import ac.grim.grimac.bedrock.player.BedrockPlayerState;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsW;
import ac.grim.grimac.checks.impl.elytra.ElytraA;
import ac.grim.grimac.checks.impl.elytra.ElytraB;
import ac.grim.grimac.checks.impl.movement.VehiclePredictionRunner;
import ac.grim.grimac.checks.impl.movement.timer.VehicleTimer;
import ac.grim.grimac.checks.impl.ping.TransactionOrder;
import ac.grim.grimac.checks.impl.vehicle.VehicleC;
import ac.grim.grimac.events.packets.listeners.CheckManagerListener;
import ac.grim.grimac.events.packets.listeners.PacketEntityAction;
import ac.grim.grimac.events.packets.listeners.PacketPlayerAttack;
import ac.grim.grimac.network.PacketReceiveHandler;
import ac.grim.grimac.network.PacketReceiveRoute;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReachabilityOrderingParityTest {
    @Test
    public void invalidEntityTargetCancellationStopsOrdinaryReceivePhase() throws Exception {
        GrimPlayer player = offlineJavaPlayer();
        try {
            BadPacketsW badPacketsW = player.checkManager.getListener(BadPacketsW.class);
            player.setExperimentalChecks(true);
            badPacketsW.setEnabled(true);
            PacketReceiveEvent event = receiveEvent(player, new ServerboundAttackPacket(1_000_000));
            int[] ordinaryCalls = {0};

            dispatchThroughReceivePipeline(
                    player,
                    event,
                    (receiveEvent, routedPlayer, packet) -> ordinaryCalls[0]++);

            assertTrue(event.isCancelled());
            assertEquals(1.0D, badPacketsW.violations, 0.0D);
            assertEquals(0, ordinaryCalls[0]);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockAttackDoesNotInvokeJavaOnlyBadPacketsW() throws Exception {
        GrimPlayer player = offlineBedrockPlayer();
        try {
            BadPacketsW badPacketsW = player.checkManager.getListener(BadPacketsW.class);
            player.setExperimentalChecks(true);
            badPacketsW.setEnabled(true);
            ServerboundAttackPacket packet = new ServerboundAttackPacket(1_000_000);
            PacketReceiveEvent event = receiveEvent(player, packet);
            int[] ordinaryCalls = {0};
            PacketPlayerAttack attackListener = new PacketPlayerAttack();

            dispatchThroughReceivePipeline(
                    player,
                    event,
                    (receiveEvent, routedPlayer, routedPacket) ->
                            attackListener.onAttack(receiveEvent, routedPlayer, routedPacket),
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
        GrimPlayer player = offlineJavaPlayer();
        try {
            TrackingElytraA elytraA = installTrackingElytraA(player);
            player.onGround = true;
            player.lastOnGround = false;
            player.isGliding = true;
            player.getInventory().inventory.getInventoryStorage().setItem(
                    Inventory.SLOT_CHESTPLATE,
                    new MaterialItemStack(Material.ELYTRA));

            PacketReceiveEvent event = receiveEvent(player, glideStartPacket());
            new PacketEntityAction().onPlayerCommand(
                    event, player, (ServerboundPlayerCommandPacket) event.getNmsPacket());

            assertTrue(event.isCancelled());
            assertEquals(0, elytraA.calls);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void elytraARunsBeforeInvalidEquipmentIsRejected() {
        GrimPlayer player = offlineJavaPlayer();
        try {
            TrackingElytraA elytraA = installTrackingElytraA(player);
            player.onGround = false;
            player.lastOnGround = false;

            PacketReceiveEvent event = receiveEvent(player, glideStartPacket());
            new PacketEntityAction().onPlayerCommand(
                    event, player, (ServerboundPlayerCommandPacket) event.getNmsPacket());

            assertEquals(1, elytraA.calls);
            assertFalse(elytraA.cancelledAtCall);
            assertTrue(event.isCancelled());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockGlideActionSkipsElytraAButRetainsEquipmentRejection() {
        GrimPlayer player = offlineBedrockPlayer();
        try {
            TrackingElytraA elytraA = installTrackingElytraA(player);
            player.onGround = false;
            player.lastOnGround = false;

            PacketReceiveEvent event = receiveEvent(player, glideStartPacket());
            new PacketEntityAction().onPlayerCommand(
                    event, player, (ServerboundPlayerCommandPacket) event.getNmsPacket());

            assertEquals(0, elytraA.calls);
            assertTrue(event.isCancelled());
            assertFalse(player.isGliding);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void groundedGlideRejectionDoesNotReachOrdinaryElytraChecks() throws Exception {
        GrimPlayer player = offlineJavaPlayer();
        try {
            player.onGround = true;
            player.lastOnGround = false;
            player.getInventory().inventory.getInventoryStorage().setItem(
                    Inventory.SLOT_CHESTPLATE,
                    new MaterialItemStack(Material.ELYTRA));

            PacketReceiveEvent event = dispatchGlideStartThroughManagers(player);

            assertTrue(event.isCancelled());
            assertFalse(booleanField(player.checkManager.getListener(ElytraB.class), "glide"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void invalidEquipmentGlideRejectionDoesNotReachOrdinaryElytraChecks() throws Exception {
        GrimPlayer player = offlineJavaPlayer();
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
        GrimPlayer player = offlineJavaPlayer();
        try {
            player.onGround = false;
            player.lastOnGround = false;
            ServerboundPlayerCommandPacket packet = glideStartPacket();
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
        GrimPlayer player = offlineJavaPlayer();
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
        GrimPlayer player = offlineBedrockPlayer();
        try {
            player.keepAliveProcessor.lastKeepAlivePing = 1L;
            enqueueSentTransaction(player, 1, 101);
            enqueueSentTransaction(player, 2, 202);

            TrackingTransactionOrder order = new TrackingTransactionOrder(player);
            player.checkManager.allChecks.put(TransactionOrder.class, order);

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
        GrimPlayer player = offlineJavaPlayer();
        try {
            TrackingVehicleC vehicleC = new TrackingVehicleC(player);
            player.checkManager.allChecks.put(VehicleC.class, vehicleC);
            VehiclePredictionRunner runner = player.checkManager.getListener(VehiclePredictionRunner.class);
            Method evaluate = VehiclePredictionRunner.class.getDeclaredMethod(
                    "evaluateItemControlledVehicle", PacketEntity.class);
            evaluate.setAccessible(true);

            PacketEntity pig = new PacketEntity(EntityTypesCompat.PIG, 10);
            evaluate.invoke(runner, pig);
            assertEquals(1, vehicleC.flags);

            player.getInventory().inventory.getInventoryStorage().setItem(
                    Inventory.HOTBAR_OFFSET, new MaterialItemStack(Material.CARROT_ON_A_STICK));
            evaluate.invoke(runner, pig);
            assertEquals(1, vehicleC.flags);

            player.getInventory().inventory.getInventoryStorage().setItem(
                    Inventory.HOTBAR_OFFSET, ItemStack.empty());
            PacketEntity strider = new PacketEntity(EntityTypesCompat.STRIDER, 11);
            evaluate.invoke(runner, strider);
            assertEquals(2, vehicleC.flags);

            player.getInventory().inventory.getInventoryStorage().setItem(
                    Inventory.SLOT_OFFHAND, new MaterialItemStack(Material.WARPED_FUNGUS_ON_A_STICK));
            evaluate.invoke(runner, strider);
            assertEquals(2, vehicleC.flags);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void vehicleCRemainsIsolatedFromBedrockVehiclePrediction() throws Exception {
        GrimPlayer player = offlineBedrockPlayer();
        try {
            TrackingVehicleC vehicleC = new TrackingVehicleC(player);
            player.checkManager.allChecks.put(VehicleC.class, vehicleC);
            VehiclePredictionRunner runner = player.checkManager.getListener(VehiclePredictionRunner.class);
            Method evaluate = VehiclePredictionRunner.class.getDeclaredMethod(
                    "evaluateItemControlledVehicle", PacketEntity.class);
            evaluate.setAccessible(true);

            evaluate.invoke(runner, new PacketEntity(EntityTypesCompat.PIG, 10));
            evaluate.invoke(runner, new PacketEntity(EntityTypesCompat.STRIDER, 11));

            assertEquals(0, vehicleC.flags);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void rejectedVehicleMovementStillAdvancesVehicleTimerOnce() throws Exception {
        GrimPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            VehicleTimer timer = player.checkManager.getListener(VehicleTimer.class);
            long now = System.nanoTime();
            setLongField(timer, "timerBalanceRealTime", now - 1_000_000_000L);
            setLongField(timer, "lastMovementPlayerClock", now - 2_000_000_000L);
            long initial = longField(timer, "timerBalanceRealTime");

            ServerboundMoveVehiclePacket packet =
                    new ServerboundMoveVehiclePacket(Vec3.ZERO, 0.0F, 0.0F, false);
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
        GrimPlayer player = offlineBedrockPlayer();
        try {
            TrackingVehicleTimer timer = new TrackingVehicleTimer(player);
            player.checkManager.allChecks.put(VehicleTimer.class, timer);
            ServerboundMoveVehiclePacket packet =
                    new ServerboundMoveVehiclePacket(Vec3.ZERO, 0.0F, 0.0F, false);
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
        GrimPlayer player = offlineJavaPlayer();
        try {
            assertEquals("TimerVehicle",
                    player.checkManager.getListener(VehicleTimer.class).getConfigName());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static TrackingElytraA installTrackingElytraA(GrimPlayer player) {
        TrackingElytraA check = new TrackingElytraA(player);
        player.checkManager.allChecks.put(ElytraA.class, check);
        return check;
    }

    private static ServerboundPlayerCommandPacket glideStartPacket() {
        return new ServerboundPlayerCommandPacket(
                Mockito.mock(Entity.class),
                ServerboundPlayerCommandPacket.Action.START_FALL_FLYING,
                0);
    }

    private static PacketReceiveEvent receiveEvent(GrimPlayer player, Packet<?> packet) {
        return new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);
    }

    @SafeVarargs
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void dispatchThroughReceivePipeline(
            GrimPlayer player,
            PacketReceiveEvent event,
            PacketReceiveHandler<Packet<?>>... ordinaryHandlers
    ) throws ReflectiveOperationException {
        Class<?> pipeline = Class.forName("ac.grim.grimac.network.PacketReceivePipeline");
        Method dispatch = pipeline.getDeclaredMethod(
                "dispatch",
                PacketReceiveRoute.class,
                PacketReceiveRoute.class,
                PacketReceiveRoute.class,
                PacketReceiveEvent.class,
                GrimPlayer.class,
                Packet.class);
        dispatch.setAccessible(true);
        PacketReceiveHandler<Packet<?>> earlyManager =
                (receiveEvent, routedPlayer, packet) -> routedPlayer.checkManager.dispatchEarlyReceive(receiveEvent);
        dispatch.invoke(
                null,
                PacketReceiveRoute.of(new PacketReceiveHandler[]{earlyManager}),
                PacketReceiveRoute.of(ordinaryHandlers),
                PacketReceiveRoute.EMPTY,
                event,
                player,
                event.getNmsPacket());
    }

    private static PacketReceiveEvent dispatchGlideStartThroughManagers(GrimPlayer player) {
        ServerboundPlayerCommandPacket packet = glideStartPacket();
        PacketReceiveEvent event = receiveEvent(player, packet);
        new PacketEntityAction().onPlayerCommand(event, player, packet);
        new CheckManagerListener().onPlayerCommand(event, player, packet);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Queue<Object> sentTransactions(GrimPlayer player) throws ReflectiveOperationException {
        Field field = GrimPlayer.class.getDeclaredField("transactionsSent");
        field.setAccessible(true);
        return (Queue<Object>) field.get(player);
    }

    private static void enqueueSentTransaction(GrimPlayer player, int transaction, int id)
            throws ReflectiveOperationException {
        Class<?> sentTransaction = Class.forName("ac.grim.grimac.player.GrimPlayer$SentTransaction");
        Constructor<?> constructor = sentTransaction.getDeclaredConstructor(int.class, int.class, long.class);
        constructor.setAccessible(true);
        sentTransactions(player).add(constructor.newInstance(transaction, id, System.nanoTime()));
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

    private static GrimPlayer offlineJavaPlayer() {
        OfflineGrimTestBootstrap.installConfig();
        UUID playerId = UUID.randomUUID();
        User user = new User(
                new User.Profile(playerId, ".Reachability_Order_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new GrimPlayer(user);
    }

    private static GrimPlayer offlineBedrockPlayer() {
        OfflineGrimTestBootstrap.installConfig();
        UUID playerId = UUID.randomUUID();
        User user = new User(
                new User.Profile(playerId, ".Reachability_Bedrock_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new GrimPlayer(user, MovementPlatform.BEDROCK, new BedrockPlayerState(playerId));
    }

    private static final class TrackingElytraA extends ElytraA {
        private int calls;
        private boolean cancelledAtCall;

        private TrackingElytraA(GrimPlayer player) {
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

        private TrackingTransactionOrder(GrimPlayer player) {
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

        private TrackingVehicleTimer(GrimPlayer player) {
            super(player);
        }

        @Override
        public void onMoveVehicle(
                PacketReceiveEvent event,
                GrimPlayer player,
                ServerboundMoveVehiclePacket packet
        ) {
            calls++;
        }
    }

    private static final class TrackingVehicleC extends VehicleC {
        private int flags;

        private TrackingVehicleC(GrimPlayer player) {
            super(player);
        }

        @Override
        public boolean flag(String verbose) {
            flags++;
            return true;
        }
    }

    private static final class MaterialItemStack extends ItemStack {
        private final Material material;

        private MaterialItemStack(Material material) {
            this.material = material;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public Map<Enchantment, Integer> getEnchantments() {
            return Map.of();
        }

        @Override
        public int getEnchantmentLevel(Enchantment enchantment) {
            return 0;
        }
    }
}
