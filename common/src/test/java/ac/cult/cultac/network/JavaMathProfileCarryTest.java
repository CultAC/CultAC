package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntityFirework;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A glider rising straight up with level pitch has no horizontal speed, so LivingEntity#updateFallFlyingMovement's look
 * terms vanish and both trig tables travel identically. A rocket ticking after LocalPlayer then boosts the stored
 * velocity along Entity#getLookAngle, where the tables differ (OptiFine's rounds sin(0.087 degrees) to zero): the
 * table the client travelled with decides the next tick, so neither may be dropped while the travel ties. Before
 * 1.21.5 LivingEntity#aiStep zeroes each axis under 0.003, which erases the 0.087 degree boost for both tables.
 */
class JavaMathProfileCarryTest {
    private static final float[] YAWS = {0.087F, 17.293F};

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aTiedTravelKeepsBothProfilesForTheLateRocketsBoost(ClientVersion version) throws Exception {
        for (float yaw : YAWS)
            for (boolean vanilla : new boolean[] {true, false}) {
                try (var f = new JavaTickFixture()) {
                    JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                    Vec3 position = new Vec3(0.5, 80, 0.5);
                    Vec3 velocity = new Vec3(0, 1, 0);
                    f.place(position, velocity);
                    f.player.isGliding = true;
                    f.player.pose = Pose.FALL_FLYING;
                    f.player.boundingBox =
                            GetBoundingBox.getPlayerBoundingBox(f.player, position.x, position.y, position.z);
                    rocket(f, 100);
                    f.player.xRot = yaw;
                    f.player.yRot = 0.0F;
                    Vec3 vanillaMove = movement(f, velocity, true);
                    Vec3 fastMove = movement(f, velocity, false);
                    assertEquals(vanillaMove, fastMove, "the first travel must tie");
                    assertNotEquals(boost(f, vanillaMove, true), boost(f, fastMove, false), "the boost must not tie");
                    for (int tick = 0; tick < 3; tick++) {
                        Vec3 movement = movement(f, threshold(version, velocity), vanilla);
                        position = position.add(movement);
                        var result = f.tick(position, false);
                        assertTrue(
                                JavaTickFixture.exact(result),
                                yaw + (vanilla ? " vanilla" : " fast") + " tick " + tick + ": "
                                        + JavaTickFixture.describe(result));
                        velocity = boost(f, movement, vanilla);
                    }
                }
            }
    }

    private static PacketEntityFirework rocket(JavaTickFixture f, int id) {
        var entities = f.player.compensatedEntities;
        entities.addEntity(id, EntityTypeIds.FIREWORK_ROCKET, new Vec3(0.5, 80, 0.5), 0, 0, 0);
        var rocket = assertInstanceOf(PacketEntityFirework.class, entities.getEntity(id));
        rocket.attachment = f.player.entityID;
        f.player.compensatedFireworks.addNewFirework(id);
        return rocket;
    }

    /** LivingEntity#aiStep's movement threshold before travel: per axis before 1.21.5, horizontally for players after. */
    private static Vec3 threshold(ClientVersion version, Vec3 velocity) {
        double x = velocity.x, y = velocity.y, z = velocity.z;
        if (version.isOlderThan(ClientVersion.V_1_21_5)) {
            if (Math.abs(x) < 0.003) x = 0;
            if (Math.abs(z) < 0.003) z = 0;
        } else if (x * x + z * z < 9.0E-6) {
            x = 0;
            z = 0;
        }
        if (Math.abs(y) < 0.003) y = 0;
        return new Vec3(x, y, z);
    }

    /** LivingEntity#travelFallFlying's move with {@code vanilla}'s table. */
    private static Vec3 movement(JavaTickFixture f, Vec3 velocity, boolean vanilla) {
        if (!vanilla) f.player.trigHandler.toggleShitMath();
        try {
            Vector3dm look = ReachUtils.getLook(f.player, f.player.xRot, f.player.yRot);
            return ElytraTransform.getElytraMovement(f.player, velocity, look, f.player.yRot, 0.08);
        } finally {
            if (!vanilla) f.player.trigHandler.toggleShitMath();
        }
    }

    /** FireworkRocketEntity#tick's boost along Entity#getLookAngle with {@code vanilla}'s table. */
    private static Vec3 boost(JavaTickFixture f, Vec3 velocity, boolean vanilla) {
        if (!vanilla) f.player.trigHandler.toggleShitMath();
        try {
            Vector3dm look = ReachUtils.getLook(f.player, f.player.xRot, f.player.yRot);
            return velocity.add(
                    look.getX() * 0.1 + (look.getX() * 1.5 - velocity.x) * 0.5,
                    look.getY() * 0.1 + (look.getY() * 1.5 - velocity.y) * 0.5,
                    look.getZ() * 0.1 + (look.getZ() * 1.5 - velocity.z) * 0.5);
        } finally {
            if (!vanilla) f.player.trigHandler.toggleShitMath();
        }
    }
}
