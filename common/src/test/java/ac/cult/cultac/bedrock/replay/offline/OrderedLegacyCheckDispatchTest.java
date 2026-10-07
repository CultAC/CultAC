package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsB;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsH;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsO;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsV;
import ac.cult.cultac.checks.impl.combat.Hitboxes;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.NegativeTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TickTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsE;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderB;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderH;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderO;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderP;
import ac.cult.cultac.checks.impl.post.PostCheck;
import ac.cult.cultac.checks.impl.sprint.SprintB;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.checks.impl.vehicle.VehicleC;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.type.LegacyPacketEventSemantics;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.data.LastInstance;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import com.google.common.collect.ClassToInstanceMap;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class OrderedLegacyCheckDispatchTest {
    @Test
    public void streamChecksUseTickBoundaryHandlersAndDeadCheckStaysUnregistered() {
        CultPlayer player = offlineJavaPlayer();
        try {
            var records = ac.cult.cultac.CultAPI.INSTANCE
                    .getNetworkManager()
                    .dispatcher()
                    .scanner();
            assertTrue(records.hasReceiveHandlerDeclaration(MultiActionsE.class));
            assertTrue(records.hasReceiveHandlerDeclaration(PacketOrderB.class));
            // PacketOrderO flags any packet between movement and tick end, so it observes the whole stream.
            assertFalse(records.hasReceiveHandlerDeclaration(PacketOrderO.class));
            assertFalse(player.checkManager.getAllChecks().stream().anyMatch(check -> check instanceof PacketOrderP));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void multiActionsDropExemptsOnlyTheNextSwingOfItsTick() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            MultiActionsE check = player.checkManager.getListener(MultiActionsE.class);
            ServerboundPlayerAction drop = new ServerboundPlayerAction(
                    PlayerAction.DROP_ITEM,
                    new ac.cult.cultac.protocol.value.BlockPos(0, 0, 0),
                    ac.cult.cultac.protocol.value.Direction.DOWN,
                    0);
            player.checkManager.dispatchEarlyReceive(receiveEvent(player, drop));
            assertTrue(booleanField(check, "dropping"));

            player.checkManager.dispatchEarlyReceive(RecordReceiveTestEvents.swing(player, ServerboundSwing.PUNCH));
            assertFalse(booleanField(check, "dropping"));

            player.checkManager.dispatchEarlyReceive(receiveEvent(player, drop));
            player.checkManager.dispatchEarlyReceive(RecordReceiveTestEvents.tickEnd(player));
            assertFalse(booleanField(check, "dropping"));

            player.checkManager.dispatchEarlyReceive(receiveEvent(player, drop));
            player.checkManager.dispatchEarlyReceive(
                    receiveEvent(player, new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, false)));
            assertFalse(booleanField(check, "dropping"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void packetOrderBExpectsTheAttackSwingBeforeTheTickBoundary() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        player.setDisabled(true);
        try {
            PacketOrderB check = player.checkManager.getListener(PacketOrderB.class);
            player.checkManager.dispatchEarlyReceive(RecordReceiveTestEvents.attack(player, 7));
            assertTrue(booleanField(check, "sentAttack"));

            player.checkManager.dispatchEarlyReceive(RecordReceiveTestEvents.swing(player, ServerboundSwing.PUNCH));
            assertFalse(booleanField(check, "sentAttack"));

            player.checkManager.dispatchEarlyReceive(RecordReceiveTestEvents.attack(player, 7));
            player.checkManager.dispatchEarlyReceive(RecordReceiveTestEvents.tickEnd(player));
            assertFalse(booleanField(check, "sentAttack"));

            player.checkManager.dispatchEarlyReceive(RecordReceiveTestEvents.swing(player, ServerboundSwing.PUNCH));
            player.checkManager.dispatchEarlyReceive(RecordReceiveTestEvents.attack(player, 7));
            player.checkManager.dispatchEarlyReceive(
                    receiveEvent(player, new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, false)));
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
            ServerboundMovePlayer movement = new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, false);
            player.checkManager.dispatchNonAsyncReceive(receiveEvent(player, movement));
            assertTrue(booleanField(check, "flying"));

            player.checkManager.dispatchNonAsyncReceive(receiveEvent(player, new ServerboundKeepAlive(21L)));
            assertTrue(booleanField(check, "flying"));

            player.checkManager.dispatchNonAsyncReceive(RecordReceiveTestEvents.tickEnd(player));
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
            ServerboundMovePlayer movement = new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, false);
            player.checkManager.dispatchReceiveHandlers(receiveEvent(player, movement));

            ServerboundPlayerAction action = new ServerboundPlayerAction(
                    PlayerAction.DROP_ITEM,
                    new ac.cult.cultac.protocol.value.BlockPos(0, 0, 0),
                    ac.cult.cultac.protocol.value.Direction.DOWN,
                    0);
            player.checkManager.dispatchReceiveHandlers(receiveEvent(player, action));
            assertTrue(objectField(check, "post") != null);

            PacketReceiveEvent unmatchedPong = receiveEvent(player, new ServerboundPong(90));
            player.checkManager.dispatchReceiveHandlers(unmatchedPong);
            assertTrue(objectField(check, "post") != null);
            assertTrue(booleanField(check, "sentFlying"));

            PacketReceiveEvent acceptedPong = receiveEvent(player, new ServerboundPong(91));
            acceptedPong.setAcceptedTransactionResponse(true);
            player.checkManager.dispatchReceiveHandlers(acceptedPong);
            assertTrue(objectField(check, "post") == null);
            assertFalse(booleanField(check, "sentFlying"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void legacyAsyncClassificationMatchesPacketEvents() throws Exception {
        CultPlayer player = offlineJavaPlayer();
        try {
            assertTrue(LegacyPacketEventSemantics.isAsync(receiveEvent(player, new ServerboundKeepAlive(1L))));
            assertFalse(LegacyPacketEventSemantics.isAsync(RecordReceiveTestEvents.pong(player, 1)));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
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
            assertTrue(ac.cult.cultac.CultAPI.INSTANCE
                    .getNetworkManager()
                    .dispatcher()
                    .scanner()
                    .hasReceiveHandlerDeclaration(DumbTimer.class));
            assertTrue(ac.cult.cultac.CultAPI.INSTANCE
                    .getNetworkManager()
                    .dispatcher()
                    .scanner()
                    .hasReceiveHandlerDeclaration(NegativeTimerCheck.class));

            ServerboundMovePlayer movement = new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, false);
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, movement));
            assertFalse(booleanField(tickTimer, "receivedTickEnd"));

            player.checkManager.dispatchPrePredictionReceive(RecordReceiveTestEvents.tickEnd(player));
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
                    Direction.DOWN,
                    ac.cult.cultac.protocol.value.Direction.DOWN.get3DDataValue(),
                    PlayerAction.START_DESTROY_BLOCK,
                    7,
                    ac.cult.blocksim.data.BlockIds.STONE.defaultState());
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
            ServerboundMovePlayer movement = new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, false);

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
                check.onKeepAlive(null, player, new ClientboundKeepAlive(id));
            }

            check.onKeepAlive(receiveEvent(player, new ServerboundKeepAlive(0L)), player, new ServerboundKeepAlive(0L));

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

            ServerboundPlayerCommand startSprint =
                    new ServerboundPlayerCommand(0, PlayerCommandAction.START_SPRINTING, 0);
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

            ServerboundMovePlayer move = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, false);
            player.packetStateData.receivedMovementThisClientTick = true;
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, move));
            long afterMove = longField(timer, "timerBalanceRealTime");
            assertTrue(afterMove == initial);

            player.checkManager.dispatchPrePredictionReceive(RecordReceiveTestEvents.tickEnd(player));
            assertTrue(longField(timer, "timerBalanceRealTime") - afterMove == 50_000_000L);

            player.packetStateData.receivedMovementThisClientTick = false;
            player.checkManager.dispatchPrePredictionReceive(RecordReceiveTestEvents.tickEnd(player));
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
            ServerboundMoveVehicle moveVehicle =
                    new ServerboundMoveVehicle(new Vec3d(0, 0, 0), 0.0F, 0.0F, false, true);
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, moveVehicle));
            player.checkManager.dispatchPrePredictionReceive(receiveEvent(player, moveVehicle));
            assertTrue(longField(vehicleTimer, "timerBalanceRealTime") - initial == 50_000_000L);

            setLongField(vehicleTimer, "timerBalanceRealTime", now - 1_000_000_000L);
            setLongField(vehicleTimer, "lastMovementPlayerClock", now - 2_000_000_000L);
            initial = longField(vehicleTimer, "timerBalanceRealTime");
            vehicleTimer.onClientTickEnd(
                    RecordReceiveTestEvents.tickEnd(player),
                    player,
                    ac.cult.cultac.protocol.packet.ServerboundPackets.CLIENT_TICK_END.opaqueValue());
            player.compensatedEntities.getSelf().mount(new PacketEntity(EntityTypeIds.OAK_BOAT, 99));
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
            assertTrue("TimerA"
                    .equals(player.checkManager.getListener(TimerCheck.class).getConfigName()));
            assertTrue("TimerVehicle"
                    .equals(player.checkManager.getListener(VehicleTimer.class).getConfigName()));
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

            var shifted = new ServerboundPlayerInput(false, false, false, false, false, true, false);
            player.checkManager.dispatchReceiveHandlers(RecordReceiveTestEvents.input(player, shifted));

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
            player.packetStateData.itemInUseHand = Hand.MAIN_HAND;
            player.packetStateData.setSlowedByUsingItem(true);
            assertTrue(invokeBoolean(check, "isActivelyUsingItem"));

            player.packetStateData.lastSlotSelected = 3;
            assertFalse(invokeBoolean(check, "isActivelyUsingItem"));

            player.packetStateData.lastSlotSelected = 2;
            player.packetStateData.itemInUseHand = Hand.OFF_HAND;
            player.packetStateData.setSlowedByUsingItem(true);
            player.packetStateData.lastSlotSelected = 3;
            assertTrue(invokeBoolean(check, "isActivelyUsingItem"));

            player.packetStateData.itemInUseHand = Hand.MAIN_HAND;
            ServerboundMovePlayer movement = new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, false);
            player.actionManager.onMovePlayerPos(receiveEvent(player, movement), player, movement);
            assertFalse(player.packetStateData.isSlowedByUsingItem());

            player.packetStateData.lastSlotSelected = 3;
            player.packetStateData.setSlowedByUsingItem(true);
            ServerboundPlayerAction release = new ServerboundPlayerAction(
                    PlayerAction.RELEASE_USE_ITEM,
                    new ac.cult.cultac.protocol.value.BlockPos(0, 0, 0),
                    ac.cult.cultac.protocol.value.Direction.DOWN,
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
            player.compensatedEntities.updateAttributes(
                    player.entityID,
                    List.of(
                            new AttributeSnapshot("minecraft:block_break_speed", 1.75D, List.of()),
                            new AttributeSnapshot("minecraft:mining_efficiency", 4.0D, List.of()),
                            new AttributeSnapshot("minecraft:submerged_mining_speed", 0.6D, List.of())));

            assertEquals(1.75D, player.compensatedEntities.getSelf().blockBreakSpeed, 0.0D);
            assertEquals(4.0D, player.compensatedEntities.getSelf().miningEfficiency, 0.0D);
            assertEquals(0.6D, player.compensatedEntities.getSelf().submergedMiningSpeed, 1.0E-7D);

        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static PacketReceiveEvent<ServerboundMovePlayer> receiveEvent(
            CultPlayer player, ServerboundMovePlayer packet) {
        return RecordReceiveTestEvents.movement(player, packet);
    }

    private static PacketReceiveEvent<ServerboundMoveVehicle> receiveEvent(
            CultPlayer player, ServerboundMoveVehicle packet) {
        return RecordReceiveTestEvents.vehicle(player, packet);
    }

    private static PacketReceiveEvent<ServerboundPlayerCommand> receiveEvent(
            CultPlayer player, ServerboundPlayerCommand packet) {
        return RecordReceiveTestEvents.playerCommand(player, packet);
    }

    private static PacketReceiveEvent<ServerboundPlayerAction> receiveEvent(
            CultPlayer player, ServerboundPlayerAction packet) {
        return RecordReceiveTestEvents.playerAction(player, packet);
    }

    private static PacketReceiveEvent<ServerboundKeepAlive> receiveEvent(
            CultPlayer player, ServerboundKeepAlive packet) {
        return RecordReceiveTestEvents.keepAlive(player, packet);
    }

    private static PacketReceiveEvent<ac.cult.cultac.protocol.packet.serverbound.ServerboundPong> receiveEvent(
            CultPlayer player, ServerboundPong packet) {
        return RecordReceiveTestEvents.pong(player, packet.id());
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
        User user = ac.cult.cultac.network.TestUsers.create(
                new User.Profile(playerId, ".Ordered_Check_Test"), new EmbeddedChannel());
        return new CultPlayer(user);
    }
}
