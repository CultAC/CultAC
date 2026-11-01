package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionDebug;
import ac.cult.cultac.bedrock.prediction.integration.ExternalMovementUncertainty;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.anticheat.StringReturner;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

public class DebugHandler extends CultProcessor implements PostPredictionListener {
    private static final int MAX_LISTED_END_OF_TICK_VELOCITIES = 4;
    private final Map<UUID, Sender> rewindListeners = new ConcurrentHashMap<>();
    private final Map<UUID, Sender> itemSlowListeners = new ConcurrentHashMap<>();

    public boolean toggleItemSlowListener(Sender sender) {
        if (itemSlowListeners.remove(sender.getUniqueId()) != null) return false;
        itemSlowListeners.put(sender.getUniqueId(), sender);
        return true;
    }

    public boolean hasItemSlowListeners() {
        if (itemSlowListeners.isEmpty()) return false;
        removeInvalidListeners(itemSlowListeners);
        return !itemSlowListeners.isEmpty();
    }

    public void relayItemSlow(Supplier<Component> details) {
        if (!hasItemSlowListeners()) return;
        var message = Component.text("[itemslow] " + player.getName() + " ").append(details.get());
        itemSlowListeners.values().forEach(sender -> sender.sendMessage(message));
    }

    public boolean toggleRewindListener(Sender sender) {
        if (rewindListeners.remove(sender.getUniqueId()) != null) return false;
        rewindListeners.put(sender.getUniqueId(), sender);
        return true;
    }

    public boolean hasRewindListeners() {
        removeInvalidListeners(rewindListeners);
        return !rewindListeners.isEmpty();
    }

    public void relayRewind(Supplier<String> details) {
        removeInvalidListeners(rewindListeners);
        if (rewindListeners.isEmpty()) return;
        var message = Component.text(player.getName() + " " + details.get());
        rewindListeners.values().forEach(sender -> sender.sendMessage(message));
    }

    private static void removeInvalidListeners(Map<UUID, Sender> listeners) {
        listeners
                .values()
                .removeIf(sender -> !sender.isValid()
                        || sender.getPlatformPlayer() != null
                                && !sender.getPlatformPlayer().isOnline()
                        || !sender.hasPermission("cult.debug"));
    }

    // Each listener, and whether it ran /cult debug -v for the S and E velocities too. The command thread toggles one
    // entry atomically while the packet thread prunes offline listeners, so neither splits the other's update.
    private final Map<PlatformPlayer, Boolean> listeners = new ConcurrentHashMap<>();
    boolean outputToConsole = false;
    private boolean consoleVerbose = false;

    // The prediction whose debug lines were just sent; its end-of-tick velocities follow once they
    // are committed.
    private PredictionResult awaitingEndOfTick;
    private Set<Vec3> startOfTickVelocities;
    // A committed prediction whose end-of-tick line waits for the client tick's later passes (entities and block
    // entities ticking after the report through 26.2), with the velocities it committed for a client without tick ends.
    private PredictionResult committed;
    private Set<Vec3> committedStart;
    private Set<Vec3> committedEnd;
    private String endOfTickLabelColor;
    private String endOfTickColor;

