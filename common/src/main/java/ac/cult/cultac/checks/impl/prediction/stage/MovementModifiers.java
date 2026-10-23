package ac.cult.cultac.checks.impl.prediction.stage;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.SpinRebound;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPush;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public List<PredVector> applyWithoutFluidHop(
            CultPlayer player,
            Set<Vec3> initial,
            SimulationContext state,
            PredictionResult lastResult,
            boolean canTickSkip) {
        return applyWithoutJavaFluidHop(player, initial, state, lastResult, canTickSkip);
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

        // This happens on first join, good to keep in case other things do this too.
        if (initial.isEmpty()) {
            start.add(new PredVector(Vec3.ZERO));
        }

        boolean canStartWithFluidHop =
                fluidHopMode == FluidHopMode.INCLUDE && canStartWithFluidHop(state, lastResult, canTickSkip);
        boolean canStartWithRiddenFluidHopFloat = canStartWithRiddenFluidHopFloat(lastResult);
        Set<Vec3> unrebounded = state.getSpinRebound() == SpinRebound.ONLY ? Set.of() : initial;
        if (canStartWithFluidHop) {
            for (Vec3 vector : unrebounded) {
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
            packetEventsInOrder.sort(Comparator.comparingLong(TransactionVel::getOutboundSequence));
        }

        for (Vec3 vector : unrebounded) {
            start.add(PredVector.fromStartingVector(vector));
        }

        EntityPush.addVehiclePushCandidates(player, start, state, lastResult);

        // To prevent trying to add a million vectors, we limit the overridden vels
        for (TransactionVel order : player.checkManager.getKnockbackHandler().overriddenVels) {
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
            for (PredVector vector : new ArrayList<>(start)) {
                Vec3 eventVel = vel.getVel();

                if (vel.isVelocity()) { // Velocity
                    vector = vector.withXYZ(eventVel.x, eventVel.y, eventVel.z, "Velocity");
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

    private static void addSlimePistonLaunches(List<PredVector> start, SimulationContext state) {
        for (PredVector vector : new ArrayList<>(start)) {
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
