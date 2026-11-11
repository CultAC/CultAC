package ac.cult.cultac.checks.impl.sprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollisionTest;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#shouldStopRunSprinting only stops sprinting on a hard collision; running into a wall along the input is
 * minor and keeps the sprint.
 */
class SprintETest {
    @Test
    void sprintingAfterAMinorWallCollisionIsNotFlagged() {
        run(rig -> {
            Vec3 slide = new Vec3(-0.2, 0, 0);
            Vec3 attempted = slide.add(ClientHorizontalCollisionTest.input(85, 0, 1, 0.13));
            rig.complete(rig.move(85, slide, new Vec3(attempted.x, 0, 0)));
            rig.complete(rig.move(85, Vec3.ZERO, new Vec3(0, 0, 0.1)));
            assertEquals(0, rig.sprintE.violations);
            verify(rig.setbacks, never()).executeViolationSetback();
        });
    }

    @Test
    void sprintingAfterAHardWallCollisionIsFlagged() {
        run(rig -> {
            Vec3 slide = new Vec3(0.3, 0, 0);
            Vec3 attempted = slide.add(ClientHorizontalCollisionTest.input(30, 0, 1, 0.13));
            rig.complete(rig.move(30, slide, new Vec3(attempted.x, 0, 0)));
            rig.complete(rig.move(30, Vec3.ZERO, new Vec3(0, 0, 0.1)));
            assertEquals(1, rig.sprintE.violations);
        });
    }

