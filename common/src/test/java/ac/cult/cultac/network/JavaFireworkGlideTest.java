package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * FireworkRocketEntity#tick runs after the LocalPlayer's tick: while the attached player is fall flying it sets the
 * stored velocity to v + 0.1 * look + (1.5 * look - v) * 0.5 with that tick's look, and the next tick's travel
 * starts from it.
 */
class JavaFireworkGlideTest {
    private static final int ROCKET = 4242;
    private static final double GRAVITY = 0.08;
    private static final float YAW = 30.0F, PITCH = -20.0F;
    private static final Vec3 FROM = new Vec3(8.5, JavaTickFixture.FLOOR_Y + 40, 8.5);
    private static final Vec3 CARRIED = new Vec3(0.5, -0.1, 0.8);
    private static final Vec3 UP = new Vec3(0, 0.6, 0);
    private static final Vec3 KNOCKBACK = new Vec3(0.6, 0.4, 0.3);
    private static final Vec3 SET = new Vec3(0.3, 0.5, -0.2);
    private static final RelativeFlag ABSOLUTE = new RelativeFlag(0);
    private static final RelativeFlag RELATIVE_DELTAS = new RelativeFlag(0xE0);
    private static final Vec3 TO = new Vec3(8.5, JavaTickFixture.FLOOR_Y + 30, 8.5);

    @Test
    void aRocketAttachedBeforeTheLastTickBoostsThisGlide() throws Exception {
        assertExact(glideAfterBoost(Vec3.ZERO));
    }

    @Test
    void theBoostSetsTheVerticalVelocityOfTheGlide() throws Exception {
        assertNotVanilla(glideAfterBoost(UP));
    }

    @Test
    void aRocketAttachedThisTickBoostsOnlyTheNextTick() throws Exception {
        try (var f = gliding()) {
            Vec3 first = glide(f, CARRIED);
            assertExact(f.tick(FROM.add(first), false));
            attachRocket(f);
            Vec3 second = glide(f, first);
            assertExact(f.tick(FROM.add(first).add(second), false));
        }
        try (var f = gliding()) {
            Vec3 first = glide(f, CARRIED);
            assertExact(f.tick(FROM.add(first), false));
            attachRocket(f);
            Vec3 boosted = glide(f, boost(f, first));
            assertNotVanilla(f.tick(FROM.add(first).add(boosted), false));
        }
    }

    /** ClientPacketListener#handleExplosion adds the knockback to the velocity the rocket boosted. */
    @Test
    void anExplosionAddsToTheBoostedVelocity() throws Exception {
        try (var f = boostedGlider()) {
            Vec3 first = glide(f, CARRIED);
            f.explosion(KNOCKBACK);
            assertExact(f.tick(FROM.add(first).add(glide(f, boost(f, first).add(KNOCKBACK))), false));
        }
        try (var f = boostedGlider()) {
            Vec3 first = glide(f, CARRIED);
            f.explosion(KNOCKBACK);
            assertNotVanilla(f.tick(FROM.add(first).add(glide(f, boost(f, first.add(KNOCKBACK)))), false));
        }
    }

    /** ClientPacketListener#handleSetEntityMotion replaces the velocity the rocket boosted. */
    @Test
    void aServerVelocityReplacesTheBoostedVelocity() throws Exception {
        try (var f = boostedGlider()) {
            Vec3 first = glide(f, CARRIED);
            f.pongThrough(f.velocity(SET));
            assertExact(f.tick(FROM.add(first).add(glide(f, SET)), false));
        }
        try (var f = boostedGlider()) {
            Vec3 first = glide(f, CARRIED);
            f.pongThrough(f.velocity(SET));
            assertNotVanilla(f.tick(FROM.add(first).add(glide(f, boost(f, SET))), false));
        }
    }

    /**
     * The rocket boosts the stored velocity before ClientPacketListener#handleMovePlayer, so
     * PositionMoveRotation#calculateAbsolute sets each absolute delta axis and keeps each relative one of the boost.
     */
    @Test
    void anAbsoluteTeleportReplacesTheBoostedVelocity() throws Exception {
        assertExact(afterTeleport(ABSOLUTE, Vec3.ZERO, YAW, (f, v) -> Vec3.ZERO));
        assertNotVanilla(afterTeleport(ABSOLUTE, Vec3.ZERO, YAW, (f, v) -> boost(f, Vec3.ZERO)));
    }

