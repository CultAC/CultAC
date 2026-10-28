package ac.cult.cultac.checks.impl.prediction.stage;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.SpinRebound;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVelocityRange;
import ac.cult.cultac.utils.latency.ClientFishingPulls;
import ac.cult.cultac.utils.latency.ClientRavagerRoars;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class MovementModifiers {
    public static final String RIPTIDE_ATTACK = "riptide attack";
    // LivingEntity#jumpOutOfFluid's Y, which the Java carry applies after travel and Bedrock prediction shares.
    public static final double FLUID_HOP_Y = (double) 0.3F;

    public List<PredVector> applyModifers(
            CultPlayer player,
            Set<Vec3> initial,
            SimulationContext state,
            PredictionResult lastResult) {
        return applyStartingModifiers(player, initial, state, lastResult);
    }

    public List<PredVector> applyBedrockModifiers(
            CultPlayer player,
            Set<Vec3> initial,
            SimulationContext state,
            PredictionResult lastResult) {
        List<PredVector> vectors = applyStartingModifiers(player, initial, state, lastResult);
        TransactionVel receivedMotion = player.checkManager.getKnockbackHandler().secondBread;
        if (state.isBedrockTeleportTick() && receivedMotion != null) {
            // Travel is suppressed, so displacement cannot select the applied motion.
            // Its receipt proof makes the server velocity mandatory for carry instead.
            vectors.removeIf(vector -> !vector.hasPacketModifier(receivedMotion));
        }
        return vectors;
    }

    private List<PredVector> applyStartingModifiers(
            CultPlayer player,
            Set<Vec3> initial,
            SimulationContext state,
            PredictionResult lastResult) {
        List<PredVector> start = new ArrayList<>(initial.size() + 10);

        // This happens on first join, good to keep in case other things do this too.
        if (initial.isEmpty()) {
            start.add(new PredVector(Vec3.ZERO));
        }

        // LivingEntity#jumpOutOfFluid's hop is part of the carry: NextTickVelocityDeriver applies it after travel and
        // before Entity#applyEffectsFromBlocks, so no starting velocity replaces the carried Y here.
        boolean committedSpin = !player.isBedrockMovement()
                && JavaMovementEngine.contextUsesExactEffects(state)
                && state.getProfileCarry() instanceof JavaPredictionCarry carry && hasCarriedVelocity(carry, initial);
        Set<Vec3> unrebounded = committedSpin || state.getSpinRebound() == SpinRebound.ONLY ? Set.of() : initial;

        if (committedSpin) {
            // The carry already ran the spin before later entity/block-entity ticks. Keep only its provenance;
            // scaling here would incorrectly rebound those later velocity writes too.
            var carry = (JavaPredictionCarry) state.getProfileCarry();
            for (var carried : carry.states()) {
                if (!initial.contains(carried.velocity())
                        || carried.spinRebound() && state.getSpinRebound() == SpinRebound.NONE
                        || !carried.spinRebound() && state.getSpinRebound() == SpinRebound.ONLY) continue;
                PredVector vector = PredVector.fromStartingVector(carried.velocity());
                start.add(carried.spinRebound() ? vector.with(carried.velocity(), RIPTIDE_ATTACK) : vector);
            }
        }

        if (!committedSpin && lastResult != null
                && lastResult.getSimulationContext().getWorldData().isSpinAttackTarget()
                && state.getVehicle() == null
                && state.getSpinRebound() != SpinRebound.NONE) {
            for (Vec3 vector : initial) {
                // PredVector#multiply scales a carried velocity range with the vector.
                PredVector riptideAttack = PredVector.fromStartingVector(vector).multiply(-0.2, RIPTIDE_ATTACK);
                start.add(riptideAttack);

                // MCP-Reborn Player#causeExtraKnockback damps attacker X/Z by 0.6
                // before LivingEntity#checkAutoSpinAttack applies the -0.2 rebound.
                if (lastResult.getSimulationContext().getWorldData().isSpinAttackPlayer()
                        && lastResult.getSimulationContext().getIsSprinting().determineOptimistically()) {
                    start.add(JavaVelocityRange.scaled(
                            riptideAttack.withXYZ(
                                    riptideAttack.x * 0.6, riptideAttack.y, riptideAttack.z * 0.6, "riptide sprint attack"),
                            new Vec3(0.6, 1, 0.6)));
                }
            }
        }

        // There may only be one first bread at a time.
        List<TransactionVel> packetEventsInOrder = List.of();
        TransactionVel knockbackFirst = player.checkManager.getKnockbackHandler().firstBread;
        TransactionVel explosionFirst = player.checkManager.getExplosionHandler().firstBread;
        TransactionVel knockbackSecond = player.checkManager.getKnockbackHandler().secondBread;
        TransactionVel explosionSecond = player.checkManager.getExplosionHandler().secondBread;
        var roars = player.compensatedEntities.ravagerRoars.pending(
                state.getVehicle() == null ? player.compensatedEntities.getSelf() : state.getVehicle());
        var pulls = player.compensatedEntities.fishingPulls.pending(
                state.getVehicle() == null ? player.compensatedEntities.getSelf() : state.getVehicle());
        if (knockbackFirst != null
                || explosionFirst != null
                || knockbackSecond != null
                || explosionSecond != null
                || !roars.isEmpty()
                || !pulls.isEmpty()) {
            packetEventsInOrder = new ArrayList<>(4);
            if (knockbackFirst != null) packetEventsInOrder.add(knockbackFirst);
            if (explosionFirst != null) packetEventsInOrder.add(explosionFirst);
            if (knockbackSecond != null) packetEventsInOrder.add(knockbackSecond);
            if (explosionSecond != null) packetEventsInOrder.add(explosionSecond);
            packetEventsInOrder.addAll(roars);
            packetEventsInOrder.addAll(pulls);
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

        // To prevent trying to add a million vectors, we limit the overridden vels
        for (TransactionVel order : player.checkManager.getKnockbackHandler().overriddenVels) {
            PredVector overriddenVel = new PredVector(order.getVel());
            overriddenVel.setEntityMotion(true);
            start.add(overriddenVel);
        }

        // TODO: This is too brute forcey, we don't need duplicates in certain situations.
        // TODO: This should just be refactored for 1.20.4+ clients with tick end and bundle packet!
        for (int eventIndex = 0, eventCount = packetEventsInOrder.size(); eventIndex < eventCount; eventIndex++) {
            TransactionVel vel = packetEventsInOrder.get(eventIndex);
            if (vel instanceof ClientFishingPulls.Impulse pull) {
                var pulled = new ArrayList<PredVector>();
                for (PredVector vector : start)
                    for (Vec3 impulse : pull.impulses()) {
                        PredVector moved = vector.add(impulse, "Fishing pull");
                        pulled.add(moved.withVelocityRange(
                                JavaVelocityRange.add(moved.getVelocityRange(), pull.range())));
                    }
                start = pulled;
                continue;
            }
            if (vel instanceof ClientRavagerRoars.Impulse roar) {
                var pushed = new ArrayList<PredVector>();
                for (PredVector vector : start)
                    for (Vec3 impulse : roar.impulses()) pushed.add(vector.add(impulse, "Ravager roar"));
                start = pushed;
                continue;
            }
            for (PredVector vector : new ArrayList<>(start)) {
                Vec3 eventVel = vel.getVel();

                if (vel.isVelocity()) { // Velocity
                    // set_entity_motion replaces deltaMovement, including any range it held.
                    vector = vector.withXYZ(eventVel.x, eventVel.y, eventVel.z, "Velocity").withVelocityRange(null);
                } else { // Explosion
                    vector = vector.add(eventVel, "Explosion");
                }

                vector.addPacketModifier(vel);
                start.add(vector);
            }
        }

        return start;
    }

    private static boolean hasCarriedVelocity(JavaPredictionCarry carry, Set<Vec3> initial) {
        for (var state : carry.states()) if (initial.contains(state.velocity())) return true;
        return false;
    }
}
