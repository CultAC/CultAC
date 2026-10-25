package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ShulkerData;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Shulker#canBeCollidedWith admits every living shulker, so Entity#collide stops a player on its bounding box,
 * Shulker#makeBoundingBox: the closed box grown away from the attach face by Shulker#getPhysicalPeek of
 * currentPeekAmount, which Shulker#updatePeekAmount moves by 0.05F toward DATA_PEEK_ID * 0.01F each client tick.
 */
class JavaShulkerEntityCollisionTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double FALL = 0.0784000015258789;
    private static final int SHULKER = 100;
    // A 1.21.4 client's offset from the shulker's position, the block centre the shulker sits on.
    private static final double X = 0.66393651849273;
    private static final double Z = 0.37791566111454;

    /**
     * A 1.21.4 client rides a closing shulker down: twenty client ticks after it handles DATA_PEEK_ID 0, its last
     * fall of the closing reaches the closed box, 0.0061528 below.
     */
    @Test
    void aPlayerRidingAClosingShulkerDownLandsOnItsClosedBox() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 100);
            ticks(f, 20);
            peek(f, 0);
            ticks(f, 20);
            stand(f, FLOOR_Y + 1.00615283846855);

            PredictionResult landing = f.tick(at(FLOOR_Y + 1), true);
            assertTrue(exact(landing), describe(landing));
        }
    }

    /** A shulker at rest at DATA_PEEK_ID 30 has a box 0.20609185 above its closed box. */
    @Test
    void aPlayerStandsOnAPeekingShulker() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 30);
            ticks(f, 6);
            stand(f, FLOOR_Y + 1.20609185099602);

            PredictionResult still = f.tick(at(FLOOR_Y + 1.20609185099602), true);
            assertTrue(exact(still), describe(still));
        }
    }

    @Test
    void aPlayerStandsOnAnOpenShulker() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 100);
            ticks(f, 20);
            stand(f, FLOOR_Y + 2);

            PredictionResult still = f.tick(at(FLOOR_Y + 2), true);
            assertTrue(exact(still), describe(still));
        }
    }

    @Test
    void aPlayerAboveAClosedShulkerStillFalls() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            stand(f, FLOOR_Y + 1.1);

            PredictionResult hover = f.tick(at(FLOOR_Y + 1.1), true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /**
     * Closing from DATA_PEEK_ID 100 to 30 takes fourteen Shulker#updatePeekAmount steps; each move's box reaches the
     * larger of the peeks before and after that tick's step.
     */
    @Test
    void aShulkerClosingToAPeekAnimatesEachStep() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 100);
            ticks(f, 20);
            peek(f, 30);
            assertTop(f, FLOOR_Y + 2);
            ticks(f, 13);
            assertTop(f, FLOOR_Y + 1 + physicalPeek(0.35F));
            ticks(f, 1);
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.isEmpty(), "the lid rests at the peek");
        }
    }

    /** Shulker#sanitizeScale caps the scale at 3, so a scale 5 shulker's closed box is three blocks tall. */
    @Test
    void aShulkerScaledPastThreeIsThreeBlocksTall() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            scale(f, 5.0F);
            stand(f, FLOOR_Y + 3);
            PredictionResult still = f.tick(at(FLOOR_Y + 3), true);
            assertTrue(exact(still), describe(still));

            stand(f, FLOOR_Y + 5);
            PredictionResult hover = f.tick(at(FLOOR_Y + 5), true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /** A scale 3 shulker opening from DATA_PEEK_ID 0 to 100 grows its box by three times each step's peek. */
    @Test
    void aScaledShulkerOpensOneStepAtATime() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            scale(f, 3.0F);
            peek(f, 100);
            ticks(f, 1);
            assertTop(f, FLOOR_Y + 3 + 3 * physicalPeek(0.1F));

            stand(f, FLOOR_Y + 4.5);
            PredictionResult hover = f.tick(at(FLOOR_Y + 4.5), true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /** Shulker#onPeekAmountChange pushes only while Shulker#getPhysicalPeek grows: opening, never closing. */
    @Test
    void onlyAnOpeningShulkerPushes() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 30);
            ticks(f, 6);
            peek(f, 100);
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.stream().anyMatch(ShulkerData::canPushEntities));
            ticksToRest(f);
            peek(f, 30);
            assertFalse(f.player.compensatedWorld.openShulkerBoxes.isEmpty());
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.stream().noneMatch(ShulkerData::canPushEntities));
        }
    }

    /** Shulker#getRawPeekAmount reads DATA_PEEK_ID as a signed byte. */
    @Test
    void aNegativePeekRaisesTheBox() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, -56);
            assertEquals(13, ticksToRest(f));
            stand(f, FLOOR_Y + 1 + physicalPeek(-0.56F));
            PredictionResult still = f.tick(at(FLOOR_Y + 1 + physicalPeek(-0.56F)), true);
            assertTrue(exact(still), describe(still));
        }
    }

    /** ClientLevel#tickEntities skips a shulker TickRateManager#isEntityFrozen reports, so its peek holds. */
    @Test
    void aFrozenShulkerHoldsItsPeek() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 100);
            ticks(f, 2);
            f.player.packetStateData.serverTicksFrozen = true;
            ticks(f, 30);
            double lid = FLOOR_Y + 1 + physicalPeek(0.1F);
            assertTop(f, lid);
            stand(f, lid);
            PredictionResult still = f.tick(at(lid), true);
            assertTrue(exact(still), describe(still));
            stand(f, FLOOR_Y + 2);
            PredictionResult hover = f.tick(at(FLOOR_Y + 2), true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /**
     * Mth#clamp in Shulker#updatePeekAmount returns its maximum when the minimum exceeds it. A step that grows
     * Shulker#getPhysicalPeek may push in the move after it, so an opening animates one tick past its last step.
     */
    @Test
    void peekStepsFollowMthClamp() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 100);
            assertEquals(21, ticksToRest(f));
            peek(f, 30);
            assertEquals(14, ticksToRest(f));
            peek(f, 127);
            assertEquals(20, ticksToRest(f));
            // 1.27F clamps to 1.0F, then rises by 0.05F steps to 1.1F.
            peek(f, 110);
            assertEquals(4, ticksToRest(f));
        }
    }

    /**
     * Below zero, Shulker#updatePeekAmount's rise clamps to 0.0F: one tick toward DATA_PEEK_ID -30 moves -0.56F to
     * 0.0F, and DATA_PEEK_ID -100 then takes twenty steps, each one growing Shulker#getPhysicalPeek.
     */
    @Test
    void aPeekRetargetedMidAnimationStepsFromWhereItIs() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, -56);
            ticksToRest(f);
            peek(f, -30);
            ticks(f, 1);
            assertTop(f, FLOOR_Y + 1 + physicalPeek(-0.05F));
            peek(f, -100);
            ticks(f, 15);
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.stream().anyMatch(ShulkerData::canPushEntities));
            assertEquals(6, ticksToRest(f));
            stand(f, FLOOR_Y + 2);
            PredictionResult still = f.tick(at(FLOOR_Y + 2), true);
            assertTrue(exact(still), describe(still));
        }
    }

    /** A shulker closing toward any peek below the one it holds never grows Shulker#getPhysicalPeek. */
    @Test
    void aShulkerRetargetedWhileClosingNeverPushes() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 100);
            ticksToRest(f);
            peek(f, 0);
            ticks(f, 4);
            peek(f, 30);
            assertNoPushUntilRest(f);

            peek(f, 0);
            ticksToRest(f);
            peek(f, 100);
            ticks(f, 10);
            peek(f, 30);
            ticks(f, 1);
            assertNoPushUntilRest(f);
        }
    }

    /** Until the client answers the ping after DATA_PEEK_ID, it may not have handled it, nor started stepping. */
    @Test
    void anUnansweredPeekKeepsTheShulkerAnimating() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            int proof = sendPeek(f, 100);
            ticks(f, 25);
            assertTop(f, FLOOR_Y + 2);
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.stream().anyMatch(ShulkerData::canPushEntities));
            f.pongThrough(proof);
            assertEquals(21, ticksToRest(f));
        }
    }

    /** The shulker's scale attribute, handled by the client. */
    private static void scale(JavaTickFixture f, float scale) {
        var shulker = f.player.compensatedEntities.getEntity(SHULKER);
        shulker.onScale(scale, 0);
        shulker.onSizeHandled(0);
        shulker.refreshDimensions(f.player);
    }

    private static SimpleCollisionBox box(double y) {
        return new SimpleCollisionBox(0.4, y, 0.4, 0.6, y + 0.1, 0.6);
    }

    private static JavaTickFixture legacyFixture() throws Exception {
        var f = new JavaTickFixture();
        setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
        return f;
    }

    /** A shulker attached to the floor below it, on the block centre (0.5, 0.5). */
    private static void shulker(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
        assertEquals(
                1, f.player.compensatedEntities.getEntity(SHULKER).getPossibleMovementCollisionBoxes().maxY - FLOOR_Y);
    }

    /** The server's DATA_PEEK_ID, handled by the client. */
    private static void peek(JavaTickFixture f, int peek) {
        f.pongThrough(sendPeek(f, peek));
    }

    /** The server's DATA_PEEK_ID and the ping after it; the ping's transaction. */
    private static int sendPeek(JavaTickFixture f, int peek) {
        int proof = f.player.lastTransactionSent.get() + 1;
        f.player.compensatedEntities.updateEntityMetadata(
                SHULKER,
                List.of(new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_PEEK, (byte) peek, ByteBuffer.allocate(0))),
                proof);
        assertEquals(proof, f.player.sendTrailingTransaction());
        return proof;
    }

    private static void ticks(JavaTickFixture f, int ticks) {
        for (int tick = 0; tick < ticks; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** The client ticks until the shulker's box rests and no push is left to show. */
    private static int ticksToRest(JavaTickFixture f) {
        int ticks = 0;
        while (!f.player.compensatedWorld.openShulkerBoxes.isEmpty() && ticks < 100) {
            ticks(f, 1);
            ticks++;
        }
        return ticks;
    }

    private static void assertNoPushUntilRest(JavaTickFixture f) {
        for (int tick = 0; !f.player.compensatedWorld.openShulkerBoxes.isEmpty(); tick++) {
            assertTrue(tick < 100, "the lid rests");
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.stream().noneMatch(ShulkerData::canPushEntities));
            ticks(f, 1);
        }
    }

    /** The animating shulker's box reaches {@code top} in the next move, and no higher. */
    private static void assertTop(JavaTickFixture f, double top) {
        assertTrue(f.player.compensatedWorld.hasOpenShulkerCollision(box(top - 0.09)), "the box reaches " + top);
        assertFalse(f.player.compensatedWorld.hasOpenShulkerCollision(box(top + 0.001)), "the box stops at " + top);
    }

    // Shulker#getPhysicalPeek on a 1.21.4 client.
    private static float physicalPeek(float peekAmount) {
        return 0.5F - VanillaMath.sin(ClientVersion.V_1_21_4, (0.5F + peekAmount) * (float) Math.PI) * 0.5F;
    }

    /** The player on the ground at {@code y} after a grounded tick, LivingEntity#travel's gravity next. */
    private static void stand(JavaTickFixture f, double y) {
        f.place(at(y), new Vec3(0, -FALL, 0));
        f.player.onGround = f.player.lastOnGround = true;
        f.input(NONE);
    }

    private static Vec3 at(double y) {
        return new Vec3(X, y, Z);
    }
}