    @Test
    void sprintingAfterAWallMinorFromAnAcceptedSiblingStartIsNotFlagged() {
        // 01a17ffe move 2156: W at yaw -1.2172644 sprint-falls into a +Z wall. The selected start has X at 0, which W
        // cannot turn into the reported -0.0018 X; the accepted start (-0.0023563, 0.371) reaches it with W exactly,
        // 1.6 degrees off the collided movement, so LocalPlayer#isHorizontalCollisionMinor keeps the sprint.
        run(rig -> {
            rig.player.packetStateData.knownInput = new KnownInput(true, false, false, false, false, false, true);
            float yaw = -1.2172644F;
            Vec3 target = new Vec3(-0.0018164858938689576, -0.15523200451660557, 0.24328455423164996);
            CollideAxisData wall = Rig.wallAtPositiveZ(target, target.z);
            List<PredictionResult> realities = new ArrayList<>();
            PredictionResult selected = rig.reality(
                    yaw, new Vec3(0.0, -0.1552320045166016, 0.37103305941942144), target, wall, realities);
            rig.reality(
                    yaw,
                    new Vec3(-0.002356318533208103, -0.15523200451660105, 0.37103305941942144),
                    target,
                    wall,
                    realities);
            rig.complete(selected);
            rig.complete(rig.move(yaw, Vec3.ZERO, new Vec3(0, 0, 0.1)));
            assertEquals(0, rig.sprintE.violations);
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(selected));
        });
    }

    @Test
    void sprintingAfterALadderJumpThatStopsShortOfAWallIsNotFlagged() {
        // A W+D sprint-jump onto a ladder at yaw -2452.5981. LivingEntity#handleOnClimbable clamps
        // the X the input pushes past -0.15 back to it, so the input stays free to land Z 9e-4 short of the +Z wall.
        run(rig -> {
            rig.player.packetStateData.knownInput = new KnownInput(true, false, false, true, true, false, false);
            Vec3 jump = new Vec3(-0.30004172400405343, 0.41999998688697815, 0.1343276239849551);
            Vec3 target = new Vec3(-0.15000000596046448, 0.41999998688697815, 0.08477459692926459);
            rig.complete(rig.climb(-2452.5981F, jump, target, 0.08568146244738273));
            rig.complete(rig.move(-2457.7805F, Vec3.ZERO, new Vec3(0, 0, -0.1)));
            assertEquals(0, rig.sprintE.violations);
            verify(rig.setbacks, never()).executeViolationSetback();
        });
    }

    @Test
    void sprintingAfterALadderJumpIntoAWallIsFlagged() {
        // W+A at yaw 0 on a ladder: the clamp holds Z at 0.15 past the wall at 0.01 for every input length.
        run(rig -> {
            rig.player.packetStateData.knownInput = new KnownInput(true, false, true, false, true, false, false);
            Vec3 jump = new Vec3(-0.3, 0.41999998688697815, 0.3);
            Vec3 target = new Vec3(-0.15000000596046448, 0.41999998688697815, 0.01);
            rig.complete(rig.climb(0, jump, target, 0.01));
            rig.complete(rig.move(0, Vec3.ZERO, new Vec3(0, 0, -0.1)));
            assertEquals(1, rig.sprintE.violations);
        });
    }

    @Test
    void sprintingAfterAClimbingStepIntoAWallIsFlagged() {
        // W at yaw 0 on a ladder, judged as a step: CollisionModifier collides Z onto the wall at 0.01 before Ladder
        // clamps, so only the movement Entity#move collided, 0.3 past the wall for every input, is attempted.
        run(rig -> {
            rig.player.packetStateData.knownInput = new KnownInput(true, false, false, false, true, false, false);
            Vec3 delta = new Vec3(-0.1, 0.41999998688697815, 0.3);
            Vec3 target = new Vec3(-0.1, 0.41999998688697815, 0.01);
            PredVector attempted = new PredVector(delta);
            PredVector collided = attempted.withXYZ(delta.x, delta.y, target.z, "collide z");
            PredVector position = collided.withXYZ(collided.x, collided.y, collided.z, "ladder");
            rig.complete(rig.climbing(0, attempted, position, target, target.z, List.of()));
            rig.complete(rig.move(0, Vec3.ZERO, new Vec3(0, 0, -0.1)));
            assertEquals(1, rig.sprintE.violations);
        });
    }

    @Test
    void sprintingAfterALadderClampAndAPushIntoAWallIsFlagged() {
        // W at yaw 0 sliding -X on a ladder: the clamp holds Z at 0.15 and an entity push after it carries Z to 0.25,
        // past the wall at 0.2 for every input.
        run(rig -> {
            rig.player.packetStateData.knownInput = new KnownInput(true, false, false, false, true, false, false);
            Vec3 target = new Vec3(-0.1, 0.41999998688697815, 0.2);
            rig.complete(rig.pushedClimb(new Vec3(-0.1, 0.41999998688697815, 0.3), 0.1, target));
            rig.complete(rig.move(0, Vec3.ZERO, new Vec3(0, 0, -0.1)));
            assertEquals(1, rig.sprintE.violations);
        });
    }

    @Test
    void sprintingAfterALadderClampAndAPushToAWallIsNotFlagged() {
        // W at yaw 0 sliding -X on a ladder: the clamp holds Z at 0.15 and an entity push after it carries Z back to
        // 0.05, flush with the wall there for every input.
        run(rig -> {
            rig.player.packetStateData.knownInput = new KnownInput(true, false, false, false, true, false, false);
            Vec3 target = new Vec3(-0.1, 0.41999998688697815, 0.05);
            rig.complete(rig.pushedClimb(new Vec3(-0.1, 0.41999998688697815, 0.3), -0.1, target));
            rig.complete(rig.move(0, Vec3.ZERO, new Vec3(0, 0, -0.1)));
            assertEquals(0, rig.sprintE.violations);
        });
    }

    @Test
    void aWallMinorFromAnAcceptedClimbingSiblingIsMinor() {
        // W at yaw 85 slides -X along a +Z wall at 0.01, 3.8 degrees off the input. Only the sibling start that climbs
        // reaches it: handleOnClimbable holds its X at -0.15 while the input carries Z from 0 to the wall.
        run(rig -> {
            rig.player.packetStateData.knownInput = new KnownInput(true, false, false, false, false, false, true);
            Vec3 target = new Vec3(-0.15000000596046448, 0, 0.01);
            CollideAxisData wall = new CollideAxisData(
                    new CollideResult(false, target.x, 0),
                    new CollideResult(false, 0),
                    new CollideResult(false, target.y, 0),
                    new CollideResult(true, target.z, 1));
            List<PredictionResult> realities = new ArrayList<>();
            PredictionResult selected = rig.reality(85, new Vec3(-0.15000000596046448, 0, -0.2), target, wall, realities);
            realities.add(rig.climb(85, new Vec3(-0.3, 0, 0), target, target.z, realities));
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(selected));
        });
    }

    private static void run(Consumer<Rig> scenario) {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".SprintE_Test"), channel);
        var original = new CultPlayer(user);
        try {
            scenario.accept(new Rig(original));
        } finally {
            original.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static final class Rig {
        final CultPlayer player;
        final SetbackTeleportUtil setbacks = mock(SetbackTeleportUtil.class);
        final SprintE sprintE;

        Rig(CultPlayer original) {
            player = spy(original);
            player.gamemode = GameMode.SURVIVAL;
            player.noSetbackPermission = false;
            player.setExperimentalChecks(true);
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            player.isSprinting = true;
            sprintE = new SprintE(player);
            sprintE.reload();
            sprintE.violations = 0;
        }

        PredictionResult move(float yaw, Vec3 attemptedMomentum, Vec3 target) {
            PredictionResult result = ClientHorizontalCollisionTest.result(
                    yaw, attemptedMomentum, target, ClientHorizontalCollisionTest.wallAtPositiveZ());
            WorldData world = mock(WorldData.class);
            doReturn(false).when(world).isInWater();
            doReturn(DesyncStatus.FALSE).when(world).getClimbingAtStart();
            doReturn(world).when(result.getSimulationContext()).getWorldData();
            return result;
        }

        /** An accepted reality of {@code realities} starting at {@code start}, travelling with 26.2's keys and yaw. */
        PredictionResult reality(
                float yaw, Vec3 start, Vec3 target, CollideAxisData collide, List<PredictionResult> realities) {
            doReturn(ClientVersion.V_26_2).when(player).getClientVersion();
            SimulationContext context = mock(SimulationContext.class);
            doReturn(yaw).when(context).getXRot();
            doReturn(0.02F * 1.3D).when(context).getHorizontalInputRadius(any(), any());
            WorldData world = mock(WorldData.class);
            doReturn(false).when(world).isInWater();
            doReturn(DesyncStatus.FALSE).when(world).getClimbingAtStart();
            doReturn(world).when(context).getWorldData();
            ValidMovements valid = mock(ValidMovements.class);
            doReturn(new MovementTrace(new PredVector(start), Vec3.ZERO, Vec3.ZERO, start, start))
                    .when(valid)
                    .getClosestTrace();
            doReturn(new SimpleCollisionBox(start, start)).when(valid).getCollisionIgnoredMaxStartingVelExtents();
            PredictionResult result =
                    new PredictionResult(player, new PredVector(start), context, target, null, realities, realities);
            result.setValidMovements(valid);
            result.setCollideAxisData(collide);
            realities.add(result);
            return result;
        }

        /**
         * A move whose travel starts on a climbable and attempts {@code delta} before LivingEntity#handleOnClimbable,
         * ending flush against a +Z wall at {@code wallZ}.
         */
        PredictionResult climb(float yaw, Vec3 delta, Vec3 target, double wallZ) {
            return climb(yaw, delta, target, wallZ, List.of());
        }

        /** {@link #climb(float, Vec3, Vec3, double)} as one of the accepted {@code realities}. */
        PredictionResult climb(float yaw, Vec3 delta, Vec3 target, double wallZ, List<PredictionResult> realities) {
            PredVector clamped = new PredVector(delta)
                    .withXYZ(
                            Math.max(-0.15F, Math.min(0.15F, delta.x)),
                            delta.y,
                            Math.max(-0.15F, Math.min(0.15F, delta.z)),
                            "ladder");
            PredVector collided = clamped.withXYZ(clamped.x, clamped.y, wallZ, "collide z");
            return climbing(yaw, clamped, collided, target, wallZ, realities);
        }

        /**
         * A move at yaw 0 whose travel attempts {@code delta}, clamped by LivingEntity#handleOnClimbable, then pushed
         * by {@code pushZ} towards +Z, ending flush against a +Z wall at the target's Z.
         */
        PredictionResult pushedClimb(Vec3 delta, double pushZ, Vec3 target) {
            PredVector clamped = new PredVector(delta)
                    .withXYZ(delta.x, delta.y, Math.max(-0.15F, Math.min(0.15F, delta.z)), "ladder");
            PredVector pushed = clamped.add(0, 0, pushZ, "entity push");
            PredVector collided = pushed.withXYZ(pushed.x, pushed.y, target.z, "collide z");
            return climbing(0, pushed, collided, target, target.z, List.of());
        }

        /**
         * A move whose travel starts on a climbable, attempts {@code attempted} in Entity#move and ends at
         * {@code position}, flush against a +Z wall at {@code wallZ}.
         */
        PredictionResult climbing(
                float yaw,
                PredVector attempted,
                PredVector position,
                Vec3 target,
                double wallZ,
                List<PredictionResult> realities) {
            doReturn(ClientVersion.V_1_21_11).when(player).getClientVersion();
            SimulationContext context = mock(SimulationContext.class);
            doReturn(yaw).when(context).getXRot();
            doReturn(0.13F * 1.3D).when(context).getHorizontalInputRadius(any(), any());
            WorldData world = mock(WorldData.class);
            doReturn(false).when(world).isInWater();
            doReturn(DesyncStatus.TRUE).when(world).getClimbingAtStart();
            doReturn(world).when(context).getWorldData();
            ValidMovements valid = mock(ValidMovements.class);
            doReturn(new MovementTrace(position, Vec3.ZERO, Vec3.ZERO, attempted, attempted))
                    .when(valid)
                    .getClosestTrace();
            PredictionResult result = new PredictionResult(
                    player, new PredVector(attempted), context, target, null, realities, realities);
            result.setValidMovements(valid);
            result.setCollideAxisData(new CollideAxisData(
                    new CollideResult(false, target.x, 0),
                    new CollideResult(false, 0),
                    new CollideResult(false, target.y, 0),
                    new CollideResult(true, wallZ, 1)));
            return result;
        }

        void complete(PredictionResult result) {
            sprintE.onPredictionComplete(new PredictionComplete(result));
        }

        static CollideAxisData wallAtPositiveZ(Vec3 target, double wallZ) {
            return new CollideAxisData(
                    new CollideResult(false, target.x, 0),
                    new CollideResult(false, 0),
                    new CollideResult(false, target.y, 0),
                    new CollideResult(true, wallZ, 1));
        }
    }
}
