package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Before 26.3 LocalPlayer#tick sends a rider's Rot and its vehicle's move from Entity#rideTick, which runs
 * AbstractBoat#positionRider only after them, so a tick's Rot carries the turn positionRider gave the rider the tick
 * before, clamped to the boat's yaw of that tick. From 26.3 Minecraft#tick runs LocalPlayer#sendChanges after
 * ClientLevel#tickEntities, so the Rot carries the tick's own turn. Before 26.3 AbstractBoat#tick only changes
 * deltaRotation on the instance that controls the boat, so a rider that hands the boat over keeps being turned by the
 * last deltaRotation it steered; from 26.3 AbstractBoat#tick zeroes it on any other instance.
 */
class BoatRiderInventoryLookTickOrderTest {
    private static final int BOAT = 100;
    private static final int OTHER_RIDER = 101;
    private static final int OTHER_BOAT = 102;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int RELEASE_TICK = 8;
    private static final int NEVER = Integer.MAX_VALUE;
    // The client opens the inventory in Minecraft#handleKeybinds, before the tick's entities.
    private static final int OPEN_TICK = 3;
    private static final int HAND_OVER_TICK = 6;
    private static final int TICKS = 40;
    private static final float LOOK_YAW = 30;
    private static final float LOOK_PITCH = 10;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void aDriverTheReleasedBoatKeepsTurningInItsInventoryIsNotRotating(ClientVersion version) throws Exception {
        Run run = drive(version, sendsTurnOfItsTick(version), -1, 0, 0);

        assertTrue(run.turnedLooks > 0, "the boat turned no judged look");
        assertEquals(0, run.violations);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void aLookCarryingTheTurnInTheOtherTickOrderIsFlagged(ClientVersion version) throws Exception {
        Run run = drive(version, !sendsTurnOfItsTick(version), -1, 0, 0);

        assertTrue(run.turnedLooks > 0, "the boat turned no judged look");
        assertEquals(run.turnedLooks, run.violations);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2"})
    void aMouseLookOnTopOfTheBoatsTurnInAnInventoryIsStillFlagged(ClientVersion version) throws Exception {
        Run run = drive(version, false, OPEN_TICK + 4, 20, 0);

        assertEquals(1, run.violations);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2"})
    void aPitchChangeWhileTheBoatTurnsInAnInventoryIsStillFlagged(ClientVersion version) throws Exception {
        Run run = drive(version, false, OPEN_TICK + 4, 0, 5);

        assertEquals(1, run.violations);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2"})
    void aRiderTheBoatItHandedOverKeepsTurningInItsInventoryIsNotRotating(ClientVersion version) throws Exception {
        Run run = handOver(version, true);

        assertTrue(run.turnedLooks > 0, "the boat turned no judged look");
        assertEquals(0, run.violations);
    }

    @Test
    void a26_3RiderTheBoatItHandedOverStopsTurningIsNotRotating() throws Exception {
        Run run = handOver(ClientVersion.V_26_3, false);

        assertEquals(0, run.violations);
    }

    @Test
    void a26_3RiderStillTurningByTheBoatItHandedOverIsFlagged() throws Exception {
        Run run = handOver(ClientVersion.V_26_3, true);

        assertTrue(run.turnedLooks > 0, "the rider turned no judged look");
        assertEquals(run.turnedLooks, run.violations);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void aRiderTurningInABoatItNeverDroveIsFlagged(ClientVersion version) throws Exception {
        try (var f = board(version, false)) {
            MultiActionsO check = enable(f);
            float yaw = clampRotation(LOOK_YAW, 0);
            int turnedLooks = 0;
            for (int tick = 0; tick < TICKS; tick++) {
                if (tick == OPEN_TICK) f.player.openWindow.onClientOpenOrCloseWindow(true);
                float previousYaw = yaw;
                yaw = clampRotation(yaw - 2.5F, 0);
                if (tick > OPEN_TICK && yaw != previousYaw) turnedLooks++;
                rot(f, yaw, LOOK_PITCH);
                f.tickEnd();
            }
            assertTrue(turnedLooks > 0, "the rider turned no judged look");
            assertEquals(turnedLooks, check.violations);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2"})
    void aRiderBackInABoatItLeftTurningKeepsItsTurnInItsInventoryIsNotRotating(ClientVersion version)
            throws Exception {
        Run run = backAboard(version, false, true);

        assertTrue(run.turnedLooks > 0, "the boat turned no judged look");
        assertEquals(0, run.violations);
    }

    @Test
    void a26_3RiderBackInABoatItLeftTurningStillTurningIsFlagged() throws Exception {
        Run run = backAboard(ClientVersion.V_26_3, false, true);

        assertTrue(run.turnedLooks > 0, "the rider turned no judged look");
        assertEquals(run.turnedLooks, run.violations);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2"})
    void aDriverBackInABoatItLeftTurningKeepsItsTurnInItsInventoryIsNotRotating(ClientVersion version)
            throws Exception {
        Run run = backAboard(version, true, true);

        assertTrue(run.turnedLooks > 0, "the boat turned no judged look");
        assertEquals(0, run.violations);
    }

    @Test
    void a26_3DriverBackInABoatItLeftTurningStillTurningIsFlagged() throws Exception {
        Run run = backAboard(ClientVersion.V_26_3, true, true);

        assertTrue(run.turnedLooks > 0, "the rider turned no judged look");
        assertEquals(run.turnedLooks, run.violations);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void aRiderTurningByTheTurnOfABoatBeforeItRespawnedIsFlagged(ClientVersion version) throws Exception {
        float[] turns = vanillaDeltaRotations(NEVER);
        try (var f = board(version, true)) {
            MultiActionsO check = enable(f);
            float boatYaw = driveUntilHandOver(f, version, turns);
            seat(f, BOAT, List.of(OTHER_RIDER, f.player.entityID));
            f.player.compensatedEntities.removeEntity(BOAT);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, boatYaw, 0, 0);
            seat(f, BOAT, List.of(OTHER_RIDER, f.player.entityID));
            openInventory(f, boatYaw);
            check.violations = 0;

            Run run = ride(f, check, version, boatYaw, turns[HAND_OVER_TICK - 1], false);

            assertTrue(run.turnedLooks > 0, "the rider turned no judged look");
            assertEquals(run.turnedLooks, run.violations);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void aDriverBackInABoatItLeftAWhileTurningItFromItsHeldTurnIsNotRotating(ClientVersion version)
            throws Exception {
        DriveAgain run = driveAgain(version, heldTurn(version), false);

        assertTrue(run.turnedLooks > 0, "the boat turned no judged look");
        assertEquals(0, run.violations);
    }

    @Test
    void a26_3DriverBackInABoatItLeftAWhileTurningItFromItsOldTurnIsFlagged() throws Exception {
        DriveAgain run = driveAgain(ClientVersion.V_26_3, vanillaDeltaRotations(NEVER)[HAND_OVER_TICK - 1], false);

        assertTrue(run.unvanillaTurns > 0, "every judged look turned as vanilla turns it");
        assertEquals(run.unvanillaTurns, run.violations);
    }

    @Test
    void a26_3UseItemsInABoatDrivenAgainAfterAWhileMatchTheTickRotation() throws Exception {
        DriveAgain run = driveAgain(ClientVersion.V_26_3, 0, true);

        assertTrue(run.turnedLooks > 0, "no use_item came in a tick the boat turned its rider");
        assertEquals(0, run.violations);
    }

    @Test
    void a26_3UseItemsInABoatDrivenAgainTurningFromItsOldTurnAreFlagged() throws Exception {
        DriveAgain run = driveAgain(ClientVersion.V_26_3, vanillaDeltaRotations(NEVER)[HAND_OVER_TICK - 1], true);

        assertTrue(run.unvanillaTurns > 0, "every use_item tick turned as vanilla turns it");
        assertEquals(run.unvanillaTurns, run.violations);
    }

    private static boolean sendsTurnOfItsTick(ClientVersion version) {
        return version.isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    private record Run(double violations, int turnedLooks) {}

    private record DriveAgain(double violations, int turnedLooks, int unvanillaTurns) {}

    /**
     * Steers left until {@link #RELEASE_TICK} and opens the inventory at {@link #OPEN_TICK}. Each tick the boat turns
     * by deltaRotation, positionRider turns the rider by it and clamps it to the boat, and the Rot carries the yaw before
     * that turn, or after it when {@code turnOfItsTick}. At tick {@code at} a mouse look turns the yaw by
     * {@code extraYaw} and the pitch by {@code extraPitch} before the tick's entities.
     */
    private static Run drive(ClientVersion version, boolean turnOfItsTick, int at, float extraYaw, float extraPitch)
            throws Exception {
        float[] turns = vanillaDeltaRotations(RELEASE_TICK);
        try (var f = board(version, true)) {
            MultiActionsO check = enable(f);
            float boatYaw = 0;
            // Entity#turn, then AbstractBoat#onPassengerTurned, before the first tick.
            float yaw = clampRotation(LOOK_YAW, boatYaw);
            float pitch = LOOK_PITCH;
            float lastSent = yaw;
            int turnedLooks = 0;
            for (int tick = 0; tick < TICKS; tick++) {
                if (tick == OPEN_TICK) f.player.openWindow.onClientOpenOrCloseWindow(true);
                steer(f, tick, RELEASE_TICK);
                if (tick == at) {
                    yaw = clampRotation(yaw + extraYaw, boatYaw);
                    pitch += extraPitch;
                }
                boatYaw += turns[tick];
                float turned = clampRotation(yaw + turns[tick], boatYaw);
                float sent = turnOfItsTick ? turned : yaw;
                yaw = turned;
                if (tick > OPEN_TICK && sent != lastSent && tick != at) turnedLooks++;
                lastSent = sent;
                rot(f, sent, pitch);
                moveBoat(f, boatYaw);
                f.tickEnd();
            }
            return new Run(check.violations, turnedLooks);
        }
    }

    /**
     * Steers left throughout, opens the inventory at {@link #OPEN_TICK}, and at {@link #HAND_OVER_TICK} the server seats
     * another rider in front of the player. The boat no longer moves, and positionRider turns the rider by the last
     * deltaRotation it steered when {@code leftoverTurn}, by none otherwise. The Rot carries the yaw before the tick's
     * positionRider before 26.3 and after it from 26.3.
     */
    private static Run handOver(ClientVersion version, boolean leftoverTurn) throws Exception {
        float[] turns = vanillaDeltaRotations(NEVER);
        try (var f = board(version, true)) {
            MultiActionsO check = enable(f);
            boolean turnOfItsTick = sendsTurnOfItsTick(version);
            float boatYaw = 0;
            float yaw = clampRotation(LOOK_YAW, boatYaw);
            float lastSent = yaw;
            float turn = 0;
            int turnedLooks = 0;
            for (int tick = 0; tick < TICKS; tick++) {
                if (tick == OPEN_TICK) f.player.openWindow.onClientOpenOrCloseWindow(true);
                if (tick == HAND_OVER_TICK) {
                    f.setPassengers(BOAT, List.of(OTHER_RIDER, f.player.entityID));
                    f.pongThrough(f.player.lastTransactionSent.get());
                }
                steer(f, tick, NEVER);
                boolean drives = tick < HAND_OVER_TICK;
                if (drives) {
                    turn = turns[tick];
                    boatYaw += turn;
                } else if (!leftoverTurn) {
                    turn = 0;
                }
                float turned = clampRotation(yaw + turn, boatYaw);
                float sent = turnOfItsTick ? turned : yaw;
                yaw = turned;
                if (tick >= HAND_OVER_TICK && sent != lastSent) turnedLooks++;
                lastSent = sent;
                rot(f, sent, LOOK_PITCH);
                if (drives) moveBoat(f, boatYaw);
                f.tickEnd();
            }
            return new Run(check.violations, turnedLooks);
        }
    }

    /**
     * Drives the boat steering left until {@link #HAND_OVER_TICK} with the inventory closed, leaves it and releases the
     * keys, drives another boat that does not turn, leaves that too and opens the inventory. Then the server seats the
     * player in the first boat again, as its driver when {@code drives} and behind another rider otherwise. Before 26.3
     * the first boat still holds the deltaRotation of its last tick under the player's control; from 26.3 AbstractBoat
     * #tick zeroed it. The boat and the rider turn by that deltaRotation when {@code leftoverTurn}.
     */
    private static Run backAboard(ClientVersion version, boolean drives, boolean leftoverTurn) throws Exception {
        float[] turns = vanillaDeltaRotations(NEVER);
        try (var f = board(version, true)) {
            f.player.compensatedEntities.addEntity(OTHER_BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
            MultiActionsO check = enable(f);
            float boatYaw = driveUntilHandOver(f, version, turns);
            seat(f, BOAT, List.of());
            f.input(RELEASED);
            seat(f, OTHER_BOAT, List.of(f.player.entityID));
            for (int tick = 0; tick < 3; tick++) {
                rot(f, 0, LOOK_PITCH);
                moveBoat(f, 0);
                f.tickEnd();
            }
            seat(f, OTHER_BOAT, List.of());
            openInventory(f, 0);
            check.violations = 0;
            seat(f, BOAT, drives ? List.of(f.player.entityID) : List.of(OTHER_RIDER, f.player.entityID));
            return ride(f, check, version, boatYaw, leftoverTurn ? turns[HAND_OVER_TICK - 1] : 0, drives);
        }
    }

    /**
     * The deltaRotation a boat holds once the player drives it again after leaving it for a tick: before 26.3 the one
     * its last tick under the player's control left, from 26.3 none, as AbstractBoat#tick zeroes it on a tick the
     * client's instance does not control the boat.
     */
    private static float heldTurn(ClientVersion version) {
        return sendsTurnOfItsTick(version) ? 0 : vanillaDeltaRotations(NEVER)[HAND_OVER_TICK - 1];
    }

    /**
     * Drives the boat steering left until {@link #HAND_OVER_TICK} with the inventory closed, leaves it for three ticks
     * with the keys released and drives it again, its deltaRotation starting from {@code startTurn}. Back aboard the
     * keys steer left from the first tick until {@link #OPEN_TICK}, when they are released and the inventory opens, or,
     * when {@code useItems}, a use_item comes from Minecraft#handleKeybinds every tick after instead. Counts the turned
     * looks MultiActionsO judges, or the use_items of a tick the boat turned the rider, those of them whose turn differs
     * from the one a client of {@code version} gives the boat and the rider from the same yaws, and the check's
     * violations.
     */
    private static DriveAgain driveAgain(ClientVersion version, float startTurn, boolean useItems) throws Exception {
        float[] turns = vanillaDeltaRotations(NEVER);
        try (var f = board(version, true)) {
            MultiActionsO look = enable(f);
            BadPacketsJ use = f.player.checkManager.getListener(BadPacketsJ.class);
            float boatYaw = driveUntilHandOver(f, version, turns);
            seat(f, BOAT, List.of());
            f.input(RELEASED);
            for (int tick = 0; tick < 3; tick++) {
                rot(f, boatYaw, LOOK_PITCH);
                f.tickEnd();
            }
            seat(f, BOAT, List.of(f.player.entityID));
            look.violations = 0;
            double useViolations = use.violations;
            boolean turnOfItsTick = sendsTurnOfItsTick(version);
            float yaw = boatYaw;
            float lastSent = yaw;
            float turn = startTurn;
            float vanillaTurn = heldTurn(version);
            int sequence = 0;
            int judged = 0;
            int unvanilla = 0;
            for (int tick = 0; tick < TICKS; tick++) {
                if (tick == 0) f.input(LEFT);
                if (tick == OPEN_TICK) {
                    f.input(RELEASED);
                    if (!useItems) f.player.openWindow.onClientOpenOrCloseWindow(true);
                }
                boolean judging = tick > OPEN_TICK;
                if (useItems && judging) {
                    var packet = new ServerboundUseItem(Hand.MAIN_HAND, ++sequence, yaw, LOOK_PITCH);
                    f.listener.onUseItem(f.event(ServerboundPackets.USE_ITEM, packet), f.player, packet);
                }
                turn *= landFriction();
                vanillaTurn *= landFriction();
                if (tick >= 1 && tick <= OPEN_TICK) {
                    turn--;
                    vanillaTurn--;
                }
                boolean vanillaStep = boatYaw + turn == boatYaw + vanillaTurn
                        && clampRotation(yaw + turn, boatYaw + turn)
                                == clampRotation(yaw + vanillaTurn, boatYaw + vanillaTurn);
                boatYaw += turn;
                float turned = clampRotation(yaw + turn, boatYaw);
                float sent = turnOfItsTick ? turned : yaw;
                if (judging && (useItems ? turned != yaw : sent != lastSent)) {
                    judged++;
                    if (!vanillaStep) unvanilla++;
                }
                lastSent = sent;
                yaw = turned;
                rot(f, sent, LOOK_PITCH);
                moveBoat(f, boatYaw);
                f.tickEnd();
            }
            return new DriveAgain(useItems ? use.violations - useViolations : look.violations, judged, unvanilla);
        }
    }

    /** Drives the boat steering left from tick 0 until {@link #HAND_OVER_TICK}, returns the boat's yaw. */
    private static float driveUntilHandOver(JavaTickFixture f, ClientVersion version, float[] turns) {
        float boatYaw = 0;
        float yaw = clampRotation(LOOK_YAW, boatYaw);
        for (int tick = 0; tick < HAND_OVER_TICK; tick++) {
            steer(f, tick, NEVER);
            boatYaw += turns[tick];
            float turned = clampRotation(yaw + turns[tick], boatYaw);
            rot(f, sendsTurnOfItsTick(version) ? turned : yaw, LOOK_PITCH);
            yaw = turned;
            moveBoat(f, boatYaw);
            f.tickEnd();
        }
        return boatYaw;
    }

    /**
     * Rides the boat at {@code boatYaw} just boarded, with the keys released and the inventory open. The rider starts at
     * the boat's yaw, which ClientPacketListener#handleSetEntityPassengersPacket gives a player newly aboard, and
     * positionRider turns it by {@code turn} each tick. The driver's boat turns by it as well and AbstractBoat#floatBoat
     * multiplies it by the land friction each tick first.
     */
    private static Run ride(
            JavaTickFixture f, MultiActionsO check, ClientVersion version, float boatYaw, float turn, boolean drives) {
        boolean turnOfItsTick = sendsTurnOfItsTick(version);
        float yaw = boatYaw;
        float lastSent = yaw;
        int turnedLooks = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            if (drives) {
                turn *= landFriction();
                boatYaw += turn;
            }
            float turned = clampRotation(yaw + turn, boatYaw);
            float sent = turnOfItsTick ? turned : yaw;
            yaw = turned;
            if (sent != lastSent) turnedLooks++;
            lastSent = sent;
            rot(f, sent, LOOK_PITCH);
            if (drives) moveBoat(f, boatYaw);
            f.tickEnd();
        }
        return new Run(check.violations, turnedLooks);
    }

    private static void openInventory(JavaTickFixture f, float yaw) {
        f.player.openWindow.onClientOpenOrCloseWindow(true);
        for (int tick = 0; tick < 2; tick++) {
            rot(f, yaw, LOOK_PITCH);
            f.tickEnd();
        }
    }

    private static void seat(JavaTickFixture f, int vehicle, List<Integer> passengers) {
        f.setPassengers(vehicle, passengers);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /**
     * The boat's deltaRotation each tick while the keys steer left from tick 0 until {@code releaseTick}: AbstractBoat
     * #floatBoat multiplies it by the land friction, AbstractBoat#getGroundFriction's mean over the nine stone blocks
     * under the boat, and controlBoat takes 1 from it while the keys LocalPlayer#rideTick handed the boat the tick before
     * steer left.
     */
    private static float[] vanillaDeltaRotations(int releaseTick) {
        float friction = landFriction();
        float[] turns = new float[TICKS];
        float turn = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            turn *= friction;
            if (tick >= 1 && tick <= releaseTick) turn--;
            turns[tick] = turn;
        }
        return turns;
    }

    // AbstractBoat#getGroundFriction: the mean friction of the nine stone blocks under the boat.
    private static float landFriction() {
        float friction = 0;
        for (int block = 0; block < 9; block++) friction += 0.6F;
        return friction / 9;
    }

    private static MultiActionsO enable(JavaTickFixture f) {
        MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
        f.player.setExperimentalChecks(true);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }

    /** A client of {@code version} on a boat at yaw 0 on stone, its first passenger when it {@code controls} the boat. */
    private static JavaTickFixture board(ClientVersion version, boolean controls) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        f.player.compensatedEntities.addEntity(OTHER_RIDER, EntityTypeIds.ZOMBIE, SEAT, 0, 0, 0);
        f.setPassengers(BOAT, controls ? List.of(f.player.entityID) : List.of(OTHER_RIDER, f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        return f;
    }

    // The client sends the keys that changed before the Rot.
    private static void steer(JavaTickFixture f, int tick, int releaseTick) {
        if (tick == 0) f.input(LEFT);
        if (tick == releaseTick) f.input(RELEASED);
    }

    private static void rot(JavaTickFixture f, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, f.player.onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    private static void moveBoat(JavaTickFixture f, float boatYaw) {
        var packet = new ServerboundMoveVehicle(new Vec3d(SEAT.x, SEAT.y, SEAT.z), boatYaw, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, packet), f.player, packet);
    }

    // AbstractBoat#clampRotation's yaw for a player passenger, in Mth's float operations.
    private static float clampRotation(float yaw, float boatYaw) {
        float delta = (yaw - boatYaw) % 360.0F;
        if (delta >= 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        float target = delta < -105.0F ? -105.0F : Math.min(delta, 105.0F);
        return yaw + target - delta;
    }
}
