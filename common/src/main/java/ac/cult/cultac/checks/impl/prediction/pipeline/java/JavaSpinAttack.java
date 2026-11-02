package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.Box;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityArmorStand;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;

/** LivingEntity#checkAutoSpinAttack reads the travel pose and each entity at the local player's tick place. */
public final class JavaSpinAttack {
    private JavaSpinAttack() {}

    public enum Presence {
        ABSENT,
        POSSIBLE,
        CERTAIN;

        Presence or(Presence other) {
            return ordinal() >= other.ordinal() ? this : other;
        }
    }

    public record Hit(Presence living, Presence anyEntity, boolean player) {}

    /** The spin's velocity write, before later entity ticks and the block-entity pass. */
    public record Velocity(Vec3 velocity, boolean rebound) {}

    public static List<Velocity> afterTravel(CultPlayer player, PredictionResult result, Vec3 end, Vec3 velocity) {
        SimulationContext context = result.getSimulationContext();
        Hit hit = check(player, context, end);
        if (hit.living() == Presence.ABSENT) return List.of(new Velocity(velocity, false));
        var outcomes = new ArrayList<Velocity>(3);
        if (!player.riptideSpinCertain || hit.living() != Presence.CERTAIN)
            outcomes.add(new Velocity(velocity, false));
        outcomes.add(new Velocity(velocity.scale(-0.2), true));
        if (hit.player() && extraKnockbackPossible(player, context)) {
            // Player#attack -> causeExtraKnockback, then LivingEntity#checkAutoSpinAttack's rebound.
            outcomes.add(new Velocity(velocity.multiply(0.6, 1.0, 0.6).scale(-0.2), true));
        }
        return outcomes;
    }

    /**
     * Whether the spin's Player#attack may pass causeExtraKnockback's positive knockback, which damps X/Z by 0.6 and
     * calls setSprinting(false): the client's LivingEntity#getKnockback is attack_knockback / 2, and a sprint attack
     * adds 0.5.
     */
    public static boolean extraKnockbackPossible(CultPlayer player, SimulationContext context) {
        return context.getIsSprinting().determineOptimistically() || player.combatAttributes.attackKnockback() > 0.0;
    }

    /** A post-travel shove cannot decide a pre-travel sprint flag. Validate the extra state against its own travel. */
    public static List<JavaRiptideRelease.Prepared> sprintContexts(
            CultPlayer player, List<JavaRiptideRelease.Prepared> prepared) {
        if (player.isBedrockMovement() || player.isSprinting || player.riptideSpinAttackTicks <= 0) return prepared;
        var variants = new ArrayList<JavaRiptideRelease.Prepared>();
        for (var release : prepared) {
            var context = release.context();
            var pass = context.getPassAfterTravel();
            if (context.getVehicle() != null || pass == null || pass.isEmpty()
                    || context.getSpinRebound() == SimulationContext.SpinRebound.ONLY) {
                variants.add(release);
                continue;
            }
            var reset = context.afterPreTravelMove(null);
            context.setIsSprinting(DesyncStatus.FALSE);
            context.setSprintModifier(player.sprintState.modifier());
            reset.setIsSprinting(DesyncStatus.TRUE);
            reset.setSprintModifier(true);
            reset.setSpinSprintResetRequired(true);
            variants.add(release);
            variants.add(new JavaRiptideRelease.Prepared(reset, release.velocities()));
        }
        return variants;
    }

    public static boolean validSprintContext(CultPlayer player, PredictionResult result) {
        var context = result.getSimulationContext();
        if (!context.isSpinSprintResetRequired()) return true;
        if (player.packetStateData.riptideLevel == 0
                && result.getInitialStartingVel().hasReason(MovementModifiers.RIPTIDE_ATTACK)) return false;
        var pass = JavaMovementEngine.passAfterTravel(result);
        // This extra state exists only for a post-travel pass. Without a replayed candidate, the report
        // supplies no pre-pass position and cannot prove the sprint reset.
        return pass != null && check(player, context, pass.travel().position()).player();
    }

    private static SimpleCollisionBox poseBox(SimulationContext context, Vec3 position) {
        Pose pose = context.getPose();
        float scale = pose == Pose.SLEEPING || pose == Pose.DYING ? 1.0F : context.getScale();
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                position.x, position.y, position.z, pose.width * scale, pose.height * scale);
    }

    private static boolean intersects(SimpleCollisionBox a, SimpleCollisionBox b) {
        return a.minX < b.maxX
                && a.maxX > b.minX
                && a.minY < b.maxY
                && a.maxY > b.minY
                && a.minZ < b.maxZ
                && a.maxZ > b.minZ;
    }

    public static Hit check(CultPlayer player, SimulationContext context, Vec3 travelEnd) {
        if (context.getVehicle() != null || player.riptideSpinAttackTicks <= 0
                || context.getSpinRebound() == SimulationContext.SpinRebound.ONLY && context.getRiptideLevel() == 0)
            return new Hit(Presence.ABSENT, Presence.ABSENT, false);
        SimpleCollisionBox sweep = poseBox(context, context.getStart()).union(poseBox(context, travelEnd));
        var entities = context.getEntities();
        Presence living = Presence.ABSENT, any = Presence.ABSENT;
        boolean remotePlayer = false;
        var scratch = new SimpleCollisionBox();
        for (var entity : entities.entityMap.values()) {
            // Owner decision: foreign riding trees have no movement positions for collision readers.
            if (entity == entities.getSelf()) continue;
            if (entity instanceof PacketEntityPlayer remote && remote.profile != null
                    && remote.actionSpectator(entities.clientPlayerModes)) continue;
            int positions = entity.possibleMovementCollisionBoxCandidateCount(), count = 0, hits = 0;
            for (int i = 0; i < positions; i++) {
                entity.possibleMovementCollisionBoxCandidate(i, scratch);
                if (entity.isAvatar()) {
                    for (var box : entity.avatarBoxCandidates(scratch)) {
                        count++;
                        if (intersects(sweep, box)) hits++;
                    }
                    continue;
                }
                count++;
                if (entity instanceof PacketEntityArmorStand stand) {
                    Box box = stand.actionBounds(scratch);
                    scratch.set(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
                } else entity.toBoundingBox(player, scratch);
                if (intersects(sweep, scratch)) hits++;
            }
            if (hits == 0) continue;
            Presence present = hits == count && entity.hasProvenMovementCandidates(player.lastTransactionReceived.get())
                    ? Presence.CERTAIN : Presence.POSSIBLE;
            any = any.or(present);
            if (entity.isLivingEntity()) {
                living = living.or(present);
                remotePlayer |= entity instanceof PacketEntityPlayer;
            }
        }
        return new Hit(living, any, remotePlayer);
    }
}