    @Test
    void aRelativeTeleportKeepsTheBoostedVelocity() throws Exception {
        assertExact(afterTeleport(RELATIVE_DELTAS, Vec3.ZERO, YAW, JavaFireworkGlideTest::boost));
        assertNotVanilla(afterTeleport(RELATIVE_DELTAS, Vec3.ZERO, YAW, (f, v) -> boost(f, boost(f, v))));
    }

    /** TeleportCommand gives /tp with a relative y alone a relative y delta. */
    @Test
    void aTeleportWithARelativeVerticalDeltaKeepsTheBoostedFall() throws Exception {
        RelativeFlag deltaY = new RelativeFlag(0x40);
        assertExact(afterTeleport(deltaY, Vec3.ZERO, YAW, (f, v) -> new Vec3(0, boost(f, v).y, 0)));
        assertNotVanilla(afterTeleport(deltaY, Vec3.ZERO, YAW, (f, v) -> boost(f, new Vec3(0, v.y, 0))));
    }

    @Test
    void aTeleportWithRelativeHorizontalDeltasKeepsTheBoostedGlide() throws Exception {
        RelativeFlag deltaXZ = new RelativeFlag(0xA0);
        assertExact(afterTeleport(deltaXZ, Vec3.ZERO, YAW, (f, v) -> boost(f, v).multiply(1, 0, 1)));
        assertNotVanilla(afterTeleport(deltaXZ, Vec3.ZERO, YAW, (f, v) -> boost(f, v.multiply(1, 0, 1))));
    }

    @Test
    void aRelativeDeltaAddsToTheBoostedVelocity() throws Exception {
        Vec3 delta = new Vec3(0.2, 0.1, -0.3);
        assertExact(afterTeleport(RELATIVE_DELTAS, delta, YAW, (f, v) -> boost(f, v).add(delta)));
        assertNotVanilla(afterTeleport(RELATIVE_DELTAS, delta, YAW, (f, v) -> boost(f, v.add(delta))));
    }

    /** ROTATE_DELTA turns the boosted velocity by the teleport's turn. */
    @Test
    void aRotatingTeleportTurnsTheBoostedVelocity() throws Exception {
        RelativeFlag rotateDelta = new RelativeFlag(0x1E0);
        float yaw = YAW - 50.0F;
        assertExact(afterTeleport(rotateDelta, Vec3.ZERO, yaw, (f, v) -> turn(boost(f, v), yaw)));
        assertNotVanilla(afterTeleport(rotateDelta, Vec3.ZERO, yaw, (f, v) -> boost(f, turn(v, yaw))));
    }

    /** LivingEntity#updateFallFlying clears the flag on the server; the client glides until that metadata arrives. */
    @Test
    void theTravelAfterTheServerStopsTheGlideStartsFromTheBoost() throws Exception {
        try (var f = gliding()) {
            attachRocket(f);
            Vec3 first = glide(f, CARRIED);
            assertExact(f.tick(FROM.add(first), false));
            f.player.isGliding = false;
            f.player.refreshPlayerPose();
            // LivingEntity#travelInAir moves by the stored velocity when there is no input.
            Vec3 air = boost(f, first);
            PredictionResult result = f.tick(FROM.add(first).add(air), false);
            assertFalse(result.getSimulationContext().isGliding());
            assertExact(result);
        }
    }

    /** LivingEntity#travel takes the fluid branch before travelFallFlying; travelInWater moves by the stored velocity. */
    @Test
    void aGliderEnteringWaterStartsItsWaterTravelFromTheBoost() throws Exception {
        try (var f = gliding()) {
            attachRocket(f);
            Vec3 first = glide(f, CARRIED);
            assertExact(f.tick(FROM.add(first), false));
            fillWater(f);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 water = boost(f, first);
            PredictionResult result = f.tick(FROM.add(first).add(water), false);
            assertTrue(result.getSimulationContext().getWorldData().isInLiquid());
            assertExact(result);
        }
    }

    /** The client may already hold a water block whose placement is still in flight. */
    @Test
    void aGliderThatMayHaveEnteredWaterStartsItsWaterTravelFromTheBoost() throws Exception {
        try (var f = boostedGlider()) {
            Vec3 first = glide(f, CARRIED);
            fillWater(f);
            PredictionResult result = f.tick(FROM.add(first).add(boost(f, first)), false);
            assertTrue(result.getSimulationContext().getWorldData().isInLiquid()
                    || result.getAnyReality().stream().anyMatch(r -> r.getSimulationContext().getWorldData().isInLiquid()));
            assertTrue(!result.getSimulationContext().getWorldData().isInLiquid()
                    || result.getAnyReality().stream().anyMatch(r -> !r.getSimulationContext().getWorldData().isInLiquid()));
            assertExact(result);
        }
        try (var f = boostedGlider()) {
            Vec3 first = glide(f, CARRIED);
            fillWater(f);
            assertNotVanilla(f.tick(FROM.add(first).add(boost(f, first)).add(UP), false));
        }
    }

