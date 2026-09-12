package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.checks.impl.multiactions.MultiActionsE;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsH;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsB;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsO;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsV;
import ac.cult.cultac.checks.impl.combat.Hitboxes;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderB;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderH;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderO;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderP;
import ac.cult.cultac.checks.impl.post.PostCheck;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.checks.impl.movement.timer.TickTimer;
import ac.cult.cultac.checks.impl.movement.timer.NegativeTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.sprint.SprintB;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.checks.impl.vehicle.VehicleC;
import ac.cult.cultac.checks.type.LegacyPacketEventSemantics;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.type.OrderedPacketReceiveListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.PacketHandlerScanner;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.data.LastInstance;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import com.google.common.collect.ClassToInstanceMap;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.bukkit.block.BlockFace;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class OrderedLegacyCheckDispatchTest {
    @Test
    public void activeStreamChecksUseOneOrderedCallbackAndDeadCheckStaysUnregistered() {
        assertTrue(OrderedPacketReceiveListener.class.isAssignableFrom(MultiActionsE.class));
        assertTrue(OrderedPacketReceiveListener.class.isAssignableFrom(PacketOrderB.class));
        assertTrue(OrderedPacketReceiveListener.class.isAssignableFrom(PacketOrderO.class));
        assertFalse(PacketHandlerScanner.hasReceiveHandlerDeclaration(MultiActionsE.class));
        assertFalse(PacketHandlerScanner.hasReceiveHandlerDeclaration(PacketOrderB.class));
        assertFalse(PacketHandlerScanner.hasReceiveHandlerDeclaration(PacketOrderO.class));

        CultPlayer player = offlineJavaPlayer();
        try {
            assertFalse(player.checkManager.getAllChecks().stream()
                    .anyMatch(check -> check instanceof PacketOrderP));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void interveningOnlyPacketsCloseMultiActionsDropWindow() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            MultiActionsE check = player.checkManager.getListener(MultiActionsE.class);
            ServerboundPlayerActionPacket drop = new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.DROP_ITEM,
                    BlockPos.ZERO,
                    Direction.DOWN,
                    0);
            PacketReceiveEvent dropEvent = receiveEvent(player, drop);
            player.checkManager.dispatchEarlyReceive(dropEvent);
            if (!dropEvent.isCancelled()) {
                player.checkManager.dispatchReceiveHandlers(dropEvent);
            }
            assertTrue(booleanField(check, "dropping"));

            // SignUpdate intentionally bypasses normal check dispatch. It still
            // has to close the legacy one-packet DROP -> SWING exemption.
            ServerboundSignUpdatePacket sign = new ServerboundSignUpdatePacket(
                    BlockPos.ZERO, java.util.List.of("", "", "", ""), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
            PacketReceiveEvent signEvent = receiveEvent(player, sign);
            player.checkManager.dispatchEarlyReceive(signEvent);
            if (!signEvent.isCancelled()) {
                new CheckManagerListener().onSignUpdate(signEvent, player, sign);
            }
            assertFalse(booleanField(check, "dropping"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void packetOrderBPreservesPacketEventsAsyncBoundary() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            PacketOrderB check = player.checkManager.getListener(PacketOrderB.class);
            check.onPacketReceive(receiveEvent(player, new ServerboundAttackPacket(7)));
            assertTrue(booleanField(check, "sentAttack"));

            check.onPacketReceive(receiveEvent(player, new ServerboundKeepAlivePacket(11L)));
            assertTrue(booleanField(check, "sentAttack"));

            check.onPacketReceive(receiveEvent(player, new ServerboundPongPacket(12)));
            assertFalse(booleanField(check, "sentAttack"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void packetOrderOTracksMovementUntilTickEnd() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            PacketOrderO check = player.checkManager.getListener(PacketOrderO.class);
            ServerboundMovePlayerPacket movement =
                    new ServerboundMovePlayerPacket.StatusOnly(true, false);
            check.onPacketReceive(receiveEvent(player, movement));
            assertTrue(booleanField(check, "flying"));

            check.onPacketReceive(receiveEvent(player, new ServerboundKeepAlivePacket(21L)));
            assertTrue(booleanField(check, "flying"));

            check.onPacketReceive(receiveEvent(player, ServerboundClientTickEndPacket.INSTANCE));
            assertFalse(booleanField(check, "flying"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void postCheckClosesOnlyForAnAcceptedInboundTransaction() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            PostCheck check = player.checkManager.getListener(PostCheck.class);
            ServerboundMovePlayerPacket movement = new ServerboundMovePlayerPacket.StatusOnly(true, false);
            player.checkManager.dispatchReceiveHandlers(receiveEvent(player, movement));

            ServerboundPlayerActionPacket action = new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.DROP_ITEM,
                    BlockPos.ZERO,
                    Direction.DOWN,
                    0);
            player.checkManager.dispatchReceiveHandlers(receiveEvent(player, action));
            assertTrue(objectField(check, "post") != null);

            PacketReceiveEvent unmatchedPong = receiveEvent(player, new ServerboundPongPacket(90));
            player.checkManager.dispatchReceiveHandlers(unmatchedPong);
            assertTrue(objectField(check, "post") != null);
            assertTrue(booleanField(check, "sentFlying"));

            PacketReceiveEvent acceptedPong = receiveEvent(player, new ServerboundPongPacket(91));
            acceptedPong.setAcceptedTransactionResponse(true);
            player.checkManager.dispatchReceiveHandlers(acceptedPong);
            assertTrue(objectField(check, "post") == null);
            assertFalse(booleanField(check, "sentFlying"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void legacyAsyncClassificationMatchesPacketEvents() {
        assertTrue(LegacyPacketEventSemantics.isAsync(new ServerboundKeepAlivePacket(1L)));
        assertFalse(LegacyPacketEventSemantics.isAsync(new ServerboundPongPacket(1)));
    }

    @Test
    public void legacyTimerCallbacksRemainRegisteredWithoutRevivingNegativeTimer() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            TickTimer tickTimer = player.checkManager.getListener(TickTimer.class);
            assertTrue(player.checkManager.getListener(DumbTimer.class) != null);
            assertTrue(player.checkManager.getListener(NegativeTimerCheck.class) != null);
            assertTrue(NegativeTimerCheck.class.isAnnotationPresent(DeadCheck.class));
            assertTrue(PacketHandlerScanner.hasReceiveHandlerDeclaration(DumbTimer.class));
            assertTrue(PacketHandlerScanner.hasReceiveHandlerDeclaration(NegativeTimerCheck.class));

            ServerboundMovePlayerPacket movement =
                    new ServerboundMovePlayerPacket.StatusOnly(true, false);
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, movement));
            assertFalse(booleanField(tickTimer, "receivedTickEnd"));

            player.checkManager.dispatchPrePredictionReceive(
                    receiveEvent(player, ServerboundClientTickEndPacket.INSTANCE));
            assertTrue(booleanField(tickTimer, "receivedTickEnd"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void badPacketsHReceivesItsRestoredPlaceAndBreakCallbacks() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            BadPacketsH check = player.checkManager.getListener(BadPacketsH.class);
            assertTrue(check instanceof BlockBreakListener);

            Field blockPlaceChecks = player.checkManager.getClass().getDeclaredField("blockPlaceCheck");
            blockPlaceChecks.setAccessible(true);
            ClassToInstanceMap<?> checks = (ClassToInstanceMap<?>) blockPlaceChecks.get(player.checkManager);
            assertTrue(checks.containsKey(BadPacketsH.class));

            BlockBreak blockBreak = new BlockBreak(
                    player,
                    BlockPos.ZERO,
                    BlockFace.DOWN,
                    Direction.DOWN.get3DDataValue(),
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    7,
                    Blocks.STONE.defaultBlockState());
            check.onBlockBreak(blockBreak);
            assertTrue(intField(check, "lastSequence") == 7);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void badPacketsVTeleportGraceExpiresAfterOneCompletedTick() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            BadPacketsV check = player.checkManager.getListener(BadPacketsV.class);
            ServerboundMovePlayerPacket movement =
                    new ServerboundMovePlayerPacket.StatusOnly(true, false);

            player.packetStateData.lastPacketWasTeleport = true;
            check.onMovePlayer(receiveEvent(player, movement), player, movement);

            Field field = BadPacketsV.class.getDeclaredField("lastTeleportTicks");
            field.setAccessible(true);
            LastInstance grace = (LastInstance) field.get(check);
            assertTrue(grace.hasOccurredSince(1));

            player.lastInstanceManager.onPredictionComplete(null);
            assertTrue(grace.hasOccurredSince(1));

            player.lastInstanceManager.onPredictionComplete(null);
            assertFalse(grace.hasOccurredSince(1));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void badPacketsOKeepsAllOutstandingIdsUntilAcknowledged() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            BadPacketsO check = player.checkManager.getListener(BadPacketsO.class);
            for (long id = 0; id < 25; id++) {
                check.onKeepAlive(null, player, new ClientboundKeepAlivePacket(id));
            }

            check.onKeepAlive(
                    receiveEvent(player, new ServerboundKeepAlivePacket(0L)),
                    player,
                    new ServerboundKeepAlivePacket(0L));

            Field field = BadPacketsO.class.getDeclaredField("keepalives");
            field.setAccessible(true);
            LinkedList<?> outstanding = (LinkedList<?>) field.get(check);
            assertTrue(outstanding.size() == 24);
            assertTrue(outstanding.getFirst().equals(1L));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void deletedSprintChecksAreRegisteredAndSprintEReceivesCommands() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            assertTrue(player.checkManager.getListener(SprintB.class) != null);
            SprintE sprintE = player.checkManager.getListener(SprintE.class);
            assertTrue(sprintE != null);

            ServerboundPlayerCommandPacket startSprint = new ServerboundPlayerCommandPacket(
                    Mockito.mock(Entity.class),
                    ServerboundPlayerCommandPacket.Action.START_SPRINTING,
                    0);
            player.checkManager.dispatchReceiveHandlers(receiveEvent(player, startSprint));
            assertTrue(booleanField(sprintE, "startedSprintingThisTick"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void timerPreservesTheExistingModernTickEndClock() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            TimerCheck timer = player.checkManager.getListener(TimerCheck.class);
            long now = System.nanoTime();
            setLongField(timer, "timerBalanceRealTime", now - 1_000_000_000L);
            setLongField(timer, "knownPlayerClockTime", now - 2_000_000_000L);
            long initial = longField(timer, "timerBalanceRealTime");

            ServerboundMovePlayerPacket move = new ServerboundMovePlayerPacket.StatusOnly(false, false);
            player.packetStateData.receivedMovementThisClientTick = true;
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, move));
            long afterMove = longField(timer, "timerBalanceRealTime");
            assertTrue(afterMove == initial);

            ServerboundClientTickEndPacket tickEnd = ServerboundClientTickEndPacket.INSTANCE;
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, tickEnd));
            assertTrue(longField(timer, "timerBalanceRealTime") - afterMove == 50_000_000L);

            player.packetStateData.receivedMovementThisClientTick = false;
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, tickEnd));
            assertTrue(longField(timer, "timerBalanceRealTime") - afterMove == 100_000_000L);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void timerClampAndVehicleCadencePreserveCurrentAccounting() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            TimerCheck timer = player.checkManager.getListener(TimerCheck.class);
            setLongField(timer, "timerBalanceRealTime", 1_000L);
            setLongField(timer, "knownPlayerClockTime", 5_000L);
            setLongField(timer, "lastMovementPlayerClock", 10_000L);
            setLongField(timer, "clockDrift", 100L);
            timer.doCheck(null);
            assertTrue(longField(timer, "timerBalanceRealTime") == 4_900L);

            VehicleTimer vehicleTimer = player.checkManager.getListener(VehicleTimer.class);
            long now = System.nanoTime();
            setLongField(vehicleTimer, "timerBalanceRealTime", now - 1_000_000_000L);
            setLongField(vehicleTimer, "lastMovementPlayerClock", now - 2_000_000_000L);
            long initial = longField(vehicleTimer, "timerBalanceRealTime");
            ServerboundMoveVehiclePacket moveVehicle =
                    new ServerboundMoveVehiclePacket(net.minecraft.core.PositionAndRotation.of(Vec3.ZERO, 0.0F, 0.0F), false);
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, moveVehicle));
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, moveVehicle));
            assertTrue(longField(vehicleTimer, "timerBalanceRealTime") - initial == 50_000_000L);

            setLongField(vehicleTimer, "timerBalanceRealTime", now - 1_000_000_000L);
            setLongField(vehicleTimer, "lastMovementPlayerClock", now - 2_000_000_000L);
            initial = longField(vehicleTimer, "timerBalanceRealTime");
            vehicleTimer.onClientTickEnd(
                    receiveEvent(player, ServerboundClientTickEndPacket.INSTANCE),
                    player,
                    ServerboundClientTickEndPacket.INSTANCE);
            player.compensatedEntities.getSelf().mount(
                    new PacketEntity(EntityTypesCompat.OAK_BOAT, 99));
            vehicleTimer.handleLegacySteerVehicle();
            vehicleTimer.handleLegacySteerVehicle();
            assertTrue(longField(vehicleTimer, "timerBalanceRealTime") - initial == 50_000_000L);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void timerChecksRetainTheirCurrentConfigIdentities() {
        CultPlayer player = offlineJavaPlayer();
        try {
            assertTrue("TimerA".equals(player.checkManager.getListener(TimerCheck.class).getConfigName()));
            assertTrue("TimerVehicle".equals(player.checkManager.getListener(VehicleTimer.class).getConfigName()));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void packetOrderHDoesNotTreatModernInputAsLegacySneakAction() {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            PacketOrderH check = player.checkManager.getListener(PacketOrderH.class);
            assertTrue(check != null);
            assertFalse(PacketOrderH.class.isAnnotationPresent(DeadCheck.class));

            ServerboundPlayerInputPacket shifted = new ServerboundPlayerInputPacket(
                    new Input(false, false, false, false, false, true, false));
            player.checkManager.dispatchReceiveHandlers(receiveEvent(player, shifted));

            assertFalse(player.packetOrderProcessor.isSneaking());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void callbackOnlyChecksAreConstructedAndNotMarkedDead() {
        CultPlayer player = offlineJavaPlayer();
        try {
            assertTrue(player.checkManager.getListener(BadPacketsB.class) != null);
            assertTrue(player.checkManager.getListener(Hitboxes.class) != null);
            assertTrue(player.checkManager.getListener(VehicleC.class) != null);
            assertFalse(BadPacketsB.class.isAnnotationPresent(DeadCheck.class));
            assertFalse(Hitboxes.class.isAnnotationPresent(DeadCheck.class));
            assertFalse(VehicleC.class.isAnnotationPresent(DeadCheck.class));
            assertFalse(player.checkManager.getListener(VehicleC.class) instanceof PostPredictionListener);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void packetOrderedItemUseStatePreservesSlotHandAndReleaseSemantics() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            MultiActionsE check = player.checkManager.getListener(MultiActionsE.class);
            player.packetStateData.lastSlotSelected = 2;
            player.packetStateData.itemInUseHand = InteractionHand.MAIN_HAND;
            player.packetStateData.setSlowedByUsingItem(true);
            assertTrue(invokeBoolean(check, "isActivelyUsingItem"));

            player.packetStateData.lastSlotSelected = 3;
            assertFalse(invokeBoolean(check, "isActivelyUsingItem"));

            player.packetStateData.lastSlotSelected = 2;
            player.packetStateData.itemInUseHand = InteractionHand.OFF_HAND;
            player.packetStateData.setSlowedByUsingItem(true);
            player.packetStateData.lastSlotSelected = 3;
            assertTrue(invokeBoolean(check, "isActivelyUsingItem"));

            player.packetStateData.itemInUseHand = InteractionHand.MAIN_HAND;
            ServerboundMovePlayerPacket.StatusOnly movement =
                    new ServerboundMovePlayerPacket.StatusOnly(true, false);
            player.actionManager.onMovePlayerStatusOnly(receiveEvent(player, movement), player, movement);
            assertFalse(player.packetStateData.isSlowedByUsingItem());

            player.packetStateData.lastSlotSelected = 3;
            player.packetStateData.setSlowedByUsingItem(true);
            ServerboundPlayerActionPacket release = new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                    BlockPos.ZERO,
                    Direction.DOWN,
                    0);
            player.actionManager.onPlayerAction(receiveEvent(player, release), player, release);
            assertFalse(player.packetStateData.isSlowedByUsingItem());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void miningAttributesRemainTransactionCompensatedState() {
        CultPlayer player = offlineJavaPlayer();
        try {
            player.compensatedEntities.updateAttributes(player.entityID, List.of(
                    new ClientboundUpdateAttributesPacket.AttributeSnapshot(Attributes.BLOCK_BREAK_SPEED, 1.75D, List.of()),
                    new ClientboundUpdateAttributesPacket.AttributeSnapshot(Attributes.MINING_EFFICIENCY, 4.0D, List.of()),
                    new ClientboundUpdateAttributesPacket.AttributeSnapshot(Attributes.SUBMERGED_MINING_SPEED, 0.6D, List.of())
            ));

            assertEquals(1.75D, player.compensatedEntities.getSelf().blockBreakSpeed, 0.0D);
            assertEquals(4.0D, player.compensatedEntities.getSelf().miningEfficiency, 0.0D);
            assertEquals(0.6D, player.compensatedEntities.getSelf().submergedMiningSpeed, 1.0E-7D);

        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static PacketReceiveEvent receiveEvent(CultPlayer player, Packet<?> packet) {
        return new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);
    }

    private static boolean booleanField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static boolean invokeBoolean(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (boolean) method.invoke(target);
    }

    private static int intField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static Object objectField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static long longField(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getLong(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setLongField(Object target, String name, long value) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setLong(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static CultPlayer offlineJavaPlayer() {
        OfflineCultTestBootstrap.installConfig();
        UUID playerId = UUID.randomUUID();
        User user = new User(
                new User.Profile(playerId, ".Ordered_Check_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new CultPlayer(user);
    }
}
