package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionDebug;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ExternalMovementUncertainty;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

// @CheckData(name = "Prediction (Debug)")
public class DebugHandler extends CultProcessor implements PostPredictionListener {
    private final Map<UUID, Sender> rewindListeners = new ConcurrentHashMap<>();

    public boolean toggleRewindListener(Sender sender) {
        if (rewindListeners.remove(sender.getUniqueId()) != null) return false;
        rewindListeners.put(sender.getUniqueId(), sender);
        return true;
    }

    public boolean hasRewindListeners() {
        removeInvalidRewindListeners();
        return !rewindListeners.isEmpty();
    }

    public void relayRewind(Supplier<String> details) {
        removeInvalidRewindListeners();
        if (rewindListeners.isEmpty()) return;
        var message = Component.text(player.getName() + " " + details.get());
        rewindListeners.values().forEach(sender -> sender.sendMessage(message));
    }

    private void removeInvalidRewindListeners() {
        rewindListeners
                .values()
                .removeIf(sender -> !sender.isValid()
                        || sender.getPlatformPlayer() != null
                                && !sender.getPlatformPlayer().isOnline()
                        || !sender.hasPermission("cult.debug"));
    }

    static final Set<UUID> DEVELOPERS = ImmutableSet.of(
            UUID.fromString("aadd63d0-e545-4cc2-8449-734a6dba0b85"),
            UUID.fromString("12f4db7a-8b9c-48d9-8571-533ca27170f0"));

    public static boolean isDeveloper(UUID uuid) {
        return DEVELOPERS.contains(uuid);
    }

    Set<PlatformPlayer> listeners = new CopyOnWriteArraySet<>(new HashSet<>());
    boolean outputToConsole = false;

    boolean enabledFlags = false;
    boolean lastMovementIsFlag = false;

    public DebugHandler(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (predictionComplete.isTeleport()) return;

        PredictionResult result = predictionComplete.getPredictionResult();
        PredictionResult lastResult =
                player.checkManager.getSimulationProcessor().getLastPrediction();
        BedrockPredictionDebug.MovementDebugView debugView =
                BedrockPredictionDebug.movementDebugView(player, result, getDebugPredictionVector(result));
        double offset = debugView.offset();
        Vec3 predictedWithInputs = debugView.predictedWithInputs();
        Vec3 target = debugView.target();

        if (listeners.isEmpty() && !outputToConsole) return;

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
                + (debugView.missingObservation() ? "engine-missing" : formatOffsetDebug(offset));
        String b = outputToConsole
                ? BedrockPredictionDebug.formatConsoleDebug(player, predictionComplete, labelColor, color)
                : null;

        String prefix = player.platformPlayer == null ? "null" : player.platformPlayer.getName() + " ";

        // Don't memory leak player references
        listeners.removeIf(player -> !player.isOnline());

        for (PlatformPlayer player : listeners) {
            // Don't add prefix if the player is listening to oneself
            player.sendMessage((player == getPlayer().platformPlayer ? "" : prefix) + p);
            player.sendMessage((player == getPlayer().platformPlayer ? "" : prefix) + a);
            if (inVehicle) {
                player.sendMessage((player == getPlayer().platformPlayer ? "" : prefix) + o);
            } else {
                player.sendMessage((player == getPlayer().platformPlayer ? "" : prefix) + o);
                if (external != null) {
                    player.sendMessage((player == getPlayer().platformPlayer ? "" : prefix) + external);
                }
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
                        result.getSimulationContext(), result.getCollideAxisData(), new PredVector(max), target);
                min = CollisionModifier.transformWithCollisions(
                        result.getSimulationContext(), result.getCollideAxisData(), new PredVector(min), target);

                if (!max.equals(min)) {
                    player.sendMessage(uncertaintyColor + NumFormatter.formatVector(min));
                    player.sendMessage(uncertaintyColor + NumFormatter.formatVector(max));
                }
            }
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

    private static String formatOffsetDebug(double offset) {
        return Double.toString(offset);
    }

    private String formatExternalMovementDebug(PredictionResult result, PredictionResult lastResult, String color) {
        ExternalMovementUncertainty.Snapshot snapshot = ExternalMovementUncertainty.capture(result, lastResult);
        Vec3 positionOnlyDelta = result.getPositionOnlyDelta();
        if (!snapshot.hasAnyUncertainty() && positionOnlyDelta.lengthSqr() <= 1.0E-14) {
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

    private String getUnknownColor(double offset) {
        if (offset == 0) {
            return "\u00a77";
        }
        return "\u00a7d";
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

    private Vec3 getDebugPredictionVector(PredictionResult result) {
        return MovementProfiles.forPlayer(player).debugPredictionVector(result);
    }

    public void toggleListener(PlatformPlayer player) {
        // Toggle, if already added, remove.  If not added, then add
        final boolean wasListening = listeners.remove(player);
        if (wasListening) {
            final String disableMessage = "\u00a77" + "Disabled debugging of predictions";
            player.sendMessage(disableMessage);
        } else {
            final String enableMessage = "\u00a7a" + "Debugging predictions";
            player.sendMessage(enableMessage);
            final boolean nowListening = listeners.add(player);
        }
    }

    public boolean toggleConsoleOutput() {
        this.outputToConsole = !outputToConsole;
        return this.outputToConsole;
    }

    public boolean setConsoleOutput(boolean outputToConsole) {
        this.outputToConsole = outputToConsole;
        return this.outputToConsole;
    }

    /**
     * Generic diagnostic sink for non-prediction debug lines (checks and processors route
     * their {@code debug(...)} calls here). The supplier is only evaluated when at least
     * one listener or the console output is active, and rendering happens on the caller.
     */
    public void relayDebug(String source, ac.cult.cultac.utils.anticheat.StringReturner details) {
        if (listeners.isEmpty() && !outputToConsole) return;

        String message = "\u00a7b" + "[" + source + "] " + "\u00a7f" + details.getString();
        listeners.removeIf(listener -> !listener.isOnline());
        for (PlatformPlayer listener : listeners) {
            listener.sendMessage(message);
        }
        if (outputToConsole) {
            LogUtil.info(message);
        }
    }
}
