package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import java.util.ArrayList;
import java.util.List;

/**
 * The exact LivingEntity#moveRelative input of a Java player's own travel. LocalPlayer#applyInput moves by the keys
 * while the player is the camera entity. From 1.21.2 LocalPlayer#tick sends those keys in PLAYER_INPUT after the
 * tick's aiStep and before sendPosition (1.21.4 LocalPlayer.java:195-210, 1.21.11 LocalPlayer.java:214-228), so they
 * are the keys that travel read; an older client's keys are any KeyboardInput direction.
 */
public final class JavaTravelInputs {
    private JavaTravelInputs() {}

    /**
     * Whether the travel of {@code context} adds an exact input: a Java player outside a vehicle that is its own camera
     * entity. A vehicle supplies its ridden input; a camera elsewhere keeps the existing decayed-input model.
     */
    public static boolean appliesTo(CultPlayer player, SimulationContext context) {
        return !player.isBedrockMovement() && context.getVehicle() == null && player.cameraEntity.isSelf();
    }

    /** Whether the client sends the PLAYER_INPUT keys its travel reads. */
    public static boolean reportsKeys(CultPlayer player) {
        return !player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }

    /** Whether the reported keys are the ones {@code context}'s travel reads, including LivingEntity#jumping. */
    public static boolean usesReportedKeys(CultPlayer player, SimulationContext context) {
        return appliesTo(player, context) && reportsKeys(player);
    }

    /** Each starting velocity once per exact input its travel may add, or {@code velocities} where none applies. */
    public static List<PredVector> withInputs(
            CultPlayer player, SimulationContext context, List<PredVector> velocities) {
        if (!appliesTo(player, context)) return velocities;
        boolean keys = reportsKeys(player);
        List<PredVector> candidates = new ArrayList<>(velocities.size() * 2);
        for (int i = 0, size = velocities.size(); i < size; i++) {
            PredVector velocity = velocities.get(i);
            for (Vec3 input : ClientKeyInput.exactTravelInputs(player, context, velocity, keys)) {
                candidates.add(velocity.withTravelInput(input));
            }
        }
        return candidates;
    }

    /** The inputs a replay of {@code candidate}'s travel adds: none once the candidate holds its exact input. */
    public static List<Vec3> replayInputs(CultPlayer player, SimulationContext context, PredVector candidate) {
        return isExact(candidate)
                ? List.of(Vec3.ZERO)
                : ClientKeyInput.possibleTravelInputs(player, context, candidate);
    }

    /** The reported keys' inputs a replay of {@code candidate}'s travel adds; none once it holds its exact input. */
    public static List<Vec3> reportedReplayInputs(CultPlayer player, SimulationContext context, PredVector candidate) {
        return isExact(candidate) ? List.of(Vec3.ZERO) : ClientKeyInput.travelInputs(player, context, candidate);
    }

    public static boolean isExact(PredVector candidate) {
        return candidate != null && candidate.hasTravelInput();
    }
}
