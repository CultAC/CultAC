package ac.cult.cultac.checks.impl.prediction.stage;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.SpinRebound;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPush;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MovementModifiers {
    public static final String FLUID_HOP_REASON = "swim hop";
    public static final String SLIME_BLOCK_LAUNCH = "Slime Block";
    public static final String RIPTIDE_ATTACK = "riptide attack";
    public static final double FLUID_HOP_Y = (double) 0.3F;

    // Keep fluid-hop values centralized so Bedrock prediction and Java-shaped
    // starting-velocity candidates cannot drift through duplicated literals.
    private static final double RIDDEN_FLUID_HOP_ADDEND = (double) 0.04F;
    private static final double RIDDEN_FLUID_HOP_Y = FLUID_HOP_Y + RIDDEN_FLUID_HOP_ADDEND;

    public List<PredVector> applyModifers(
            CultPlayer player,
            Set<Vec3> initial,
            SimulationContext state,
            PredictionResult lastResult,
            boolean canTickSkip) {
        return applyModifers(player, initial, state, lastResult, canTickSkip, FluidHopMode.INCLUDE);
    }

    public List<PredVector> applyWithoutJavaFluidHop(
            CultPlayer player,
            Set<Vec3> initial,
            SimulationContext state,
            PredictionResult lastResult,
            boolean canTickSkip) {
        return applyModifers(player, initial, state, lastResult, canTickSkip, FluidHopMode.EXCLUDE);
    }

    public List<PredVector> applyBedrockModifiers(
            CultPlayer player,
            Set<Vec3> initial,
            SimulationContext state,
            PredictionResult lastResult,
            boolean canTickSkip) {
        List<PredVector> vectors =
                applyModifers(player, initial, state, lastResult, canTickSkip, FluidHopMode.EXCLUDE, false);
        TransactionVel receivedMotion = player.checkManager.getKnockbackHandler().secondBread;
        if (state.isBedrockTeleportTick() && receivedMotion != null) {
            // Travel is suppressed, so displacement cannot select the applied motion.
            // Its receipt proof makes the server velocity mandatory for carry instead.
            vectors.removeIf(vector -> !vector.hasPacketModifier(receivedMotion));
        }
        return vectors;
    }

    private List<PredVector> applyModifers(
            CultPlayer player,
            Set<Vec3> initial,
            SimulationContext state,
            PredictionResult lastResult,
            boolean canTickSkip,
            FluidHopMode fluidHopMode) {
        return applyModifers(player, initial, state, lastResult, canTickSkip, fluidHopMode, true);
    }

    private List<PredVector> applyModifers(
            CultPlayer player,
            Set<Vec3> initial,
            SimulationContext state,
            PredictionResult lastResult,
            boolean canTickSkip,
            FluidHopMode fluidHopMode,
            boolean includeSlimePistonLaunches) {
        List<PredVector> start = new ArrayList<>(initial.size() + 10);
        // A sneaking edge's carried deltaMovement starts a client whose CLIENT_TICK_END proves no skipped tick.
        if (lastResult != null && lastResult.isSneakEdgeCarried()) canTickSkip = false;

        // This happens on first join, good to keep in case other things do this too.
        if (initial.isEmpty()) {
            start.add(new PredVector(Vec3.ZERO));
        }

        boolean canStartWithFluidHop =
                fluidHopMode == FluidHopMode.INCLUDE && canStartWithFluidHop(state, lastResult, canTickSkip);
        boolean canStartWithRiddenFluidHopFloat = canStartWithRiddenFluidHopFloat(lastResult);
        Set<Vec3> unrebounded = state.getSpinRebound() == SpinRebound.ONLY ? Set.of() : initial;
        if (canStartWithFluidHop) {
            Map<Vec3, List<FluidHop>> committedHops = lastResult == null ? null : lastResult.getFluidHops();
            for (Vec3 vector : unrebounded) {
                List<FluidHop> hops = committedHops == null ? null : committedHops.get(vector);
                if (hops != null) {
                    for (FluidHop hop : hops) {
                        if (hop.blockedBox() != null && !isFree(player, hop.blockedBox(), hop.feetY())) continue;
                        Vec3 hopped = hop.velocity();
                        start.add(PredVector.fromStartingVector(vector)
                                .withXYZ(hopped.x, hopped.y, hopped.z, FLUID_HOP_REASON));
                    }
                    continue;
                }
                if (!mayJumpOutOfFluid(player, state, lastResult, vector)) continue;
                addFluidHopCandidates(start, PredVector.fromStartingVector(vector), canStartWithRiddenFluidHopFloat);
            }
        }

        if (lastResult != null
                && lastResult.getSimulationContext().getWorldData().isSpinAttackTarget()
                && state.getVehicle() == null
                && state.getSpinRebound() != SpinRebound.NONE) {
            for (Vec3 vector : initial) {
                PredVector riptideAttack = PredVector.fromStartingVector(vector).multiply(-0.2, RIPTIDE_ATTACK);
                start.add(riptideAttack);

                // MCP-Reborn Player#causeExtraKnockback damps attacker X/Z by 0.6
                // before LivingEntity#checkAutoSpinAttack applies the -0.2 rebound.
                if (lastResult.getSimulationContext().getWorldData().isSpinAttackPlayer()
                        && lastResult.getSimulationContext().getIsSprinting().determineOptimistically()) {
                    start.add(riptideAttack.withXYZ(
                            riptideAttack.x * 0.6, riptideAttack.y, riptideAttack.z * 0.6, "riptide sprint attack"));
                }
            }
        }

        // There may only be one first bread at a time.
        List<TransactionVel> packetEventsInOrder = List.of();
        TransactionVel knockbackFirst = player.checkManager.getKnockbackHandler().firstBread;
        TransactionVel explosionFirst = player.checkManager.getExplosionHandler().firstBread;
        TransactionVel knockbackSecond = player.checkManager.getKnockbackHandler().secondBread;
        TransactionVel explosionSecond = player.checkManager.getExplosionHandler().secondBread;
        if (knockbackFirst != null || explosionFirst != null || knockbackSecond != null || explosionSecond != null) {
            packetEventsInOrder = new ArrayList<>(4);
            if (knockbackFirst != null) packetEventsInOrder.add(knockbackFirst);
            if (explosionFirst != null) packetEventsInOrder.add(explosionFirst);
            if (knockbackSecond != null) packetEventsInOrder.add(knockbackSecond);
            if (explosionSecond != null) packetEventsInOrder.add(explosionSecond);
            if (state.isBedrockTeleportTick()) {
                // The outbound observer can see B + motion while A's reply is in flight.
                // Exclude that unconfirmed motion from A; firstBread retains it normally.
                TransactionVel possibleMotion = player.checkManager.getKnockbackHandler().firstBread;
                if (possibleMotion != null
                        && possibleMotion.getBedrockTeleportRevision()
                                > state.getBedrockTeleport().getBedrockTransportRevision()) {
                    packetEventsInOrder.removeIf(event -> event == possibleMotion);
                }
            }
            // ClientPacketListener applies replacement motion and additive explosions in wire order, even
            // when a single bundle ping proves all of them.
            packetEventsInOrder.sort(TransactionVel::compareClientOrder);
        }

        for (Vec3 vector : unrebounded) {
            start.add(PredVector.fromStartingVector(vector));
        }

        EntityPush.addVehiclePushCandidates(player, start, state, lastResult);
        addFireworkBoosts(player, start, state);

        // To prevent trying to add a million vectors, we limit the overridden vels
        List<TransactionVel> overriddenVels = player.checkManager.getKnockbackHandler().overriddenVels;
        for (int i = 0, size = overriddenVels.size(); i < size; i++) {
            TransactionVel order = overriddenVels.get(i);
            PredVector overriddenVel = new PredVector(order.getVel());
            overriddenVel.setTickSkip(canTickSkip);
            overriddenVel.setEntityMotion(true);
            start.add(overriddenVel);
        }

        if (canTickSkip) {
            PredVector pointThree = new PredVector(Vec3.ZERO);
            pointThree.setTickSkip(true);
            start.add(pointThree);

            if (canStartWithFluidHop) {
                addFluidHopCandidates(start, pointThree, canStartWithRiddenFluidHopFloat);
                for (Vec3 vector : unrebounded) {
                    PredVector skippedVector = PredVector.fromStartingVector(vector);
                    skippedVector.setTickSkip(true);
                    addFluidHopCandidates(start, skippedVector, canStartWithRiddenFluidHopFloat);
                }
            }
        }

        // TODO: This is too brute forcey, we don't need duplicates in certain situations.
        // TODO: This should just be refactored for 1.20.4+ clients with tick end and bundle packet!
        for (int eventIndex = 0, eventCount = packetEventsInOrder.size(); eventIndex < eventCount; eventIndex++) {
            TransactionVel vel = packetEventsInOrder.get(eventIndex);
            for (int i = 0, size = start.size(); i < size; i++) {
                PredVector vector = start.get(i);
                Vec3 eventVel = vel.getVel();

                if (vel.isVelocity()) { // Velocity
                    vector = vector.withXYZ(eventVel.x, eventVel.y, eventVel.z, "Velocity");
                    // ClientPacketListener#handleSetEntityMotion -> Entity#lerpMotion sets the whole delta movement.
                    vector.setTickSkip(false);
                } else { // Explosion
                    vector = vector.add(eventVel, "Explosion");
                }

                vector.addPacketModifier(vel);
                start.add(vector);
            }
        }

        // The legacy launch happened in the preceding block-entity pass,
        // before this tick's queued velocity/explosion packets were processed.
        if (includeSlimePistonLaunches) {
            addSlimePistonLaunches(start, state);
        }

        return start;
    }

    /**
     * FireworkRocketEntity#tick boosts the stored velocity after the last tick's LivingEntity#aiStep, with that
     * tick's look, so this tick's packets, attack and travel start from the boost. The travel may be a glide, or a
     * fluid or air travel once the server's fall flying flag arrives.
     */
    private static void addFireworkBoosts(CultPlayer player, List<PredVector> start, SimulationContext state) {
        int boosts = player.compensatedFireworks.getMaxFireworksAppliedPossible();
        if (boosts == 0 || state.getVehicle() != null) return;
        int certain = player.compensatedFireworks.getCertainFireworksApplied();
        List<PredVector> stored = new ArrayList<>(start);
        // Every rocket the client has applied and not yet removed boosted the stored velocity.
        if (certain > 0) start.clear();
        // The look reads either trig table, as the glide does.
        for (int fastMath = 0; fastMath < 2; fastMath++) {
            Vector3dm look = ReachUtils.getLook(player, state.getLastXRot(), state.getLastYRot());
            for (PredVector carried : stored) {
                Vec3 boosted = carried;
                for (int i = 1; i <= boosts; i++) {
                    boosted = ElytraTransform.boostFirework(boosted, look);
                    if (i >= certain) start.add(new ElytraTransform.Boosted(carried, boosted, i));
                }
            }
            player.trigHandler.toggleShitMath();
        }
    }

    private static void addSlimePistonLaunches(List<PredVector> start, SimulationContext state) {
        for (int i = 0, size = start.size(); i < size; i++) {
            PredVector vector = start.get(i);
            for (Direction direction : state.getWorldData().getPistonPushes().getSlimeBlockLaunches()) {
                double x = direction.getModX() == 0 ? vector.getX() : direction.getModX();
                double y = direction.getModY() == 0 ? vector.getY() : direction.getModY();
                double z = direction.getModZ() == 0 ? vector.getZ() : direction.getModZ();
                start.add(vector.withXYZ(x, y, z, SLIME_BLOCK_LAUNCH));
            }
        }
    }

    private static void addFluidHopCandidates(
            List<PredVector> start, PredVector vector, boolean canStartWithRiddenFluidHopFloat) {
        PredVector fluidHop = vector.withY(FLUID_HOP_Y, FLUID_HOP_REASON);
        start.add(fluidHop);
        if (canStartWithRiddenFluidHopFloat) {
            start.add(fluidHop.withY(RIDDEN_FLUID_HOP_Y, "ridden water float"));
        }
    }

    private boolean canStartWithFluidHop(SimulationContext state, PredictionResult lastResult, boolean canTickSkip) {
        if (state.getWorldData().isCanFluidHop()) {
            return true;
        }

        if (!canTickSkip || lastResult == null || lastResult.getSimulationContext() == null) {
            return false;
        }

        // MCP-Reborn LivingEntity#travelInWater/#travelInLava call jumpOutOfFluid after
        // movement, so the resulting 0.3 Y velocity is a carried start candidate for the
        // following simulated client tick. Tick-skipped packets can span that exact tick.
        return lastResult.getSimulationContext().getWorldData().isCanFluidHop()
                && lastResult.getSimulationContext().getWorldData().maybeInLiquid();
    }

    /**
     * A velocity the client may carry out of a move after LivingEntity#jumpOutOfFluid set its y, with the block
     * callbacks of that move applied after the hop. {@code blockedBox} is the Entity#isFree box that held a block or
     * fluid in the world of the hop's tick, which the hop still needs free, or null when nothing stops the hop.
     */
    public record FluidHop(Vec3 velocity, SimpleCollisionBox blockedBox, double feetY) {}

    /**
     * Whether the move of {@code result} may end in LivingEntity#jumpOutOfFluid: a Java water or lava travel that
     * collided horizontally, outside a boat.
     */
    public static boolean mayEndInFluidHop(CultPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        if (player.isBedrockMovement() || context == null || result.getCollideAxisData() == null) return false;
        WorldData world = context.getWorldData();
        return world != null
                && (world.getInWater().determineOptimistically()
                        || world.getInLava().determineOptimistically())
                && result.getCollideAxisData().couldCollideHorizontally()
                && (context.getVehicle() == null || !EntityTypeUtil.isBoat(context.getVehicle().type));
    }

    /**
     * The y values the move of {@code result} leaves after LivingEntity#jumpOutOfFluid: 0.3, and 0.34 once
     * LivingEntity#floatInLiquidWhileRidden adds to it.
     */
    public static double[] fluidHopYs(PredictionResult result) {
        return result.getSimulationContext().getWorldData().isCouldFloatWhileRidden()
                ? new double[] {FLUID_HOP_Y, RIDDEN_FLUID_HOP_Y}
                : new double[] {FLUID_HOP_Y};
    }

    /**
     * LivingEntity#jumpOutOfFluid's Entity#isFree box for a player whose move of {@code context} ended at {@code end}
     * with {@code movement} after travel: the pose box there moved by {@code movement} and raised 0.6 above where the
     * move started; null when it is free in the current world, and always for a vehicle.
     */
    public static SimpleCollisionBox blockedFluidHopBox(
            CultPlayer player, SimulationContext context, Vec3 end, Vec3 movement) {
        if (context.getVehicle() != null) return null;
        SimpleCollisionBox box = fluidHopBox(context, end, movement);
        return isFree(player, box, end.y) ? null : box;
    }

    private static SimpleCollisionBox fluidHopBox(SimulationContext context, Vec3 end, Vec3 movement) {
        float scale = context.getScale();
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                        end.x,
                        end.y,
                        end.z,
                        context.getPose().width * scale,
                        context.getPose().height * scale)
                .offset(movement.x, movement.y + 0.6F - end.y + context.getStart().y, movement.z);
    }

    /** Entity#isFree: no block collides with {@code box} and it holds no fluid. */
    private static boolean isFree(CultPlayer player, SimpleCollisionBox box, double feetY) {
        return Collisions.isEmpty(player, box, feetY) && !containsAnyFluid(player, box);
    }

    /**
     * LivingEntity#jumpOutOfFluid's Entity#isFree for a carried velocity no commit resolved the hop for, against the
     * current world.
     */
    private static boolean mayJumpOutOfFluid(
            CultPlayer player, SimulationContext state, PredictionResult lastResult, Vec3 movement) {
        if (player.isBedrockMovement()
                || state.getVehicle() != null
                || !state.getWorldData().isCanFluidHop()
                || lastResult == null
                || lastResult.getSimulationContext() == null) return true;
        SimulationContext last = lastResult.getSimulationContext();
        return isFree(player, fluidHopBox(last, last.getEnd(), movement), last.getEnd().y);
    }

    /** LevelReader#containsAnyLiquid over the blocks from floor(min) to below ceil(max) on each axis. */
    private static boolean containsAnyFluid(CultPlayer player, SimpleCollisionBox box) {
        int maxX = CultMath.ceil(box.maxX), maxY = CultMath.ceil(box.maxY), maxZ = CultMath.ceil(box.maxZ);
        for (int x = CultMath.floor(box.minX); x < maxX; x++) {
            for (int y = CultMath.floor(box.minY); y < maxY; y++) {
                for (int z = CultMath.floor(box.minZ); z < maxZ; z++) {
                    int block = player.compensatedWorld.getBlockStateIdAt(x, y, z);
                    if (!ClientFluidQueries.modelFluid(block).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private boolean canStartWithRiddenFluidHopFloat(PredictionResult lastResult) {
        if (lastResult == null || lastResult.getSimulationContext() == null) {
            return false;
        }

        return lastResult.getSimulationContext().getWorldData() != null
                && lastResult.getSimulationContext().getWorldData().isCouldFloatWhileRidden();
    }

    private enum FluidHopMode {
        INCLUDE,
        EXCLUDE
    }
}
