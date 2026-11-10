package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

class VelocityCandidatesTest {
    @Test
    void candidatesDropStartingVelocityFlags() {
        PredVector start = new PredVector(new Vec3(0.1, -0.078, 0.2));
        start.addPacketModifier(new TransactionVel(new Vec3(0.1, 0.4, 0.2), 0, true, false));
        assertTrue(start.isKnockback());

        PredictionResult result = result(start, start, Vec3.ZERO);

        Set<Vec3> candidates = derive(result);

        assertTrue(candidates.contains(new Vec3(0.1, -0.078, 0.2)));
        for (Vec3 candidate : candidates) {
            assertEquals(Vec3.class, candidate.getClass(), "candidate " + candidate);
        }
    }

    @Test
    void landingCarriesOnlyTheCollisionResponse() {
        PredVector velocity = new PredVector(new Vec3(0.1, -0.197271, 0.2));
        PredictionResult result = result(velocity, new Vec3(0.1, -0.05, 0.2), Vec3.ZERO);
        when(result.getCollideAxisData().getYNeg().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getYNeg().getResult()).thenReturn(-0.05);
        assertEquals(Set.of(new Vec3(0.1, 0, 0.2)), derive(result));
    }

    @Test
    void aPacketResidualDoesNotBecomeStoredVelocity() {
        PredVector velocity = new PredVector(new Vec3(0.1, -0.078, 0.2));
        Vec3 input = new Vec3(0.02, 0, 0);
        PredictionResult result = result(velocity, velocity, input);
        assertEquals(Set.of(velocity.add(input)), derive(result));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.0002, 0.0002})
    void aVerticalReportResidualIsNotHorizontalMoveRelativeInput(double reportY) {
        PredVector velocity = new PredVector(new Vec3(0.1, 0.035, 0.2));
        PredictionResult result = result(velocity, velocity, new Vec3(0.02, reportY, 0.01));
        assertEquals(Set.of(velocity.add(0.02, 0, 0.01)), derive(result));
    }

    @Test
    void aWallZeroesOnlyTheClippedAxis() {
        PredVector velocity = new PredVector(new Vec3(0.3, -0.078, 0.2));
        PredictionResult result = result(velocity, new Vec3(0.05, -0.078, 0.2), Vec3.ZERO);
        when(result.getCollideAxisData().getX().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getX().getResult()).thenReturn(0.05);
        assertEquals(Set.of(new Vec3(0, -0.078, 0.2)), derive(result));
    }

    @Test
    void aMoveEndingAtAWallMayHaveBeenALongerOneItClipped() {
        PredVector velocity = new PredVector(new Vec3(0.3, -0.078, 0.2));
        PredictionResult result = result(velocity, velocity, Vec3.ZERO);
        when(result.getCollideAxisData().getX().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getX().getResult()).thenReturn(0.3);
        assertEquals(Set.of(velocity, new Vec3(0, -0.078, 0.2)), derive(result));
    }

    @Test
    void anUnknownColliderLeavesBothClipOutcomes() {
        PredVector velocity = new PredVector(new Vec3(0.3, -0.078, 0.2));
        Vec3 moved = new Vec3(0.1, -0.078, 0.2);
        PredictionResult result = result(velocity, moved, Vec3.ZERO);
        when(result.getCollideAxisData().getX().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getUnknown()).thenReturn(List.of(new SimpleCollisionBox(1, 64, 0, 2, 65, 1)));
        assertEquals(Set.of(new Vec3(0, -0.078, 0.2), moved), derive(result));
    }

    @Test
    void anUnknownColliderLeavesBothVerticalOutcomes() {
        // The flag-5 tick: a carried fall the unknown piston boxes clamp to the move, or a free one.
        PredVector velocity = new PredVector(new Vec3(0.1, -0.155232, 0.2));
        Vec3 moved = new Vec3(0.1, -0.0784, 0.2);
        PredictionResult result = result(velocity, moved, Vec3.ZERO);
        when(result.getCollideAxisData().getYNeg().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getUnknown()).thenReturn(List.of(new SimpleCollisionBox(1, 63, 0, 2, 64, 1)));
        assertEquals(Set.of(new Vec3(0.1, 0, 0.2), moved), derive(result));
    }

    @Test
    void anUnclippedOutcomeIsTheMoveItselfWhateverTheTraceAttempted() {
        // Entity#move leaves an unclipped axis's deltaMovement as the move, even where the trace's carried velocity
        // and its pre-collision movement differ.
        PredVector velocity = new PredVector(new Vec3(-0.1348, -0.078, -0.1501));
        Vec3 moved = new Vec3(-0.1348, -0.078, -0.1584);
        PredictionResult result = result(velocity, moved, Vec3.ZERO);
        when(result.getValidMovements().getClosestTrace())
                .thenReturn(MovementTrace.start(velocity)
                        .withPosition(new PredVector(moved))
                        .withPreCollisionMovement(new Vec3(-0.1348, -0.078, -0.1420)));
        assertEquals(Set.of(new Vec3(-0.1348, -0.078, 0), moved), derive(result));
    }

    @Test
    void inputThatMovesAwayFromAWallIsNotClippedByIt() {
        // The carried velocity reaches the wall's limit, but moveRelative's input precedes Entity#collide and the
        // whole move ends away from the wall.
        PredVector velocity = new PredVector(new Vec3(-0.0586, -0.078, -0.135));
        Vec3 closest = new Vec3(-0.0424, -0.078, -0.135);
        Vec3 input = new Vec3(0.0482, 0, 0);
        PredictionResult result = result(velocity, closest, input);
        when(result.getCollideAxisData().getX().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getX().getResult()).thenReturn(-0.0424);
        Vec3 moved = closest.add(input);
        assertEquals(Set.of(new Vec3(0, -0.078, -0.135), moved), derive(result));
    }

    @Test
    void aFallAShoveCarriesPastAFloorMayHaveLandedOnIt() {
        // The flag's tick: the travel reaches the floor's limit and a shove makes up the rest, so the fall may have
        // stopped there instead.
        PredVector velocity = new PredVector(new Vec3(-0.094, -0.1746, 0.072));
        PredictionResult result = result(velocity, velocity, Vec3.ZERO);
        when(result.getCollideAxisData().getYNeg().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getYNeg().getResult()).thenReturn(0.0);
        assertEquals(Set.of(velocity, new Vec3(-0.094, 0, 0.072)), derive(result));
    }

    @Test
    void aNearbyProbeDoesNotZeroAnUnclippedMove() {
        PredVector velocity = new PredVector(new Vec3(0.3, -0.078, 0.2));
        PredictionResult result = result(velocity, velocity, Vec3.ZERO);
        // The probes find a wall and a floor near the move, but beyond what it reaches.
        when(result.getCollideAxisData().getX().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getX().getResult()).thenReturn(0.8);
        when(result.getCollideAxisData().getYNeg().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getYNeg().getResult()).thenReturn(-0.5);
        assertEquals(Set.of(velocity), derive(result));
    }

    @Test
    void inputSelectedInsideTheProbeEnvelopeIsCarriedBeforeCollisionResponse() {
        PredVector velocity = new PredVector(new Vec3(0.3, -0.078, 0.02));
        Vec3 moved = new Vec3(0.3, -0.078, 0.12);
        PredictionResult result = result(velocity, moved, Vec3.ZERO);
        when(result.getHorizontalInputRadius()).thenReturn(0.1);
        when(result.getCollideAxisData().getZ().getResult()).thenReturn(2.0);
        assertEquals(Set.of(moved), derive(result));
    }

    @Test
    void subtractingAbsolutePacketPositionsDoesNotInventAVerticalCollision() {
        // Entity#setPos adds this velocity to y=64; subtracting the positions loses one position ulp.
        PredVector velocity = new PredVector(new Vec3(0, -0.0784000015258789, 0));
        Vec3 position = new Vec3(0.5, 64.0 + velocity.y, 0.5);
        Vec3 rounded = new Vec3(0, position.y - 64.0, 0);
        PredictionResult result = result(velocity, rounded, Vec3.ZERO);
        when(result.getSimulationContext().getEnd()).thenReturn(position);
        assertEquals(Set.of(velocity), derive(result));
    }

    @Test
    void anExactColliderStillZeroesASubUlpVerticalContact() {
        PredVector velocity = new PredVector(new Vec3(0, -0.0784000015258789, 0));
        Vec3 clipped = new Vec3(0, Math.nextUp(velocity.y), 0);
        PredictionResult result = result(velocity, clipped, Vec3.ZERO);
        when(result.getSimulationContext().getEnd()).thenReturn(new Vec3(0.5, 64.0 + clipped.y, 0.5));
        when(result.getCollideAxisData().getYNeg().isLikelyCollide()).thenReturn(true);
        when(result.getCollideAxisData().getYNeg().getResult()).thenReturn(clipped.y);
        assertEquals(Set.of(Vec3.ZERO), derive(result));
    }

    @Test
    void aSuppressedSetPosStillCarriesUnclippedVelocity() {
        PredVector velocity = new PredVector(new Vec3(0.1, -0.0001, 0));
        PredVector clipped = velocity.withX(0, "collide x");
        PredictionResult result = result(velocity, Vec3.ZERO, Vec3.ZERO);
        MovementTrace trace =
                MovementTrace.start(velocity).withPosition(clipped.with(Vec3.ZERO, "Entity#move setPos guard"));
        when(result.getValidMovements().getClosestTrace()).thenReturn(trace);
        assertEquals(Set.of(new Vec3(0, -0.0001, 0)), derive(result));
    }

    @Test
    void aStepDoesNotCarryItsHeightAsVelocity() {
        PredVector velocity = new PredVector(new Vec3(0.1, -0.078, 0));
        PredVector stepped = velocity.withY(0.5, "step");
        PredictionResult result = result(velocity, stepped, Vec3.ZERO);
        when(result.getValidMovements().getClosestTrace())
                .thenReturn(MovementTrace.start(velocity).withPosition(stepped).withPreCollisionMovement(stepped));
        assertEquals(Set.of(new Vec3(0.1, 0, 0)), derive(result));
    }

    @Test
    void consumedStuckSpeedClearsStoredVelocity() {
        PredVector velocity = new PredVector(new Vec3(0.1, -0.078, 0.2));
        PredictionResult result = result(velocity, velocity.multiply(0.25, 0.05, 0.25), Vec3.ZERO);
        when(result.getSimulationContext().getLastStuckSpeed()).thenReturn(new Vec3(0.25, 0.05, 0.25));
        assertEquals(Set.of(Vec3.ZERO), derive(result));
    }

    @Test
    void aUnitStuckMultiplierStillConsumesStoredVelocity() {
        PredVector velocity = new PredVector(new Vec3(0.1, -0.078, 0.2));
        PredictionResult result = result(velocity, velocity, Vec3.ZERO);
        Vec3 unit = new Vec3(1, 1, 1);
        when(result.getSimulationContext().getLastStuckSpeed()).thenReturn(unit);
        when(result.getSimulationContext().getProfileCarry())
                .thenReturn(
                        new JavaPredictionCarry(null, 0, List.of(new JavaPredictionCarry.State(velocity, 0, unit))));
        assertEquals(Set.of(Vec3.ZERO), derive(result));
    }

    @Test
    void anOlderUnitStuckMultiplierStillConsumesStoredVelocity() {
        PredVector velocity = new PredVector(new Vec3(0.1, -0.078, 0.2));
        PredictionResult result = result(velocity, velocity, Vec3.ZERO);
        Vec3 unit = new Vec3(1, 1, 1);
        when(result.getSimulationContext().getLastStuckSpeed()).thenReturn(unit);
        PredictionResult previous = mock(PredictionResult.class, RETURNS_DEEP_STUBS);
        when(previous.getSimulationContext().getWorldData().getStuckSpeed().getStuckSpeedMultiplier())
                .thenReturn(unit);
        assertEquals(Set.of(Vec3.ZERO), derive(result, previous));
    }

    @Test
    void exemptPredictionsCarryTheirSimulatedTravel() {
        // An exemption skips the checks, not the travel the client ran: the packet's displacement is never velocity.
        PredVector velocity = new PredVector(new Vec3(0.1, -0.078, 0.2));
        PredictionResult result = result(velocity, velocity, Vec3.ZERO);
        when(result.isExempt()).thenReturn(true);
        assertEquals(Set.of(velocity), derive(result));
    }

    @Test
    void preRestitutionSlimeAndBedBouncesScaleNonLivingMovers() {
        assertEquals(
                0.8,
                VelocityCandidates.afterVerticalCollision(
                        null, BlockIds.SLIME_BLOCK.defaultState(), false, false, -1.0, 0.0),
                0.0);
        assertEquals(
                (double) 0.66F * 0.8,
                VelocityCandidates.afterVerticalCollision(
                        null,
                        DataTables.defaults()
                                .registry()
                                .block("minecraft:white_bed")
                                .defaultState(),
                        false,
                        false,
                        -1.0,
                        0.0),
                0.0);
        assertEquals(
                1.0,
                VelocityCandidates.afterVerticalCollision(
                        null, BlockIds.SLIME_BLOCK.defaultState(), false, true, -1.0, 0.0),
                0.0);
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_26_2", "V_26_3"})
    void aBoatsSlimeBounceMatchesTheCapturedClientLanding(ClientVersion version) {
        CultPlayer player = mock(CultPlayer.class);
        when(player.getClientVersion()).thenReturn(version);
        PacketEntity boat = new PacketEntity(EntityTypeIds.OAK_BOAT, 7);
        // Non-living boats use their overrides, independently of LivingEntity attributes.
        boat.gravity = 0.08;
        boat.airDragModifier = 1.0;
        double bounced = VelocityCandidates.afterVerticalCollision(
                player, boat, BlockIds.SLIME_BLOCK.defaultState(), false, false, true,
                -0.5599999999999999, -0.35999999999999943);
        assertEquals(0.4685714355536869, bounced, 1e-14);
        boat.hasGravity = false;
        double noGravity = VelocityCandidates.afterVerticalCollision(
                player, boat, BlockIds.SLIME_BLOCK.defaultState(), false, false, true,
                -0.5599999999999999, -0.35999999999999943);
        assertEquals(0.44800000667572015, noGravity, 1e-14);
    }

    private static Set<Vec3> derive(PredictionResult result) {
        return derive(result, null);
    }

    /** Every carried velocity, with or without a horizontal collision. */
    private static Set<Vec3> derive(PredictionResult result, PredictionResult previous) {
        Set<Vec3> collided = new HashSet<>();
        Set<Vec3> velocities = VelocityCandidates.derive(result, previous, new HashSet<>(), collided);
        velocities.addAll(collided);
        return velocities;
    }

    private static PredictionResult result(PredVector velocity, Vec3 moved, Vec3 input) {
        PredictionResult result = mock(PredictionResult.class, RETURNS_DEEP_STUBS);
        when(result.getPlayer()).thenReturn(null);
        when(result.getInitialStartingVel()).thenReturn(velocity);
        when(result.getProfilePredictionVector()).thenReturn(null);
        when(result.getValidMovements().getReplayedTravelInput()).thenReturn(null);
        when(result.getValidMovements().getReplayedClimbing()).thenReturn(null);
        when(result.getValidMovements().getClosestTrace())
                .thenReturn(MovementTrace.start(velocity).withPosition(new PredVector(moved)));
        when(result.getAcceptedClosestToTarget()).thenReturn(moved);
        when(result.getLegacyLikePredictionVector()).thenReturn(moved.add(input));
        when(result.getSimulationContext().getLastStuckSpeed()).thenReturn(null);
        when(result.getSimulationContext().getVehicle()).thenReturn(null);
        when(result.getSimulationContext().getWorldData().getOnBlock()).thenReturn(BlockIds.STONE.defaultState());
        when(result.getCollideAxisData().getUnknown()).thenReturn(List.of());
        return result;
    }
}
