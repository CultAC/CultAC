package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.OmniSprint;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OmniSprintTest {
    private static final ServerboundPlayerInput NONE = keys(false, false, false, false);
    private static final ServerboundPlayerInput FORWARD = keys(true, false, false, false);
    private static final ServerboundPlayerInput FORWARD_LEFT = keys(true, false, true, false);
    private static final ServerboundPlayerInput FORWARD_RIGHT = keys(true, false, false, true);
    private static final ServerboundPlayerInput BACK = keys(false, true, false, false);
    private static final ServerboundPlayerInput BACK_LEFT = keys(false, true, true, false);
    private static final ServerboundPlayerInput LEFT = keys(false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, true, false);
    private static final float YAW = 30.0F;

    enum Medium {
        GROUND(
                new Vec3(0.5, FLOOR_Y, -4.5),
                -0.0784000015258789,
                (float) ((double) 0.1F * (1.0D + (double) 0.3F)),
                0.1F,
                0.6F * 0.91F),
        AIR(new Vec3(0.5, FLOOR_Y + 12, 0.5), 0.0, 0.025999999F, 0.02F, 0.91F);

        final Vec3 start;
        final double startY;
        final float sprintSpeed;
        final float walkSpeed;
        final float friction;

        Medium(Vec3 start, double startY, float sprintSpeed, float walkSpeed, float friction) {
            this.start = start;
            this.startY = startY;
            this.sprintSpeed = sprintSpeed;
            this.walkSpeed = walkSpeed;
            this.friction = friction;
        }
    }

    @ParameterizedTest
    @EnumSource(Medium.class)
    void sprintingForwardAndDiagonallyForwardWhileTurningIsExact(Medium medium) throws Exception {
        for (ClientVersion version : new ClientVersion[] {ClientVersion.V_26_3, ClientVersion.V_1_21_4}) {
            try (var f = fixture(version)) {
                var run = new Run(f, medium);
                f.command(PlayerCommandAction.START_SPRINTING);
                for (ServerboundPlayerInput keys :
                        new ServerboundPlayerInput[] {FORWARD, FORWARD, FORWARD_LEFT, FORWARD_LEFT, FORWARD_RIGHT}) {
                    run.assertExact(keys);
                }
                assertNotSetBack(f, medium + " " + version);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Medium.class)
    void sprintingBackwardIsSetBack(Medium medium) throws Exception {
        assertSetBack(medium, BACK);
    }

    @ParameterizedTest
    @EnumSource(Medium.class)
    void sprintingSidewaysIsSetBack(Medium medium) throws Exception {
        assertSetBack(medium, LEFT);
    }

    @ParameterizedTest
    @EnumSource(Medium.class)
    void sprintingDiagonallyBackwardIsSetBack(Medium medium) throws Exception {
        assertSetBack(medium, BACK_LEFT);
    }

    @Test
    void sprintingBackwardWhilePushedByAZombieIsSetBack() throws Exception {
        Medium medium = Medium.GROUND;
        for (ClientVersion version : new ClientVersion[] {ClientVersion.V_26_3, ClientVersion.V_1_21_4}) {
            try (var f = fixture(version)) {
                var run = new Run(f, medium);
                f.command(PlayerCommandAction.START_SPRINTING);
                run.assertExact(FORWARD);
                run.pusher = run.position.add(0.5, 0, 0);
                zombie(f, run.pusher);
                PredictionResult result = run.tick(BACK);
                String where = medium + " " + version + ": " + describe(result);
                assertEquals(1, result.getSimulationContext().getWorldData().getNumPushing(), where);
                assertTrue(result.hasFlag(OmniSprint.class), where);
                assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), "not set back " + where);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Medium.class)
    void sprintingForwardWhilePushedByAZombieIsExact(Medium medium) throws Exception {
        for (ClientVersion version : new ClientVersion[] {ClientVersion.V_26_3, ClientVersion.V_1_21_4}) {
            try (var f = fixture(version)) {
                var run = new Run(f, medium);
                f.command(PlayerCommandAction.START_SPRINTING);
                run.assertExact(FORWARD);
                run.pusher = run.position.add(0.5, 0, 0);
                zombie(f, run.pusher);
                run.assertExact(FORWARD_LEFT);
                assertNotSetBack(f, medium + " " + version);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Medium.class)
    void releasingForwardStopsTheSprintBeforeTheMove(Medium medium) throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            var run = new Run(f, medium);
            f.command(PlayerCommandAction.START_SPRINTING);
            run.assertExact(FORWARD);
            run.assertExact(FORWARD);
            f.command(PlayerCommandAction.STOP_SPRINTING);
            run.assertExact(NONE);
            run.assertExact(BACK);
            run.assertExact(LEFT);
            assertNotSetBack(f, medium.toString());
        }
    }

    @ParameterizedTest
    @EnumSource(Medium.class)
    void aKnockbackWhileSprintingForwardIsExact(Medium medium) throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            var run = new Run(f, medium);
            f.command(PlayerCommandAction.START_SPRINTING);
            run.assertExact(FORWARD);
            Vec3 knockback = new Vec3(-0.25, medium == Medium.GROUND ? medium.startY : 0.0, -0.3);
            f.pongThrough(f.velocity(knockback));
            run.velocity = knockback;
            run.assertExact(FORWARD);
            run.assertExact(FORWARD_RIGHT);
            assertNotSetBack(f, medium.toString());
        }
    }

    @ParameterizedTest
    @EnumSource(Medium.class)
    void sneakingWithForwardHeldKeepsTheSprint(Medium medium) throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            var run = new Run(f, medium);
            f.command(PlayerCommandAction.START_SPRINTING);
            run.assertExact(FORWARD);
            run.assertExact(SNEAK_FORWARD);
            run.slowdown = 0.3F;
            run.assertExact(SNEAK_FORWARD_LEFT);
            run.assertExact(SNEAK_FORWARD);
            assertNotSetBack(f, medium.toString());
        }
    }

    private static void assertSetBack(Medium medium, ServerboundPlayerInput keys) throws Exception {
        for (ClientVersion version : new ClientVersion[] {ClientVersion.V_26_3, ClientVersion.V_1_21_4}) {
            try (var f = fixture(version)) {
                var run = new Run(f, medium);
                f.command(PlayerCommandAction.START_SPRINTING);
                run.assertExact(FORWARD);
                PredictionResult result = run.tick(keys);
                String where = medium + " " + version + ": " + describe(result);
                assertTrue(result.hasFlag(OmniSprint.class), where);
                assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), "not set back " + where);
            }
        }
    }

    private static void assertNotSetBack(JavaTickFixture f, String where) {
        assertFalse(f.player.checkManager.getCheck(OmniSprint.class).violations > 0, where);
        assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), where);
    }

    private static void zombie(JavaTickFixture f, Vec3 at) {
        f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, at, 0, 0, 0);
        f.player.compensatedEntities.getEntity(100).setClientTickOrder(-1L);
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        return version == ClientVersion.V_26_3
                ? new JavaTickFixture()
                : new JavaTickFixture(ProtocolVersion.V1_21_4, version);
    }

    private static ServerboundPlayerInput keys(boolean forward, boolean backward, boolean left, boolean right) {
        return new ServerboundPlayerInput(forward, backward, left, right, false, false, false);
    }

    private static final class Run {
        final JavaTickFixture f;
        final Medium medium;
        Vec3 position;
        Vec3 velocity;
        Vec3 pusher;
        float yaw = YAW;
        float slowdown = 1.0F;

        Run(JavaTickFixture f, Medium medium) {
            this.f = f;
            this.medium = medium;
            this.position = medium.start;
            this.velocity = new Vec3(0, medium.startY, 0);
            f.place(position, velocity);
        }

        PredictionResult tick(ServerboundPlayerInput keys) {
            f.player.xRot = yaw;
            f.input(keys);
            // This zombie ticks before LocalPlayer, so LivingEntity#pushEntities adds Entity#push's impulse
            // before the player's travel. The refactored engine models that write rather than a push radius.
            double halfWidths = (double) 0.6F;
            if (pusher != null
                    && Math.abs(position.x - pusher.x) < halfWidths
                    && Math.abs(position.z - pusher.z) < halfWidths
                    && position.y < pusher.y + 1.95F
                    && position.y + 1.8F > pusher.y) {
                double x = position.x - pusher.x, z = position.z - pusher.z;
                double largest = Math.max(Math.abs(x), Math.abs(z));
                if (largest >= 0.01F) {
                    double root = Math.sqrt(largest);
                    double scale = Math.min(1.0 / root, 1.0);
                    velocity = velocity.add(x / root * scale * 0.05F, 0, z / root * scale * 0.05F);
                }
            }
            float speed = f.player.isSprinting ? medium.sprintSpeed : medium.walkSpeed;
            Vec3 input = ClientMoveInput.of(f, keys, slowdown, speed, yaw);
            Vec3 move = new Vec3(velocity.x + input.x, velocity.y, velocity.z + input.z);
            boolean grounded = medium == Medium.GROUND;
            position = position.add(move.x, grounded ? 0.0 : move.y, move.z);
            PredictionResult result = f.tick(position, grounded);
            double y = grounded ? medium.startY : (move.y - 0.08) * 0.98F;
            velocity = new Vec3(move.x * medium.friction, y, move.z * medium.friction);
            yaw += 7.5F;
            return result;
        }

        void assertExact(ServerboundPlayerInput keys) {
            PredictionResult result = tick(keys);
            assertTrue(exact(result), medium + " " + f.player.getClientVersion() + ": " + describe(result));
        }
    }
}