    public DebugHandler(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        // No tick end closed the last committed tick: its committed velocities are its end.
        if (committed != null) publishEndOfTick(committedEnd);
        awaitingEndOfTick = null;
        startOfTickVelocities = null;
        if (predictionComplete.isTeleport()) return;
        if (listeners.isEmpty() && !outputToConsole) return;

        PredictionResult result = predictionComplete.getPredictionResult();
        PredictionResult lastResult =
                player.checkManager.getSimulationProcessor().getLastPrediction();
        BedrockPredictionDebug.MovementDebugView debugView = BedrockPredictionDebug.movementDebugView(
                player, result, MovementProfiles.forPlayer(player).debugPredictionVector(result));
        double offset = debugView.offset();
        Vec3 predictedWithInputs = debugView.predictedWithInputs();
        Vec3 target = debugView.target();

        boolean inVehicle = isVehiclePrediction(result);
        boolean positionsTooUncertain = positionsTooUncertainToCheck(inVehicle);
        boolean fullyExempt = predictionComplete.isExempt() && !positionsTooUncertain;
        String color =
                debugView.missingObservation() ? "\u00a77" : pickColor(offset, positionsTooUncertain, fullyExempt);
        String labelColor = positionsTooUncertain ? "\u00a77" : fullyExempt ? "\u00a79" : "\u00a7f";
        String external = formatExternalMovementDebug(
                result, lastResult, externalUncertaintyColor(positionsTooUncertain, fullyExempt));

        // This is pointless debug unless an external movement uncertainty is active.
        if (!debugView.missingObservation() && target.lengthSqr() == 0 && offset == 0 && external == null) return;

        String p = labelColor + "P: " + color + NumFormatter.formatVectorDebug(predictedWithInputs);
        String a = labelColor + "A: " + color + NumFormatter.formatVector(target);
        String o = labelColor + "O: " + color
                + (debugView.missingObservation() ? "engine-missing" : Double.toString(offset));
        String b = outputToConsole
                ? BedrockPredictionDebug.formatConsoleDebug(player, predictionComplete, labelColor, color)
                : null;

        String prefix = player.platformPlayer == null ? "null" : player.platformPlayer.getName() + " ";

        // Don't memory leak player references
        listeners.keySet().removeIf(player -> !player.isOnline());

        for (PlatformPlayer player : listeners.keySet()) {
            // Don't add prefix if the player is listening to oneself
            player.sendMessage((player == getPlayer().platformPlayer ? "" : prefix) + p);
            player.sendMessage((player == getPlayer().platformPlayer ? "" : prefix) + a);
            player.sendMessage((player == getPlayer().platformPlayer ? "" : prefix) + o);
            if (!inVehicle && external != null) {
                player.sendMessage((player == getPlayer().platformPlayer ? "" : prefix) + external);
            }

            // Java valid-movement envelopes are not authoritative for Bedrock authored-input prediction.
            if (player == getPlayer().platformPlayer && getPlayer().bedrockState == null) {
                String uncertaintyColor = positionsTooUncertain ? "\u00a77" : fullyExempt ? "\u00a79" : "\u00a7d";
                SimpleCollisionBox validStarting = result.getValidMovements()
                        .getCollisionIgnoredMaxStartingVelExtents()
                        .copy();

                for (PredictionResult reality : result.getRealities()) {
                    validStarting.union(reality.getValidMovements().getCollisionIgnoredMaxStartingVelExtents());
                }

                Vec3 max = validStarting.max();
                Vec3 min = validStarting.min();

                max = CollisionModifier.transformWithCollisions(
                        result.getSimulationContext(),
                        result.getCollideAxisData(),
                        new PredVector(max),
                        target,
                        result);
                min = CollisionModifier.transformWithCollisions(
                        result.getSimulationContext(),
                        result.getCollideAxisData(),
                        new PredVector(min),
                        target,
                        result);

                if (!max.equals(min)) {
                    player.sendMessage(uncertaintyColor + NumFormatter.formatVector(min));
                    player.sendMessage(uncertaintyColor + NumFormatter.formatVector(max));
                }
            }
        }

        if (getPlayer().bedrockState == null && (listeners.containsValue(true) || outputToConsole && consoleVerbose)) {
            awaitingEndOfTick = result;
            // The commit clears this set before refilling it with the end-of-tick velocities.
            startOfTickVelocities =
                    Set.copyOf(player.checkManager.getSimulationProcessor().getLastStartingVelocitiesUsed());
            endOfTickLabelColor = labelColor;
            endOfTickColor = positionsTooUncertain ? "\u00a77" : fullyExempt ? "\u00a79" : "\u00a7d";
        }

        if (outputToConsole) {
            LogUtil.info(prefix + p);
            LogUtil.info(prefix + a);
            if (inVehicle) {
                LogUtil.info(prefix + o);
            } else {
                if (b != null) {
                    LogUtil.info(prefix + b);
                }
                LogUtil.info(prefix + o);
                if (external != null) {
                    LogUtil.info(prefix + external);
                }
            }
            if (!inVehicle) {
                LogUtil.info("FROM: " + player.lastX + " " + player.lastY + " " + player.lastZ);
                LogUtil.info("TO: " + player.x + " " + player.y + " " + player.z);
            }
        }
    }

