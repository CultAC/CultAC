package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.*;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.*;
import org.junit.Test;

public final class BedrockSolidActorPushBufferTest {
    static final Vec3d FEET = new Vec3d(.5, 120, .5);

    static Vec3d axis(int a, double v) {
        return new Vec3d(a == 0 ? v : 0, a == 1 ? v : 0, a == 2 ? v : 0);
    }

    static double component(Vec3d v, int a) {
        return a == 0 ? v.x() : a == 1 ? v.y() : v.z();
    }

    static void check(boolean b, String message) {
        if (!b) throw new AssertionError(message);
    }

    static BedrockEntityMove.Result move(PlacedBlockCollision... shapes) {
        var current = BedrockMovementState.fromPhysicalFeet(
                FEET, Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
        var fetch = BedrockCollisionFetchBox.of(
                current.collisionBox(PlayerDimensionsState.DEFAULT),
                Vec3d.ZERO,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                current.coordinateFrame());
        return BedrockEntityMove.move(
                current,
                BedrockInputFrame.idle(1),
                new BlockCollisionWorld(List.of(shapes)),
                PlayerDimensionsState.DEFAULT,
                FEET,
                Vec3d.ZERO,
                0,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                false,
                fetch,
                0);
    }

    static PlacedBlockCollision actor(int axis, int sign, float depth, float buffer) {
        var player = BedrockCollisionSweep.playerBox(FEET, PlayerDimensionsState.DEFAULT);
        double[] low = {-2, 117, -2}, high = {3, 124, 3};
        double min = axis == 0 ? player.minX() : axis == 1 ? player.minY() : player.minZ();
        double max = axis == 0 ? player.maxX() : axis == 1 ? player.maxY() : player.maxZ();
        if (sign > 0) {
            high[axis] = (float) ((float) min + depth);
            low[axis] = high[axis] - 4;
        } else {
            low[axis] = (float) ((float) max - depth);
            high[axis] = low[axis] + 4;
        }
        return solid(new WorldCollisionBox(low[0], low[1], low[2], high[0], high[1], high[2]), buffer);
    }

    static PlacedBlockCollision solid(WorldCollisionBox box, float b) {
        return BedrockDepenetrationLimit.HAPPY_GHAST.solidActor(box, b);
    }

    @Test
    public void aCapturedStoppedBoatShoveCanUseItsPositionBudget() {
        Vec3d feet = new Vec3d(30.3763427734375, 160, 14.5), claimed = new Vec3d(30.373334884643555, 160, 14.5);
        float boatX = 31.476259231567383F;
        var shapes = new ArrayList<PlacedBlockCollision>();
        for (int x = 27; x <= 34; x++)
            for (int z = 12; z <= 16; z++)
                shapes.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 159, z),
                        "minecraft:stone",
                        "minecraft:stone",
                        List.of(new WorldCollisionBox(x, 159, z, x + 1, 160, z + 1))));
        shapes.add(BedrockDepenetrationLimit.SOLID_ACTOR.solidActor(
                new WorldCollisionBox(boatX - .8F, 160, 13.7F, boatX + .8F, 160F + .6F, 15.3F), .05F));
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(shapes)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var frame = BedrockInputFrame.idle(14218);
        var previous = BedrockMovementState.fromPhysicalFeet(
                feet,
                new Vec3d(0, -.07840000092983246, -7.567764515871119E-16),
                BedrockInputFrame.idle(14217),
                BedrockCollisionFlags.ON_GROUND);
        var candidates = BedrockForwardTick.simulate(new BedrockSimulation.Input(
                previous,
                frame,
                frame.intent(),
                BedrockWorldSnapshot.fromContext(context),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO));
        double best = Double.POSITIVE_INFINITY, charge = -1;
        for (var c : candidates) {
            var r = c.movementResult();
            var range = r.solidActorOrderRange();
            var selected = range == null ? r.predictedPosition() : range.closest(r.predictedPosition(), claimed);
            double error = selected.subtract(claimed).length();
            if (error < best) {
                best = error;
                charge = range == null ? 0 : range.buffered(r.predictedPosition(), claimed);
            }
        }
        if (best > 1e-6 || Math.abs(charge - .002925872802734375) > 1e-6)
            throw new AssertionError("captured buffered boat shove remains outside the range");
    }

    @Test
    public void aCapturedStationaryPlayerCanBeShovedByABufferedBoatPosition() {
        Vec3d feet = new Vec3d(30.375463485717773, 160, 14.5), claimed = new Vec3d(30.372594833374023, 160, 14.5);
        float boatX = 31.476259231567383F;
        var shapes = new ArrayList<PlacedBlockCollision>();
        for (int x = 27; x <= 34; x++)
            for (int z = 12; z <= 16; z++)
                shapes.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 159, z),
                        "minecraft:stone",
                        "minecraft:stone",
                        List.of(new WorldCollisionBox(x, 159, z, x + 1, 160, z + 1))));
        shapes.add(BedrockDepenetrationLimit.SOLID_ACTOR.solidActor(
                new WorldCollisionBox(boatX - .8F, 160, 13.7F, boatX + .8F, 160F + .6F, 15.3F), .05F));
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(shapes)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var frame = BedrockInputFrame.idle(1039);
        var previous = BedrockMovementState.fromPhysicalFeet(
                feet,
                new Vec3d(0, -.07840000092983246, -7.567764515871119E-16),
                BedrockInputFrame.idle(1038),
                BedrockCollisionFlags.ON_GROUND);
        var candidates = BedrockForwardTick.simulate(new BedrockSimulation.Input(
                previous,
                frame,
                frame.intent(),
                BedrockWorldSnapshot.fromContext(context),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO));
        double best = Double.POSITIVE_INFINITY, charge = -1;
        for (var c : candidates) {
            var r = c.movementResult();
            var range = r.solidActorOrderRange();
            var selected = range == null ? r.predictedPosition() : range.closest(r.predictedPosition(), claimed);
            double error = selected.subtract(claimed).length();
            if (error < best) {
                best = error;
                charge = range == null ? 0 : range.buffered(r.predictedPosition(), claimed);
            }
        }
        if (best > 1e-6 || Math.abs(charge - .003665924072265625) > 1e-6)
            throw new AssertionError("captured buffered boat shove remains outside the range");
    }

    private static List<BedrockSimulation.Candidate> stationaryCandidates(PlacedBlockCollision shape) {
        return candidatesWithVelocity(Vec3d.ZERO, shape);
    }

    private static List<BedrockSimulation.Candidate> candidatesWithVelocity(
            Vec3d velocity, PlacedBlockCollision shape) {
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(shape))),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var frame = BedrockInputFrame.idle(1);
        var previous = BedrockMovementState.fromPhysicalFeet(
                FEET, velocity, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
        return BedrockForwardTick.simulate(new BedrockSimulation.Input(
                previous,
                frame,
                frame.intent(),
                BedrockWorldSnapshot.fromContext(context),
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO));
    }

    @Test
    public void aBufferedActorCanShoveAStationaryPlayerInSixDirections() {
        for (int axis = 0; axis < 3; axis++)
            for (int sign : new int[] {-1, 1}) {
                Vec3d claimed = FEET.add(axis(axis, sign * .02D));
                boolean found = false;
                for (var candidate : stationaryCandidates(actor(axis, sign, -.01F, .05F))) {
                    var result = candidate.movementResult();
                    var range = result.solidActorOrderRange();
                    if (range == null
                            || range.closest(result.predictedPosition(), claimed)
                                            .subtract(claimed)
                                            .length()
                                    > 1e-6) continue;
                    check(
                            Math.abs(range.buffered(result.predictedPosition(), claimed) - .03D) < 2e-5,
                            "stationary shove must pay the separated gap and the resulting push");
                    var flags = result.predictedState().collisionFlags();
                    check(
                            axis == 0 ? flags.xCollision() : axis == 1 ? flags.verticalCollision() : flags.zCollision(),
                            "stationary shove must be derived from a real collision");
                    found = true;
                }
                check(found, "no stationary shove on axis " + axis + " direction " + sign);
            }
    }

    @Test
    public void aBufferedActorCanAlsoShoveAgainstAnExistingAwayMove() {
        for (int axis = 0; axis < 3; axis++)
            for (int sign : new int[] {-1, 1})
                for (double speed : new double[] {.01D, 1e-16D}) {
                    Vec3d velocity = axis(axis, sign * speed);
                    var actual = candidatesWithVelocity(velocity, actor(axis, sign, .02F, 0.0F))
                            .getFirst()
                            .movementResult();
                    boolean found = false;
                    for (var candidate : candidatesWithVelocity(velocity, actor(axis, sign, -.01F, .05F))) {
                        var result = candidate.movementResult();
                        var range = result.solidActorOrderRange();
                        if (range == null
                                || range.closest(result.predictedPosition(), actual.predictedPosition())
                                                .subtract(actual.predictedPosition())
                                                .length()
                                        > 1e-6) continue;
                        check(
                                result.predictedVelocity()
                                                .subtract(actual.predictedVelocity())
                                                .length()
                                        < 1e-6,
                                "translated actor must produce the same carried state as its ordinary overlap");
                        check(
                                Math.abs(range.buffered(result.predictedPosition(), actual.predictedPosition()) - .03D)
                                        < 2e-5,
                                "moving shove must pay the full actor translation");
                        found = true;
                    }
                    check(
                            found,
                            "no buffered away-move shove on axis " + axis + " direction " + sign + " speed " + speed);
                }
    }

    @Test
    public void stationaryShovesCannotBorrowWithoutAnEligibleNearbyActor() {
        check(
                stationaryCandidates(actor(0, 1, -.01F, 0.0F)).size() == 1,
                "stationary actor added a position candidate");
        check(
                stationaryCandidates(actor(0, 1, -.051F, .05F)).size() == 1,
                "actor outside the budget added a position candidate");
        check(
                stationaryCandidates(BedrockDepenetrationLimit.replayed(actor(0, 1, -.01F, .05F)))
                                .size()
                        == 1,
                "historical replay borrowed a live actor position");
    }

    @Test
    public void fullOverlapPushesUseTheSameBudgetInSixDirections() {
        for (int a = 0; a < 3; a++)
            for (int sign : new int[] {-1, 1}) {
                var end = move(actor(a, sign, .01F, .05F));
                var range = end.solidActorOrderRange();
                check(range != null, "no range " + a + "/" + sign);
                var claimed = end.position().add(axis(a, sign * .02));
                check(
                        range.closest(end.position(), claimed).subtract(claimed).length() < 1e-6,
                        "missing push " + a + "/" + sign);
                check(Math.abs(range.buffered(end.position(), claimed) - .02) < 1e-6, "charge " + a + "/" + sign);
            }
    }

    @Test
    public void pushesStayWithinTheOrdinaryDepenetrationLimit() {
        var deep = move(actor(0, 1, .09F, .05F));
        var selected = deep.solidActorOrderRange().closest(deep.position(), FEET.add(axis(0, .14)));
        check(selected.x() - FEET.x() <= .100001, "depenetration limit exceeded");
    }

    @Test
    public void pushesCannotChangeTheNearestExitAxis() {
        var thin = move(solid(new WorldCollisionBox(.21F - 4, 119, -2, .21F, 120.025F, 3), .05F));
        var selected = thin.solidActorOrderRange().closest(thin.position(), FEET.add(axis(0, .04)));
        check(selected.x() - FEET.x() <= (120.025F - 120.0F), "range changed the nearest exit axis");
    }

    @Test
    public void pushesCannotChangeTheNearestExitDirection() {
        var flip = move(solid(new WorldCollisionBox(-2, 120.84F, -2, 3, 120.94F, 3), .05F));
        var selected = flip.solidActorOrderRange().closest(flip.position(), FEET.add(axis(1, .98)));
        check(selected.y() - FEET.y() < .95001, "range changed the exit direction");
    }

    @Test
    public void fetchedBlocksStillClampBufferedPushes() {
        // The move only uses fetched blocks. This ceiling is inside the normal vertical fetch volume.
        var ceiling = PlacedBlockCollision.manual(
                new BlockPosition(0, 122, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(-2, 121.92F, -2, 3, 123, 3)));
        var stopped = move(ceiling, actor(1, 1, .11F, .05F));
        var selected = stopped.solidActorOrderRange().closest(stopped.position(), FEET.add(axis(1, .15)));
        check(selected.y() - FEET.y() <= .12001, "range crossed a fetched block");
    }

    @Test
    public void aStationaryActorCannotBorrowForItsPush() {
        check(move(actor(0, 1, .01F, 0)).solidActorOrderRange() == null, "stationary actor borrowed");
    }

    @Test
    public void pushesCannotExceedTheAvailablePool() {
        var little = move(actor(0, 1, .01F, .01F));
        check(
                little.solidActorOrderRange().buffered(little.position(), FEET.add(axis(0, .04))) <= .01000001,
                "available budget exceeded");
    }

    @Test
    public void pushesOnThreeAxesShareOneDistanceBudget() {
        var xyz = move(actor(0, 1, .01F, .05F), actor(1, 1, .01F, .05F), actor(2, 1, .01F, .05F));
        check(
                xyz.solidActorOrderRange()
                                .buffered(xyz.position(), xyz.position().add(new Vec3d(.04, .04, .04)))
                        <= .05000001,
                "shared XYZ budget exceeded");
    }
}
