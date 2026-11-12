package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InputKeys;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import org.junit.jupiter.api.Test;

/**
 * Captured 1.21.11 and 26.3 clients that keep LocalPlayer#modifyInput's use multiplier after the server's completion (entity event
 * 9) or their own release_use_item ended the use: the reported keys still move them at the use's speed. Moves that the
 * ended use's multiplier does not produce still flag.
 */
class ItemUseEndedInputKeysTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SPRINT_KEY =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SPRINT_KEY_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double FRICTION = 0.6F * 0.91F;
    private static final double WALK = (double) 0.98F * (double) 0.1F;
    private static final double SLOWED = (double) (0.98F * 0.2F) * (double) 0.1F;
    private static final double SPRINT_SLOWED = (double) (0.98F * 0.2F) * (double) 0.13F;

    // Recorded moves: x, z and yaw of each move; the floor is y 63.
    private static final double[][] COMPLETED_RUN = {
        {1130.9702381054901, 2090.7433115037966, -842.0566},
        {1130.988252738596, 2090.7355892593796, -833.2066},
        {1131.0164289541053, 2090.724459433495, -830.65656},
        {1131.050171228773, 2090.7115165660834, -830.50653},
        {1131.086952572094, 2090.6975837778823, -830.50653},
        {1131.1253932470927, 2090.683110492868, -830.50653},
        {1131.1647399172923, 2090.6683420965587, -830.50653},
        {1131.2045812609292, 2090.6534125694643, -830.50653},
        {1131.2446926962944, 2090.6383950649506, -830.50653},
        {1131.2849516017602, 2090.6233295247607, -830.50653},
        {1131.3252910259102, 2090.6082377570883, -830.50653},
        {1131.365674413267, 2090.593131669209, -830.50653},
        {1131.4058375325758, 2090.5773970391365, -832.4568},
        {1131.4457402608048, 2090.5609888009303, -833.50696},
    };
    private static final double COMPLETED_SHIFT_X = -1131;
    private static final double COMPLETED_SHIFT_Z = -2090;
    private static final int COMPLETED_EVENT_ROW = 5;

    // Recorded moves: x, z and yaw of each move; the floor is y 62.
    private static final double[][] RELEASED_RUN = {
        {-3125.496073870782, -165.99332023396215, -312.96127},
        {-3125.5356269094877, -166.0436348868236, -313.22754},
        {-3125.55722287113, -166.07110669047688, -313.71558},
        {-3125.5690142675558, -166.08610629701383, -313.84872},
        {-3125.589583967303, -166.08071455061065, -313.86353},
        {-3125.6148983537573, -166.06413907983602, -314.07074},
    };
    private static final double RELEASED_SHIFT_X = 3125.5;
    private static final double RELEASED_SHIFT_Z = 166;

    // Recorded moves: x, z and yaw of each move; the floor is y 65.
    private static final double[][] LAST_STACK_RUN = {
        {692.9987133815978, -725.0336726201457, -648.7849},
        {692.9710650398063, -724.9808836954211, -648.7849},
        {692.9437229335015, -724.927194236997, -648.7849},
        {692.9165480317919, -724.8730130871161, -648.7849},
        {692.8894644238019, -724.8185634736684, -648.7849},
        {692.8624306621886, -724.7639672790963, -648.7849},
        {692.8354241167001, -724.7092910512209, -648.7849},
        {692.8084324312175, -724.6545711251568, -648.7849},
        {692.7814488592991, -724.5998273398791, -648.7849},
        {692.7544697173871, -724.5450705274691, -648.7849},
        {692.727492994259, -724.490306602244, -648.7849},
        {692.7005175917872, -724.4355387934215, -648.7849},
        {692.6735429103936, -724.3807688641546, -648.7849},
        {692.6402583265872, -724.3445541831796, -648.7849},
        {692.6035285356631, -724.318470668949, -648.7849},
    };
    private static final double LAST_STACK_SHIFT_X = -692;
    private static final double LAST_STACK_SHIFT_Z = 724;
    private static final int LAST_STACK_FIRST_SEQ = 8646;
    private static final ServerboundPlayerInput SPRINT_KEY_FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, false, true);

    // Recorded moves: x, y, z, yaw and ground of each move; the floor is y 65.
    private static final double[][] KNOCKED_RUN = {
        {16015.9100968332, 65.09554087887209, 2051.401745574171, -874.4705200195312, 0},
        {16015.871968409816, 65.0, 2051.428602039935, -872.4155883789062, 1},
        {16015.827285533958, 65.41999998688698, 2051.469906763688, -869.3744506835938, 0},
        {16015.800809938411, 65.7531999805212, 2051.4957825821475, -867.9771728515625, 0},
        {16015.77462408449, 66.00133597911214, 2051.522644015024, -867.7305297851562, 0},
        {16015.576229979444, 66.24945903349779, 2051.874490467317, -867.7305297851562, 0},
        {16015.393593513007, 66.4142196300024, 2052.1979821719106, -867.6483154296875, 0},
        {16015.224866193163, 66.49728501619362, 2052.4953554546632, -859.83984375, 0},
        {16015.068657197871, 66.50028909471946, 2052.7688379219057, -857.1275024414062, 0},
        {16014.923856447787, 66.42483309020622, 2053.0205950411505, -857.4562377929688, 0},
        {16014.789433050348, 66.27248620281816, 2053.25257827834, -857.3740844726562, 0},
        {16014.66449451583, 66.04478624874619, 2053.4666049098387, -858.196044921875, 0},
        {16014.551236354962, 65.74324028788675, 2053.6668957026923, -859.5111694335938, 0},
        {16014.451156313628, 65.36932523896708, 2053.851701361502, -859.5933837890625, 0},
        {16014.36307565761, 65.0, 2054.0224069521673, -859.7578125, 1},
        {16014.310468126549, 65.0, 2054.174659505948, -861.4016723632812, 1},
    };
    private static final double KNOCKED_SHIFT_X = -16016;
    private static final double KNOCKED_SHIFT_Y = -1;
    private static final double KNOCKED_SHIFT_Z = -2050;
    private static final int KNOCKED_FIRST_SEQ = 946;
    private static final ServerboundPlayerInput SPRINT_KEY_BACK_JUMP =
            new ServerboundPlayerInput(false, true, false, false, true, false, true);
    private static final ServerboundPlayerInput SPRINT_KEY_BACK =
            new ServerboundPlayerInput(false, true, false, false, false, false, true);
    private static final ServerboundPlayerInput SPRINT_KEY_BACK_RIGHT =
            new ServerboundPlayerInput(false, true, false, true, false, false, true);
    private static final ServerboundPlayerInput SPRINT_KEY_RIGHT =
            new ServerboundPlayerInput(false, false, false, true, false, false, true);
    private static final ServerboundPlayerInput SPRINT_KEY_FORWARD_RIGHT =
            new ServerboundPlayerInput(true, false, false, true, false, false, true);

    private int sequence = 200;

    /**
     * The client sends use_item again before it handles entity event 9 for its last stack, the server never
     * confirms that use, and the client keeps moving its forward keys at the use's speed after the completion.
     */
    @Test
    void walkingAtTheUseSpeedAfterTheCompletionIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            startCompletedRun(f);
            for (int row = COMPLETED_EVENT_ROW; row < COMPLETED_RUN.length; row++) {
                if (row == COMPLETED_EVENT_ROW + 1) usingFlags(f, (byte) 0);
                PredictionResult result = replay(f, COMPLETED_RUN[row], COMPLETED_SHIFT_X, COMPLETED_SHIFT_Z);
                assertTrue(exact(result), "seq " + (12918 + row) + " " + flags(result));
            }
            assertNoFlags(f);
        }
    }

    /**
     * The client restarts the use, releases it in the next tick, and moves its forward keys at the use's
     * speed on that tick and the next, when it handles entity event 9 and the server's stop.
     */
    @Test
    void walkingAtTheUseSpeedAfterTheClientsReleaseIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.food = 10;
            f.player.getInventory().inventory.getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack("minecraft:baked_potato", 26));
            use(f, Hand.MAIN_HAND);
            usingFlags(f, (byte) 1);
            double[] from = RELEASED_RUN[0];
            double[] to = RELEASED_RUN[1];
            f.player.xRot = (float) to[2];
            f.place(
                    captured(to, RELEASED_SHIFT_X, RELEASED_SHIFT_Z),
                    new Vec3((to[0] - from[0]) * FRICTION, -0.0784000015258789, (to[1] - from[1]) * FRICTION));
            f.input(SPRINT_KEY);
            assertExactRow(f, RELEASED_RUN[2], RELEASED_SHIFT_X, RELEASED_SHIFT_Z, 12824);
            use(f, Hand.MAIN_HAND);
            assertExactRow(f, RELEASED_RUN[3], RELEASED_SHIFT_X, RELEASED_SHIFT_Z, 12825);
            release(f);
            f.input(SPRINT_KEY_FORWARD);
            assertExactRow(f, RELEASED_RUN[4], RELEASED_SHIFT_X, RELEASED_SHIFT_Z, 12826);
            complete(f);
            usingFlags(f, (byte) 0);
            assertExactRow(f, RELEASED_RUN[5], RELEASED_SHIFT_X, RELEASED_SHIFT_Z, 12827);
            assertNoFlags(f);
        }
    }

    /**
     * A 1.21.11 client eats its last apple, sends no use_item for that hand again, and keeps moving its
     * forward-left keys at the use's speed after entity event 9 and the server's stop.
     */
    @Test
    void walkingAtTheUseSpeedAfterTheLastStackIsCompletedIsNotFlagged() throws Exception {
        try (var f = JavaTickFixture.olderHost(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            f.player.food = 10;
            f.player.getInventory().inventory.getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack("minecraft:apple", 1));
            use(f, Hand.MAIN_HAND);
            usingFlags(f, (byte) 1);
            double[] from = LAST_STACK_RUN[0];
            double[] to = LAST_STACK_RUN[1];
            f.player.xRot = (float) to[2];
            f.place(
                    captured(to, LAST_STACK_SHIFT_X, LAST_STACK_SHIFT_Z),
                    new Vec3((to[0] - from[0]) * FRICTION, -0.0784000015258789, (to[1] - from[1]) * FRICTION));
            f.input(SPRINT_KEY_FORWARD_LEFT);
            for (int row = 2; row < LAST_STACK_RUN.length; row++) {
                int seq = LAST_STACK_FIRST_SEQ + row;
                if (seq == 8651) complete(f);
                if (seq == 8652) usingFlags(f, (byte) 0);
                if (seq == 8655) use(f, Hand.OFF_HAND);
                if (seq == 8659) f.input(SPRINT_KEY_FORWARD);
                assertExactRow(f, LAST_STACK_RUN[row], LAST_STACK_SHIFT_X, LAST_STACK_SHIFT_Z, seq);
            }
            assertNoFlags(f);
        }
    }

    /**
     * A 1.21.11 client eating a golden apple from hotbar slot 4 lands, jumps, takes knockback in the tick it
     * handles entity event 9, changes slots twice, lands again and moves its forward-right keys at the use's speed.
     */
    @Test
    void walkingAtTheUseSpeedAfterACompletionInTheAirIsNotFlagged() throws Exception {
        try (var f = JavaTickFixture.olderHost(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            f.player.food = 20;
            f.player.getInventory().inventory.getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET + 4, ItemUtil.modelItems().stack("minecraft:golden_apple", 6));
            selectSlot(f, 4);
            use(f, Hand.MAIN_HAND);
            usingFlags(f, (byte) 1);
            double[] from = KNOCKED_RUN[0];
            double[] to = KNOCKED_RUN[1];
            f.player.xRot = (float) to[3];
            f.place(
                    knocked(to),
                    new Vec3((to[0] - from[0]) * 0.91F, -0.0784000015258789, (to[2] - from[2]) * 0.91F));
            f.input(SPRINT_KEY_BACK_JUMP);
            for (int row = 2; row < KNOCKED_RUN.length; row++) {
                int seq = KNOCKED_FIRST_SEQ + row;
                if (seq == 950) f.input(SPRINT_KEY_BACK);
                if (seq == 951) {
                    f.pongThrough(f.velocity(new Vec3(-0.19630104376487822, 0.24812305438564364, 0.3485320148934872)));
                    complete(f);
                }
                if (seq == 952) usingFlags(f, (byte) 0);
                if (seq == 954) selectSlot(f, 1);
                if (seq == 958) {
                    selectSlot(f, 0);
                    f.input(SPRINT_KEY_BACK_RIGHT);
                }
                if (seq == 959) f.input(SPRINT_KEY_RIGHT);
                if (seq == 961) f.input(SPRINT_KEY_FORWARD_RIGHT);
                double[] move = KNOCKED_RUN[row];
                f.player.xRot = (float) move[3];
                PredictionResult result = f.tick(knocked(move), move[4] != 0);
                assertTrue(exact(result), "seq " + seq + " " + flags(result));
            }
            assertNoFlags(f);
        }
    }

    /** The use's speed stays a reality for a bounded run of client ticks after the completion, not for good. */
    @Test
    void walkingAtTheUseSpeedLongAfterTheCompletionIsFlagged() throws Exception {
        try (var f = completedStraightWalk()) {
            double velocity = steadySlowedVelocity();
            double z = 0.0;
            PredictionResult result = null;
            for (int tick = 0; tick < 12; tick++) {
                z += velocity + SLOWED;
                velocity = (velocity + SLOWED) * FRICTION;
                result = f.tick(new Vec3(0.5, FLOOR_Y, z - 6.0), true);
                if (tick < 11) assertTrue(exact(result), "tick " + tick + " " + flags(result));
            }
            assertTrue(result.hasFlag(InputKeys.class), flags(result));
        }
    }

    /** Faster than the use allows and slower than walking: no item-use reality moves the keys so. */
    @Test
    void walkingBetweenTheUseSpeedAndTheWalkingSpeedAfterTheCompletionIsFlagged() throws Exception {
        try (var f = completedStraightWalk()) {
            double velocity = steadySlowedVelocity();
            double half = WALK * 0.5;
            assertTrue(f.tick(new Vec3(0.5, FLOOR_Y, velocity + half - 6.0), true).hasFlag(InputKeys.class));
        }
    }

    /**
     * A move the ended use's speed leaves unexplained keeps the offset of the walking input, so
     * the window never ranks a worse candidate above the one the client ran.
     */
    @Test
    void aMoveTheUseSpeedDoesNotExplainKeepsItsWalkingOffsetAfterTheCompletion() throws Exception {
        try (var f = completedStraightWalk()) {
            double velocity = steadySlowedVelocity();
            PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, velocity + WALK * 0.5 - 6.0), true);
            PredictionResult.Flag keys = result.getFlag(InputKeys.class);
            assertTrue(keys != null, flags(result));
            assertEquals(WALK * 0.5, keys.getSeverity(), 1.0E-9, flags(result));
        }
    }

    /** While the client holds a use, walking at the unslowed speed is no reality. */
    @Test
    void walkingAtTheWalkingSpeedWhileTheUseIsHeldIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 2);
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            use(f, Hand.OFF_HAND);
            usingFlags(f, (byte) 3);
            f.player.xRot = 0;
            double velocity = steadySlowedVelocity();
            f.place(new Vec3(0.5, FLOOR_Y, -6.0), new Vec3(0, -0.0784000015258789, velocity));
            f.input(FORWARD);
            PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, velocity + WALK - 6.0), true);
            assertTrue(result.hasFlag(InputKeys.class), flags(result));
        }
    }

    /** Forward keys moving the player sideways at the use's speed. */
    @Test
    void movingSidewaysAtTheUseSpeedWithForwardKeysAfterTheCompletionIsFlagged() throws Exception {
        try (var f = completedStraightWalk()) {
            double velocity = steadySlowedVelocity();
            assertTrue(f.tick(new Vec3(0.5 + SLOWED, FLOOR_Y, velocity - 6.0), true).hasFlag(InputKeys.class));
        }
    }

    /** A sprinting player moving its keys at the sprinting speed slowed by the use: the use forbade the sprint. */
    @Test
    void sprintingAtTheUseSpeedAfterTheCompletionIsFlagged() throws Exception {
        try (var f = completedStraightWalk()) {
            f.command(PlayerCommandAction.START_SPRINTING);
            f.input(SPRINT_KEY_FORWARD);
            double velocity = steadySlowedVelocity();
            assertTrue(f.tick(new Vec3(0.5, FLOOR_Y, velocity + SPRINT_SLOWED - 6.0), true)
                    .hasFlag(InputKeys.class));
        }
    }

    /** A player that held no use moves its keys at the walking speed only. */
    @Test
    void walkingAtTheUseSpeedWithoutAnyUseIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, -6.0), new Vec3(0, -0.0784000015258789, 0));
            f.input(FORWARD);
            assertTrue(f.tick(new Vec3(0.5, FLOOR_Y, SLOWED - 6.0), true).hasFlag(InputKeys.class));
        }
    }

    /** Moving with no keys held after the completion. */
    @Test
    void movingWithoutKeysAfterTheCompletionIsFlagged() throws Exception {
        try (var f = completedStraightWalk()) {
            f.input(NONE);
            double velocity = steadySlowedVelocity();
            assertTrue(f.tick(new Vec3(0.5, FLOOR_Y, velocity + SLOWED - 6.0), true).hasFlag(InputKeys.class));
        }
    }

    private void startCompletedRun(JavaTickFixture f) {
        f.player.food = 10;
        f.player.getInventory().inventory.getInventoryStorage()
                .setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack("minecraft:baked_potato", 1));
        use(f, Hand.MAIN_HAND);
        usingFlags(f, (byte) 1);
        double[] still = COMPLETED_RUN[0];
        f.player.xRot = (float) still[2];
        f.place(captured(still, COMPLETED_SHIFT_X, COMPLETED_SHIFT_Z), new Vec3(0, -0.0784000015258789, 0));
        f.input(FORWARD);
        for (int row = 1; row < COMPLETED_EVENT_ROW; row++) {
            if (row == COMPLETED_EVENT_ROW - 1) use(f, Hand.MAIN_HAND);
            assertExactRow(f, COMPLETED_RUN[row], COMPLETED_SHIFT_X, COMPLETED_SHIFT_Z, 12918 + row);
        }
        complete(f);
    }

    /** A player walking +z at the use's speed through a completed offhand use, standing at z -6. */
    private JavaTickFixture completedStraightWalk() throws Exception {
        var f = new JavaTickFixture();
        var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 2);
        f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
        use(f, Hand.OFF_HAND);
        usingFlags(f, (byte) 3);
        f.player.xRot = 0;
        f.place(new Vec3(0.5, FLOOR_Y, -6.0), new Vec3(0, -0.0784000015258789, 0));
        f.input(FORWARD);
        double z = -6.0;
        double velocity = 0.0;
        for (int tick = 0; tick < 6; tick++) {
            z += velocity + SLOWED;
            velocity = (velocity + SLOWED) * FRICTION;
            assertTrue(exact(f.tick(new Vec3(0.5, FLOOR_Y, z), true)));
        }
        complete(f);
        usingFlags(f, (byte) 2);
        f.place(new Vec3(0.5, FLOOR_Y, -6.0), new Vec3(0, -0.0784000015258789, steadySlowedVelocity()));
        return f;
    }

    /** The starting velocity of the seventh tick of the slowed walk from rest. */
    private static double steadySlowedVelocity() {
        double velocity = 0.0;
        for (int tick = 0; tick < 6; tick++) velocity = (velocity + SLOWED) * FRICTION;
        return velocity;
    }

    private void use(JavaTickFixture f, Hand hand) {
        ItemUseTestSupport.use(f.player, hand, sequence++);
    }

    private static void usingFlags(JavaTickFixture f, byte flags) {
        InputKeysTest.selfMetadata(f, WatchableIndexUtil.LIVING_ENTITY_FLAGS, flags);
    }

    private static void release(JavaTickFixture f) {
        var release =
                new ServerboundPlayerAction(PlayerAction.RELEASE_USE_ITEM, new BlockPos(0, 0, 0), Direction.DOWN, 0);
        var event = f.event(ServerboundPackets.PLAYER_ACTION, release);
        new PacketPlayerDigging().onPlayerAction(event, f.player, release);
        f.player.actionManager.onPlayerAction(event, f.player, release);
        assertFalse(f.player.isClientUsingItem(), "still using after the release");
    }

    /** Entity event 9, applied by its trailing ping. */
    private static void complete(JavaTickFixture f) {
        var packet = new ClientboundEntityEvent(f.player.entityID, (byte) 9);
        var event = ItemUseTestSupport.sendEvent(f.player, ClientboundPackets.ENTITY_EVENT, packet);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        var ping = (ClientboundPing) event.writesAfter().getLast().packet();
        event.tasksAfter().forEach(Runnable::run);
        assertTrue(f.player.addTransactionResponse(ping.id()));
        assertFalse(f.player.isClientUsingItem(), "still using after the completion");
    }

    private static PredictionResult replay(JavaTickFixture f, double[] row, double shiftX, double shiftZ) {
        f.player.xRot = (float) row[2];
        return f.tick(captured(row, shiftX, shiftZ), true);
    }

    private static void assertExactRow(JavaTickFixture f, double[] row, double shiftX, double shiftZ, int seq) {
        PredictionResult result = replay(f, row, shiftX, shiftZ);
        assertTrue(exact(result), "seq " + seq + " " + flags(result));
    }

    private static Vec3 captured(double[] row, double shiftX, double shiftZ) {
        return new Vec3(row[0] + shiftX, FLOOR_Y, row[1] + shiftZ);
    }

    private static Vec3 knocked(double[] row) {
        return new Vec3(row[0] + KNOCKED_SHIFT_X, row[1] + KNOCKED_SHIFT_Y, row[2] + KNOCKED_SHIFT_Z);
    }

    private static void selectSlot(JavaTickFixture f, int slot) {
        var packet = new ServerboundSetCarriedItem(slot);
        f.player.checkManager.dispatchEarlyReceive(f.event(ServerboundPackets.SET_CARRIED_ITEM, packet));
        f.player.getInventory().selectHotbarSlot(slot);
    }

    private static String flags(PredictionResult result) {
        StringBuilder text = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            text.append(' ').append(flag.getCheck().getCheckName()).append('=').append(flag.getSeverity());
        }
        return text.toString();
    }

    private static void assertNoFlags(JavaTickFixture f) {
        assertEquals(0, f.player.checkManager.getListener(InputKeys.class).violations);
        assertEquals(0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
    }
}