    /**
     * Sends verbose listeners ({@code /cult debug -v}) the velocities the prediction just debugged started from (S)
     * and the ones the engine carries into the next tick (E). Each one is a starting velocity the next tick will
     * accept, so a set wider than the client can produce is room for a bypass.
     */
    public void onPredictionCommitted(PredictionResult result, Set<Vec3> endOfTickVelocities) {
        if (result == null || result != awaitingEndOfTick) return;
        awaitingEndOfTick = null;
        committed = result;
        committedStart = startOfTickVelocities;
        committedEnd = Set.copyOf(endOfTickVelocities);
        startOfTickVelocities = null;
    }

    /** The client tick ended after every later pass: {@code endOfTickVelocities} are the next travel's starts. */
    public void onClientTickEnd(Set<Vec3> endOfTickVelocities) {
        if (committed != null) publishEndOfTick(endOfTickVelocities);
    }

    private void publishEndOfTick(Set<Vec3> endOfTickVelocities) {
        Set<Vec3> startOfTick = committedStart;
        committed = null;
        committedStart = null;
        committedEnd = null;

        boolean toConsole = outputToConsole && consoleVerbose;
        if (!listeners.containsValue(true) && !toConsole) return;

        List<String> lines = new ArrayList<>();
        formatVelocities(lines, "S: ", startOfTick);
        formatVelocities(lines, "E: ", endOfTickVelocities);
        PlatformPlayer self = getPlayer().platformPlayer;
        String prefix = self == null ? "null" : self.getName() + " ";
        listeners.forEach((listener, verbose) -> {
            if (!verbose) return;
            String listenerPrefix = listener == self ? "" : prefix;
            for (String line : lines) listener.sendMessage(listenerPrefix + line);
        });
        if (toConsole) {
            for (String line : lines) LogUtil.info(prefix + line);
        }
    }

