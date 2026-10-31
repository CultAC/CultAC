package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPlayerTeam;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaLivingPushCarryRegressionTest {
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aRemoteGlidingBoxDoesNotReachAboveItsRealHeight(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var position = new Vec3(.5, 80, .5);
            f.place(position, Vec3.ZERO);
            f.addRemotePlayer(100, new Vec3(.7, 79, .5), GameMode.SURVIVAL);
            var remote = f.player.compensatedEntities.getEntity(100);
            var localBox = GetBoundingBox.getPlayerBoundingBox(f.player, position.x, position.y, position.z);
            remote.onPose(ac.cult.cultac.protocol.value.EntityPose.FALL_FLYING, 0);
            var small = JavaLivingEntityPushes.physicalBox(f.player, remote, remote.getExactMovementCollisionBox());
            assertEquals(79 + (double) .6F, small.maxY);
            assertEquals(List.of(Vec3.ZERO), JavaLivingEntityPushes.impulses(List.of(small), localBox, position));
            remote.onPose(ac.cult.cultac.protocol.value.EntityPose.STANDING, 0);
            var tall = JavaLivingEntityPushes.physicalBox(f.player, remote, remote.getExactMovementCollisionBox());
            assertTrue(JavaLivingEntityPushes.impulses(List.of(tall), localBox, position)
                            .getFirst()
                            .x
                    < -.02);
        }
    }

    @org.junit.jupiter.api.Test
    void tinyRealOverlapStillAppliesTheWholeLivingPush() {
        var pusher = new ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox(0, 0, 0, 1, 1, 1);
        var player = new ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox(1 - 1e-10, 0, 0, 1.6, 1.8, 0.6);
        assertTrue(JavaLivingEntityPushes.intersects(pusher, player));
        var impulses = JavaLivingEntityPushes.impulses(List.of(pusher), player, new Vec3(1.3, 0, 0.5));
        assertEquals(1, impulses.size());
        assertTrue(impulses.getFirst().x > 0.04);
    }

    /**
     * The 26.3 native sustained-contact run on 2026-10-25 produced 44 falses: the old circle added X/Z after
     * the glide, missing 0.0013..0.0087 Y. A later RemotePlayer tick must add its impulse to the stored velocity
     * before the following updateFallFlyingMovement, which couples horizontal speed into Y while looking up.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aLatePlayerPushIsStoredBeforeTheNextNoseUpGlide(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 position = new Vec3(0.5, 80, 0.5);
            Vec3 velocity = new Vec3(-0.03, -0.1, 0.01);
            f.place(position, velocity);
            f.player.isGliding = true;
            f.player.pose = Pose.FALL_FLYING;
            f.player.boundingBox = GetBoundingBox.getPlayerBoundingBox(f.player, position.x, position.y, position.z);
            f.player.xRot = 0;
            f.player.yRot = -40;
            f.addRemotePlayer(100, new Vec3(0.7, 79.5, 0.5), GameMode.SURVIVAL);
            PacketEntity pusher = f.player.compensatedEntities.getEntity(100);
            for (int tick = 0; tick < 3; tick++) {
                Vec3 movement = ElytraTransform.getElytraMovement(
                        f.player, velocity, ReachUtils.getLook(f.player, 0, -40), -40, 0.08);
                position = position.add(movement);
                var result = f.tick(position, false);
                assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
                assertEquals(0.0, result.getOffset(), 1e-13, JavaTickFixture.describe(result));
                var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
                assertEquals(0.0, movement.distanceTo(move.attempted()), 1e-13);
                Vec3 impulse = JavaLivingEntityPushes.impulse(pusher.getExactMovementCollisionBox(), position);
                velocity = movement.add(impulse);
                var carry = assertInstanceOf(
                        JavaPredictionCarry.class,
                        f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry());
                Vec3 expected = velocity;
                assertTrue(
                        carry.states().stream()
                                .anyMatch(state -> state.velocity().distanceTo(expected) <= 1e-13),
                        () -> "missing exact player push " + expected + " in " + carry.states());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aReceivedNeverCollisionRulePreventsThePush(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 position = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
            Vec3 gravity = new Vec3(0, -0.08 * (double) 0.98F, 0);
            f.place(position, gravity);
            f.addRemotePlayer(100, position.add(0.2, 0, 0), GameMode.SURVIVAL);
            f.player.compensatedEntities.getEntity(100).scoreboardName = "pusher";
            f.player.compensatedEntities.teamCollision.apply(
                    new ClientboundSetPlayerTeam("quiet", 0, "never", List.of("pusher")));
            var result = f.tick(position, true);
            assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
            var carry = assertInstanceOf(
                    JavaPredictionCarry.class,
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry());
            assertTrue(carry.states().stream().allMatch(state -> state.velocity().x == 0 && state.velocity().z == 0));
        }
    }
}
