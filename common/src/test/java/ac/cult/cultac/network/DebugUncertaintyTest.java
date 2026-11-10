package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.command.IsolatedCommandManager;
import ac.cult.cultac.command.commands.CultDebugUncertainty;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DebugUncertaintyTest {
    @Test
    void zeroAllowancePreservesTheExactTrace() {
        ValidMovements valid = movement(Vec3.ZERO, new Vec3(1, -1, 1), new Vec3(1, 1, 1));
        var before = valid.getClosestTrace();
        valid.applyDebugUncertainty();
        assertSame(before, valid.getClosestTrace());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void allowanceIsBoundedIndependentlyOnEachAxis(int sign) {
        ValidMovements valid =
                movement(new Vec3(0.1, 0.2, 0.3), new Vec3(sign * 0.4, sign * 0.4, sign * 0.4), new Vec3(1, 1, 1));
        var before = valid.getClosestTrace();
        valid.applyDebugUncertainty();
        assertEquals(sign * 0.1, valid.getClosestToTarget().x, 1e-12);
        assertEquals(sign * 0.2, valid.getClosestToTarget().y, 1e-12);
        assertEquals(sign * 0.3, valid.getClosestToTarget().z, 1e-12);
        assertSame(before.preCollisionVelocity(), valid.getClosestTrace().preCollisionVelocity());
        assertSame(before.preCollisionMovement(), valid.getClosestTrace().preCollisionMovement());
    }

    @Test
    void valuesRemainPhysicalBlocksWhenStuckSpeedScalesPredictionCoordinates() {
        ValidMovements valid = movement(new Vec3(0.1, 0.2, 0), new Vec3(1, -0.25, 1), new Vec3(0.25, 0.5, 0.25));
        valid.applyDebugUncertainty();
        assertEquals(0.4, valid.getClosestToTarget().x, 1e-12);
        assertEquals(-0.25, valid.getClosestToTarget().y, 1e-12);
        assertEquals(0, valid.getClosestToTarget().z, 0);
        assertEquals(new Vec3(0.1, -0.125, 0), valid.getPositionOnlyDelta());
        assertTrue(valid.getClosestTrace().position().hasReason("debug uncertainty"));
    }

    @Test
    void commandSetsTheSenderAndRejectsInvalidValuesAndMissingPermission() throws Exception {
        try (var f = new JavaTickFixture();
                var online = online(f)) {
            Sender sender = sender(f);
            var manager = manager();
            execute(manager, sender, "0.1 0.2 0.3");
            assertEquals(new Vec3(0.1, 0.2, 0.3), f.player.debugUncertainty);
            assertEquals(100.0D, f.player.debugUncertaintyChancePercent);
            Vec3 set = f.player.debugUncertainty;
            for (String invalid : List.of(
                    "-1 0 0",
                    "0 -1 0",
                    "0 0 -1",
                    "NaN 0 0",
                    "0 Infinity 0",
                    "0 0 1e999",
                    "0.1 0.2 0.3 -1",
                    "0.1 0.2 0.3 101",
                    "0.1 0.2 0.3 NaN",
                    "0.1 0.2 0.3 Infinity")) {
                manager.commandExecutor()
                        .executeCommand(sender, "cult debug uncertainty " + invalid)
                        .handle((result, error) -> null)
                        .join();
                assertSame(set, f.player.debugUncertainty, invalid);
                assertEquals(100.0D, f.player.debugUncertaintyChancePercent, invalid);
            }
            when(sender.hasPermission("cult.debug")).thenReturn(false);
            assertThrows(RuntimeException.class, () -> execute(manager, sender, "reset"));
            assertSame(set, f.player.debugUncertainty);
            when(sender.hasPermission("cult.debug")).thenReturn(true);
            execute(manager, sender, "0.1 0.2 0.3 12.5");
            assertEquals(12.5D, f.player.debugUncertaintyChancePercent);
            execute(manager, sender, "reset");
            assertSame(Vec3.ZERO, f.player.debugUncertainty);
            assertEquals(100.0D, f.player.debugUncertaintyChancePercent);
        }
        try (var reconnected = new JavaTickFixture()) {
            assertSame(Vec3.ZERO, reconnected.player.debugUncertainty);
            assertEquals(100.0D, reconnected.player.debugUncertaintyChancePercent);
        }
    }

    @Test
    void actualPredictionUsesTheAllowanceAndResetRestoresTheOriginalOffset() throws Exception {
        double baseline = offset(null);
        assertTrue(baseline > 0.01, "expected a measurable baseline offset, got " + baseline);
        assertEquals(0, offset("0.05 0 0"), 1e-5);
        assertEquals(0, offset("0.05 0 0 100"), 1e-5);
        assertEquals(baseline, offset("0.05 0 0 0"), 1e-12);
        assertEquals(baseline, offset("reset"), 1e-12);
    }

    @Test
    void retriesOfOneClientTickShareTheSameRandomChoice() throws Exception {
        try (var f = new JavaTickFixture();
                var online = online(f)) {
            Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
            Vec3 delta = new Vec3(0.04, 0, 0);
            f.place(start, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            execute(manager(), sender(f), "0.05 0 0 50");
            var runner = f.player.checkManager.getSimulationProcessor();
            Double first = null;
            for (int attempt = 0; attempt < 32; attempt++) {
                PredictionResult result = runner.getPredictionResult(
                        f.player, Set.of(Vec3.ZERO), null, delta, start, start.add(delta), false, 0, 0);
                if (first == null) first = result.getOffset();
                assertEquals(first.doubleValue(), result.getOffset(), 0);
                boolean applied = allowanceRan(result);
                for (PredictionResult candidate : result.getAnyReality())
                    assertEquals(applied, allowanceRan(candidate));
            }
        }
    }

    /**
     * The engine travels 0.1 X in the air, the client reports 0.13 X and a 0.05 X allowance covers the gap. Whether
     * the gap was a shove (next X 0.1 * 0.91F) or deltaMovement (0.13 * 0.91F), or part of each, the next tick fits
     * exactly with the allowance off; a next move past both readings still flags.
     */
    @ParameterizedTest
    @ValueSource(doubles = {0.1, 0.115, 0.13, 0.16})
    void aGapReadAsPositionOrVelocityCarriesIntoTheNextTick(double travelledVelocity) throws Exception {
        try (var f = new JavaTickFixture();
                var online = online(f)) {
            Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y + 10, 0.5);
            f.place(start, new Vec3(0.1, 0, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var manager = manager();
            Sender sender = sender(f);
            execute(manager, sender, "0.05 0 0");
            Vec3 first = start.add(0.13, 0, 0);
            PredictionResult tick = f.tick(first, false);
            assertTrue(JavaTickFixture.exact(tick), JavaTickFixture.describe(tick));
            // The shove reading needed 0.03 of allowance; the velocity reading lands on the report and wins.
            var family = tick.getValidMovements().debugFamily();
            assertEquals(2, family.size());
            assertEquals(0.03, family.getFirst().getValidMovements().getDebugAllowance().x, 1e-12);
            assertSame(family.get(1), tick);
            assertEquals(Vec3.ZERO, tick.getValidMovements().getDebugAllowance());
            var carry = (ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry)
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
            for (var state : carry.states()) {
                var range = ((PredVector) state.velocity()).getVelocityRange();
                assertNotNull(range, "the carry kept no range");
                assertEquals(0.1 * 0.91F, state.velocity().x + range.minX, 1e-9);
                assertEquals(0.13 * 0.91F, state.velocity().x + range.maxX, 1e-9);
                assertEquals(range.minY, range.maxY, 0);
            }

            execute(manager, sender, "reset");
            Vec3 second = first.add(travelledVelocity * 0.91F, -0.08 * 0.98F, 0);
            PredictionResult next = f.tick(second, false);
            if (travelledVelocity <= 0.13) assertTrue(JavaTickFixture.exact(next), JavaTickFixture.describe(next));
            else assertTrue(next.getOffset() > 0.01, JavaTickFixture.describe(next));

            // A tick that fits pins the velocity again.
            var pinned = (ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry)
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
            if (travelledVelocity <= 0.13)
                for (var state : pinned.states())
                    assertTrue(!(state.velocity() instanceof PredVector vector) || vector.getVelocityRange() == null);
        }
    }

    /**
     * A slime landing hides deltaMovement: the engine falls at 0.5, the client at 0.4, and both land on the same spot,
     * so the report shows no gap. Slime reflects the fall, so the bounce carries the difference into the next tick. The
     * allowance's velocity readings carry every bounce its reach allows; without the allowance the next tick flags.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSlimeLandingCarriesTheBouncesTheAllowanceAllows(boolean allowance) throws Exception {
        try (var f = new JavaTickFixture();
                var online = online(f)) {
            for (int x = -3; x <= 3; x++)
                for (int z = -3; z <= 3; z++)
                    f.player.compensatedWorld.updateBlock(
                            new ac.cult.cultac.protocol.value.BlockPos(x, JavaTickFixture.FLOOR_Y - 1, z),
                            ac.cult.blocksim.data.BlockIds.SLIME_BLOCK.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y + 0.2, 0.5);
            f.place(start, new Vec3(0, -0.5, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            execute(manager(), sender(f), allowance ? "0 0.2 0" : "0 0.2 0 0");
            Vec3 landed = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
            PredictionResult landing = f.tick(landed, true);
            assertTrue(JavaTickFixture.exact(landing), JavaTickFixture.describe(landing));

            execute(manager(), sender(f), "reset");
            PredictionResult bounce = f.tick(landed.add(0, (0.4 - 0.08) * 0.98F, 0), false);
            if (allowance) assertTrue(JavaTickFixture.exact(bounce), JavaTickFixture.describe(bounce));
            else assertTrue(bounce.getOffset() > 0.05, JavaTickFixture.describe(bounce));
        }
    }

    /**
     * A riptide spin hits a player while the allowance covers a gap in its travel: the client spun 0.1 X faster. Each
     * reading rebounds in LivingEntity#checkAutoSpinAttack, with and without Player#causeExtraKnockback's sprint
     * damping, before the target's later Entity#push, which follows the report before 26.3. The carry keeps every
     * rebound the readings leave, so the next tick fits exactly with the allowance off.
     */
    @ParameterizedTest(name = "{0} sprint={2}")
    @org.junit.jupiter.params.provider.MethodSource("riptideHits")
    void aRiptideHitCarriesEveryReboundTheAllowanceLeaves(
            String release, ac.cult.cultac.network.protocol.ClientVersion version, boolean sprint) throws Exception {
        try (var f = new JavaTickFixture();
                var online = online(f)) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(-4.5, 80, .5);
            Vec3 spin = new Vec3(1.5, 0, 0);
            Vec3 target = start.add(1, -1, 0);
            f.addRemotePlayer(71, target, ac.cult.cultac.protocol.value.GameMode.SURVIVAL);
            f.place(start, spin);
            f.player.riptideSpinAttackTicks = 10;
            f.player.riptideSpinCertain = true;
            f.player.isSprinting = false;
            f.input(new ServerboundPlayerInput(sprint, false, false, false, false, false, sprint));
            var manager = manager();
            Sender sender = sender(f);
            execute(manager, sender, "0.2 0 0");
            Vec3 input = new Vec3(0, 0, sprint ? (double) 0.98F * 0.025999999F : 0);
            Vec3 movement = spin.add(0.1, 0, 0).add(input);
            Vec3 position = start.add(movement);
            PredictionResult hit = f.tick(position, false);
            assertTrue(JavaTickFixture.exact(hit), JavaTickFixture.describe(hit));
            Vec3 travelled = JavaRiptideCarryOrderTest.drag(movement);
            Vec3 rebound = sprint ? travelled.multiply(.6, 1, .6).scale(-.2) : travelled.scale(-.2);
            Vec3 velocity = rebound.add(JavaRiptideCarryOrderTest.push(target, position));

            execute(manager, sender, "reset");
            if (sprint) {
                var command = new ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand(
                        f.player.entityID, ac.cult.cultac.protocol.value.PlayerCommandAction.START_SPRINTING, 0);
                new ac.cult.cultac.events.packets.listeners.PacketEntityAction()
                        .onPlayerCommand(
                                f.event(ac.cult.cultac.protocol.packet.ServerboundPackets.PLAYER_COMMAND, command),
                                f.player,
                                command);
            }
            position = position.add(
                    JavaRiptideCarryOrderTest.threshold(version, velocity).add(input));
            PredictionResult next = f.tick(position, false);
            assertTrue(JavaTickFixture.exact(next), JavaTickFixture.describe(next));
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> riptideHits() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (var version : List.of(
                ac.cult.cultac.network.protocol.ClientVersion.V_1_21_11,
                ac.cult.cultac.network.protocol.ClientVersion.V_26_3))
            for (boolean sprint : new boolean[] {false, true})
                cases.add(org.junit.jupiter.params.provider.Arguments.of(version.toString(), version, sprint));
        return cases.stream();
    }

    /**
     * Entity#updateFluidHeightAndDoFluidPushing raises a weak current to 0.0045 only while X and Z are both under
     * 0.003. A range across that square splits into parts that each lie on one side and together hold the range.
     */
    @Test
    void aRangeSplitsWhereTheMinimumFluidPushStarts() {
        var range = ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox.between(
                -0.408, -0.01, -0.16, 0.05, 0, 0.13);
        PredVector ranged = new PredVector(new Vec3(0.408, -0.04, 0)).withVelocityRange(range);
        var parts = ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVelocityRange.splitAtSlowSquare(ranged);
        // X never goes below -0.003: the part past +0.003, the two Z strips beside the square and the square.
        assertEquals(4, parts.size());
        var random = new java.util.Random(3);
        for (int i = 0; i < 4000; i++) {
            Vec3 point = new Vec3(
                    ranged.x + range.minX + random.nextDouble() * (range.maxX - range.minX),
                    ranged.y,
                    ranged.z + range.minZ + random.nextDouble() * (range.maxZ - range.minZ));
            // Many points near the square's edge.
            if (i % 4 == 0) point = new Vec3(random.nextDouble() * 0.006, point.y, random.nextDouble() * 0.006 - 0.003);
            boolean slow = Math.abs(point.x) < 0.003 && Math.abs(point.z) < 0.003;
            int holding = 0;
            for (PredVector part : parts) {
                var r = part.getVelocityRange();
                if (point.x < part.x + r.minX
                        || point.x > part.x + r.maxX
                        || point.z < part.z + r.minZ
                        || point.z > part.z + r.maxZ) continue;
                holding++;
                boolean partSlow = Math.abs(part.x) < 0.003 && Math.abs(part.z) < 0.003;
                assertEquals(slow, partSlow, point + " in " + part + " " + r);
            }
            assertEquals(1, holding, point.toString());
        }
        // A range on one side stays whole.
        var small = ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox.between(
                -0.001, 0, -0.001, 0.001, 0, 0.001);
        for (Vec3 at : List.of(new Vec3(0.5, 0, 0.5), Vec3.ZERO)) {
            PredVector whole = new PredVector(at).withVelocityRange(small);
            assertSame(
                    whole,
                    ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVelocityRange.splitAtSlowSquare(whole)
                            .getFirst());
        }
    }

    /**
     * Entity#collide moves Y, then the larger of X and Z. A block beside the player's Z path stops an X move only once
     * the Z move has put the box in line with it: the X range's collision switch is found only in that order.
     */
    @Test
    void aWallTheOtherHorizontalMoveBringsInLineIsInsideTheRange() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(
                    new ac.cult.cultac.protocol.value.BlockPos(-1, JavaTickFixture.FLOOR_Y, 1),
                    ac.cult.blocksim.data.BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            SimulationContext context = mock(SimulationContext.class);
            when(context.getFromActualPose())
                    .thenReturn(new ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox(
                            0.2, JavaTickFixture.FLOOR_Y, 0.2, 0.8, JavaTickFixture.FLOOR_Y + 1.8, 0.8));
            when(context.getLastStuckSpeed()).thenReturn(new Vec3(1, 1, 1));
            Vec3 value = new Vec3(-0.3, 0, 0.5);
            var range = ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVelocityRange.class;
            assertNull(ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVelocityRange.collisionInside(
                    f.player, context, 0, -0.5, 0.1, value, false));
            double[] wall = ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVelocityRange.collisionInside(
                    f.player, context, 0, -0.5, 0.1, value, true);
            assertNotNull(wall, range.getSimpleName());
            assertEquals(-0.2, wall[0], 1e-9);
            // Entity#move zeroes X only once the clip passes Mth.equal's 1.0E-5F.
            assertTrue(wall[0] - wall[1] > 1.0E-5F, wall[0] + " " + wall[1]);
        }
    }

    /** With the allowance on but skipped this tick, nothing extra is carried. */
    @Test
    void aTickWithoutTheAllowanceCarriesNoRange() throws Exception {
        try (var f = new JavaTickFixture();
                var online = online(f)) {
            Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y + 10, 0.5);
            f.place(start, new Vec3(0.1, 0, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            execute(manager(), sender(f), "0.05 0 0 0");
            PredictionResult tick = f.tick(start.add(0.13, 0, 0), false);
            assertTrue(tick.getOffset() > 0.02, JavaTickFixture.describe(tick));
            assertEquals(Vec3.ZERO, tick.getValidMovements().getDebugAllowance());
        }
    }

    /** Scaling and climbable clamps map every velocity of a range, and only those, into the new range. */
    @Test
    void scaledAndClampedRangesHoldExactlyTheMappedVelocities() {
        var random = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            Vec3 value =
                    new Vec3(random.nextGaussian() * 0.3, random.nextGaussian() * 0.3, random.nextGaussian() * 0.3);
            double[] a = {random.nextGaussian() * 0.2, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2};
            double[] b = {random.nextGaussian() * 0.2, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2};
            var box = ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox.between(
                    a[0], a[1], a[2], b[0], b[1], b[2]);
            PredVector ranged = new PredVector(value).withVelocityRange(box);
            double scale = random.nextGaussian();
            PredVector scaled = ranged.multiply(scale, "test");
            Vec3 low = new Vec3(-0.15, random.nextBoolean() ? 0 : -0.15, -0.15);
            Vec3 high = new Vec3(0.15, 1.0E9, 0.15);
            PredVector clamped = ranged.clamped(low, high, "test");
            for (int sample = 0; sample < 8; sample++) {
                double t = random.nextDouble();
                Vec3 point = new Vec3(
                        value.x + box.minX + t * (box.maxX - box.minX),
                        value.y + box.minY + t * (box.maxY - box.minY),
                        value.z + box.minZ + t * (box.maxZ - box.minZ));
                assertInside(point.scale(scale), scaled);
                assertInside(
                        new Vec3(
                                Math.max(low.x, Math.min(high.x, point.x)),
                                Math.max(low.y, Math.min(high.y, point.y)),
                                Math.max(low.z, Math.min(high.z, point.z))),
                        clamped);
            }
            // The range is no wider than the mapped ends.
            var scaledRange = scaled.getVelocityRange();
            assertEquals(Math.abs(scale) * (box.maxX - box.minX), scaledRange.maxX - scaledRange.minX, 1e-12);
        }
    }

    /** A range through LivingEntity#updateFallFlyingMovement holds the glide of every velocity inside it. */
    @Test
    void aGlidedRangeHoldsTheGlideOfEveryVelocityInIt() {
        var random = new java.util.Random(11);
        for (int i = 0; i < 2000; i++) {
            float pitch = (float) (random.nextDouble() * 80);
            double yaw = Math.toRadians(random.nextDouble() * 360);
            var look = new ac.cult.cultac.utils.math.Vector3dm(
                    -Math.sin(yaw) * Math.cos(Math.toRadians(pitch)),
                    -Math.sin(Math.toRadians(pitch)),
                    Math.cos(yaw) * Math.cos(Math.toRadians(pitch)));
            java.util.function.UnaryOperator<Vec3> glide =
                    v -> ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform.getElytraMovement(
                            null, v, look, pitch, 0.08);
            Vec3 value =
                    new Vec3(random.nextGaussian() * 0.5, random.nextGaussian() * 0.5, random.nextGaussian() * 0.5);
            double[] a = {random.nextGaussian() * 0.2, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2};
            double[] b = {random.nextGaussian() * 0.2, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2};
            var box = ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox.between(
                    a[0], a[1], a[2], b[0], b[1], b[2]);
            PredVector ranged = new PredVector(value).withVelocityRange(box);
            PredVector glided = ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVelocityRange.mapped(
                    ranged, ranged.with(glide.apply(value), "glide"), glide);
            for (int sample = 0; sample < 16; sample++) {
                Vec3 point = new Vec3(
                        value.x + box.minX + random.nextDouble() * (box.maxX - box.minX),
                        value.y + box.minY + random.nextDouble() * (box.maxY - box.minY),
                        value.z + box.minZ + random.nextDouble() * (box.maxZ - box.minZ));
                assertInside(glide.apply(point), glided);
            }
        }
    }

    /** A fall distance range is one state; only a range across powder snow's 2.5 can move differently. */
    @Test
    void aFallDistanceRangeStaysOneStateUnlessItCrossesPowderSnowsThreshold() {
        var state =
                new ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry.State(Vec3.ZERO, 1.0, null);
        assertFalse(state.hasFallDistanceRange());
        var ranged = state.withFallDistance(1.0, 2.0);
        assertTrue(ranged.hasFallDistanceRange());
        assertFalse(ranged.fallRangeMovesDifferently());
        assertTrue(state.withFallDistance(2.0, 3.0).fallRangeMovesDifferently());
        assertFalse(state.withFallDistance(6.0, 9.0).fallRangeMovesDifferently());
        // Copies keep the range; a high end below the low end is the low end.
        assertEquals(2.0, ranged.withVelocity(new Vec3(1, 0, 0)).fallDistanceMax());
        assertEquals(1.0, state.withFallDistance(1.0, 0.5).fallDistanceMax());
    }

    private static void assertInside(Vec3 velocity, PredVector ranged) {
        var range = ranged.getVelocityRange();
        double e = 1e-12;
        if (range == null) {
            assertEquals(ranged.x, velocity.x, e);
            assertEquals(ranged.y, velocity.y, e);
            assertEquals(ranged.z, velocity.z, e);
            return;
        }
        assertTrue(velocity.x >= ranged.x + range.minX - e && velocity.x <= ranged.x + range.maxX + e);
        assertTrue(velocity.y >= ranged.y + range.minY - e && velocity.y <= ranged.y + range.maxY + e);
        assertTrue(velocity.z >= ranged.z + range.minZ - e && velocity.z <= ranged.z + range.maxZ + e);
    }

    /** The allowance moved the candidate, or the candidate is one of its velocity readings. */
    private static boolean allowanceRan(PredictionResult candidate) {
        var valid = candidate.getValidMovements();
        return valid.getClosestTrace().position().hasReason("debug uncertainty")
                || !valid.debugFamily().isEmpty();
    }

    private static double offset(String command) throws Exception {
        try (var f = new JavaTickFixture();
                var online = online(f)) {
            Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            if (command != null) {
                var manager = manager();
                Sender sender = sender(f);
                execute(manager, sender, "0.05 0 0");
                execute(manager, sender, command);
            }
            return f.tick(start.add(0.04, 0, 0), true).getOffset();
        }
    }

    private static ValidMovements movement(Vec3 radius, Vec3 target, Vec3 stuck) {
        CultPlayer player = mock(CultPlayer.class);
        player.debugUncertainty = radius;
        SimulationContext context = mock(SimulationContext.class);
        when(context.getLastStuckSpeed()).thenReturn(stuck);
        PredVector start = new PredVector(Vec3.ZERO);
        PredictionResult result = new PredictionResult(player, start, context, target, null, List.of(), List.of());
        return new ValidMovements(start, player, result, false, List.of()).computeClosest(null);
    }

    private static IsolatedCommandManager manager() {
        var manager = new IsolatedCommandManager();
        new CultDebugUncertainty().register(manager, null);
        return manager;
    }

    /** The packet fixture bypasses login; publish its account for the command's UUID lookup. */
    @SuppressWarnings("unchecked")
    private static AutoCloseable online(JavaTickFixture fixture) throws ReflectiveOperationException {
        var manager = CultAPI.INSTANCE.getNetworkManager();
        var connectionsField = CultNetworkManager.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        var connections = connectionsField.get(manager);
        var indexField = connections.getClass().getDeclaredField("currentByUuid");
        indexField.setAccessible(true);
        var index = (Map<UUID, CultConnection>) indexField.get(connections);
        index.put(fixture.player.playerUUID, fixture.fixture.connection);
        return () -> index.remove(fixture.player.playerUUID, fixture.fixture.connection);
    }

    private static Sender sender(JavaTickFixture fixture) {
        Sender sender = mock(Sender.class);
        when(sender.getPlatformPlayer()).thenReturn(fixture.player.user.getPlayer());
        when(sender.getUniqueId()).thenReturn(fixture.player.playerUUID);
        when(sender.hasPermission("cult.debug")).thenReturn(true);
        return sender;
    }

    private static void execute(IsolatedCommandManager manager, Sender sender, String arguments) {
        manager.commandExecutor()
                .executeCommand(sender, "cult debug uncertainty " + arguments)
                .join();
    }
}