    private void formatVelocities(List<String> lines, String name, Set<Vec3> velocities) {
        String label = endOfTickLabelColor + name + endOfTickColor;
        if (velocities.isEmpty()) {
            lines.add(label + "none");
            return;
        }
        if (velocities.size() == 1) {
            lines.add(label + NumFormatter.formatVector(velocities.iterator().next()));
            return;
        }

        lines.add(label + velocities.size() + " candidates");
        if (velocities.size() <= MAX_LISTED_END_OF_TICK_VELOCITIES) {
            List<Vec3> sorted = sortedVelocities(velocities);
            Vec3 first = sorted.getFirst();
            List<String> formattedVelocities =
                    sorted.stream().map(NumFormatter::formatVector).toList();
            for (int i = 0; i < sorted.size(); i++) {
                Vec3 velocity = sorted.get(i);
                String formatted = formattedVelocities.get(i);
                String delta = Collections.frequency(formattedVelocities, formatted) > 1
                        ? " (Δ " + NumFormatter.formatNumberDebug(velocity.distanceTo(first)) + ")"
                        : "";
                lines.add(endOfTickColor + " " + formatted + delta);
            }
            return;
        }

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3 velocity : velocities) {
            minX = Math.min(minX, velocity.x);
            minY = Math.min(minY, velocity.y);
            minZ = Math.min(minZ, velocity.z);
            maxX = Math.max(maxX, velocity.x);
            maxY = Math.max(maxY, velocity.y);
            maxZ = Math.max(maxZ, velocity.z);
        }
        lines.add(endOfTickColor + " min " + NumFormatter.formatVector(new Vec3(minX, minY, minZ)));
        lines.add(endOfTickColor + " max " + NumFormatter.formatVector(new Vec3(maxX, maxY, maxZ)));
    }

    private static List<Vec3> sortedVelocities(Set<Vec3> velocities) {
        return velocities.stream()
                .sorted(Comparator.comparingDouble((Vec3 v) -> v.x)
                        .thenComparingDouble(v -> v.y)
                        .thenComparingDouble(v -> v.z))
                .toList();
    }

    private String formatExternalMovementDebug(PredictionResult result, PredictionResult lastResult, String color) {
        ExternalMovementUncertainty.Snapshot snapshot = ExternalMovementUncertainty.capture(result, lastResult);
        Vec3 positionOnlyDelta = result.getPositionOnlyDelta();
        if (!snapshot.hasExternalMove() && positionOnlyDelta.lengthSqr() <= 1.0E-14) {
            return null;
        }

        StringBuilder builder = new StringBuilder(color + "ext:");
        boolean appended = false;
        if (snapshot.hasExternalMove()) {
            appended = appendPart(builder, appended, "t  " + formatBracketedVector(snapshot.maxAbsTargetDelta()));
            appended = appendPart(
                    builder, appended, "r " + formatBracketedVector(snapshot.maxAbsClientPositionOnlyDelta()));
        }
        if (positionOnlyDelta.lengthSqr() > 1.0E-14) {
            appended = appendPart(builder, appended, "c " + formatBracketedVector(positionOnlyDelta));
        }
        return builder.toString();
    }

    private boolean appendPart(StringBuilder builder, boolean appended, String part) {
        if (appended) {
            builder.append(" |");
        }
        builder.append(' ').append(part);
        return true;
    }

    private String formatBracketedVector(Vec3 vector) {
        return "[" + ExternalMovementUncertainty.formatVector(vector) + "]";
    }

    private boolean positionsTooUncertainToCheck(boolean inVehicle) {
        return inVehicle
                ? player.getSetbackTeleportUtil().shouldBlockVehicleMovement()
                : player.getSetbackTeleportUtil().shouldBlockMovement()
                        || player.getSetbackTeleportUtil().insideUnloadedChunk();
    }

    private boolean isVehiclePrediction(PredictionResult result) {
        return result.getSimulationContext() != null
                && result.getSimulationContext().getVehicle() != null;
    }

    private String externalUncertaintyColor(boolean positionsTooUncertain, boolean fullyExempt) {
        if (positionsTooUncertain) return "\u00a77";
        if (fullyExempt) return "\u00a79";
        return "\u00a7b";
    }

    private String pickColor(double offset, boolean positionsTooUncertain, boolean fullyExempt) {
        if (positionsTooUncertain) return "\u00a77";
        if (fullyExempt) return "\u00a79";
        if (offset == 0) {
            return "\u00a7f";
        } else if (offset < 0.0001) {
            return "\u00a7a";
        } else if (offset < 0.01) {
            return "\u00a7e";
        } else {
            return "\u00a7c";
        }
    }

    /**
     * Plain debug turns prediction output on or off. Verbose debug ({@code -v}) also shows the S and E velocities: it
     * starts a verbose session, upgrades a plain one, and turns a verbose one off.
     */
    public void toggleListener(PlatformPlayer player, boolean verbose) {
        Boolean now = listeners.compute(player, (listener, was) -> was != null && (!verbose || was) ? null : verbose);
        if (now == null) player.sendMessage("\u00a77" + "Disabled debugging of predictions");
        else if (now)
            player.sendMessage("\u00a7a" + "Debugging predictions with start (S) and end (E) of tick velocities");
        else player.sendMessage("\u00a7a" + "Debugging predictions");
    }

    public boolean toggleConsoleOutput() {
        return toggleConsoleOutput(false);
    }

    /** Same rules as {@link #toggleListener(PlatformPlayer, boolean)}, for console output. */
    public boolean toggleConsoleOutput(boolean verbose) {
        if (outputToConsole && (!verbose || consoleVerbose)) {
            return setConsoleOutput(false);
        }
        consoleVerbose = verbose;
        outputToConsole = true;
        return true;
    }

    public boolean setConsoleOutput(boolean outputToConsole) {
        this.outputToConsole = outputToConsole;
        if (!outputToConsole) consoleVerbose = false;
        return this.outputToConsole;
    }

    /**
     * Generic diagnostic sink for non-prediction debug lines (checks and processors route
     * their {@code debug(...)} calls here). The supplier is only evaluated when at least
     * one listener or the console output is active, and rendering happens on the caller.
     */
    public void relayDebug(String source, StringReturner details) {
        if (listeners.isEmpty() && !outputToConsole) return;

        String message = "\u00a7b" + "[" + source + "] " + "\u00a7f" + details.getString();
        listeners.keySet().removeIf(listener -> !listener.isOnline());
        for (PlatformPlayer listener : listeners.keySet()) {
            listener.sendMessage(message);
        }
        if (outputToConsole) {
            LogUtil.info(message);
        }
    }
}