    /** The rocket's attachment, which the client may apply before any tick until the next ping proves it. */
    private static void attachRocket(JavaTickFixture f) {
        var entities = f.player.compensatedEntities;
        entities.addEntity(ROCKET, EntityTypeIds.FIREWORK_ROCKET, new Vec3(f.player.x, f.player.y, f.player.z), 0, 0, 0);
        var rocket = (ac.cult.cultac.utils.data.packetentity.PacketEntityFirework) entities.getEntity(ROCKET);
        rocket.attachment = f.player.entityID;
        f.player.compensatedFireworks.addNewFirework(ROCKET);
    }

    /** A glider whose first tick the attached rocket boosted. */
    private static JavaTickFixture boostedGlider() throws Exception {
        JavaTickFixture f = gliding();
        attachRocket(f);
        assertExact(f.tick(FROM.add(glide(f, CARRIED)), false));
        return f;
    }

    private static void fillWater(JavaTickFixture f) {
        for (int x = 6; x <= 11; x++) {
            for (int y = JavaTickFixture.FLOOR_Y + 38; y <= JavaTickFixture.FLOOR_Y + 43; y++) {
                for (int z = 6; z <= 13; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
    }

    /**
     * The tick after a teleport to {@code TO} with {@code flags} and {@code delta} that turns the boosted glider to
     * {@code yaw}, moving by the glide from {@code start} of the last tick's velocity.
     */
    private static PredictionResult afterTeleport(
            RelativeFlag flags, Vec3 delta, float yaw, BiFunction<JavaTickFixture, Vec3, Vec3> start)
            throws Exception {
        try (var f = boostedGlider()) {
            Vec3 last = glide(f, CARRIED);
            Vec3 started = start.apply(f, last);
            f.teleport(f.player.sendTrailingTransaction(), TO, delta, flags, YAW, PITCH, yaw, PITCH);
            f.player.xRot = yaw;
            Vector3dm look = ReachUtils.getLook(f.player, yaw, PITCH);
            return f.tick(TO.add(ElytraTransform.getElytraMovement(f.player, started, look, PITCH, GRAVITY)), false);
        }
    }

    /** PositionMoveRotation#calculateAbsolute's turn of the delta movement from YAW to {@code yaw}. */
    private static Vec3 turn(Vec3 velocity, float yaw) {
        return velocity.xRot(0.0F).yRot((float) Math.toRadians(YAW - yaw));
    }

    private static PredictionResult glideAfterBoost(Vec3 extra) throws Exception {
        try (var f = gliding()) {
            attachRocket(f);
            Vec3 first = glide(f, CARRIED);
            assertExact(f.tick(FROM.add(first), false));
            Vec3 second = glide(f, boost(f, first));
            return f.tick(FROM.add(first).add(second).add(extra), false);
        }
    }

    private static JavaTickFixture gliding() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.isGliding = true;
        f.player.refreshPlayerPose();
        f.player.xRot = YAW;
        f.player.yRot = PITCH;
        f.place(FROM, CARRIED);
        return f;
    }

    private static Vector3dm look(JavaTickFixture f) {
        return ReachUtils.getLook(f.player, YAW, PITCH);
    }

    private static Vec3 boost(JavaTickFixture f, Vec3 velocity) {
        Vector3dm look = look(f);
        return velocity.add(
                look.getX() * 0.1 + (look.getX() * 1.5 - velocity.x) * 0.5,
                look.getY() * 0.1 + (look.getY() * 1.5 - velocity.y) * 0.5,
                look.getZ() * 0.1 + (look.getZ() * 1.5 - velocity.z) * 0.5);
    }

    private static Vec3 glide(JavaTickFixture f, Vec3 velocity) {
        return ElytraTransform.getElytraMovement(f.player, velocity, look(f), PITCH, GRAVITY);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    private static void assertNotVanilla(PredictionResult result) {
        assertFalse(result.isExempt(), describe(result));
        assertTrue(result.getOffset() > 0.1, describe(result));
        assertFalse(result.getFlags().isEmpty(), describe(result));
    }
}
