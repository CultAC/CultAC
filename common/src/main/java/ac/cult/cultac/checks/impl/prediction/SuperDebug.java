package ac.cult.cultac.checks.impl.prediction;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionDebug;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.BoatTransform;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ExternalMovementUncertainty;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.PistonData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.debug.DebugText;
import ac.cult.cultac.utils.lists.EvictingQueue;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SuperDebug extends CultProcessor implements PostPredictionListener {

    public static final int FLAG_SIZE = 128;
    private static final Vec3 TELEPORT_MARKER = new Vec3(123456, 123456, 123456);

    /**
     * The Cult Logs by identifier, kept as the values they print: most are never read, so none is printed until
     * {@link #getFlag} reads it.
     */
    private static final DebugText[] flags = new DebugText[FLAG_SIZE + 2];

    private static final VarHandle DIAG;

    static {
        try {
            DIAG = MethodHandles.lookup().findVarHandle(SuperDebug.class, "diag", PredictionObserver.class);
        } catch (ReflectiveOperationException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private final List<MovementFrame> movementHistory = new EvictingQueue<>(40);

    /** Set while something watches this player's predictions: each one also goes to it. */
    private volatile PredictionObserver diag;

    /** The appends and number appends of the last log or capture, to size the next one; on the packet thread. */
    private int lastParts = 1024;

    private int lastNumbers = 512;

    /** Scratch for {@link #appendNearbyEntityBoxes}; a text records a box's bounds as it is appended. */
    private final SimpleCollisionBox queryScratch = new SimpleCollisionBox();

    private final SimpleCollisionBox entityBox = new SimpleCollisionBox();
    private final SimpleCollisionBox entityScratch = new SimpleCollisionBox();

    public SuperDebug(CultPlayer player) {
        super(player);
    }

    /** The Cult Log stored under {@code flagId}, printed now; null when there is none. */
    public static String getFlag(int flagId) {
        if (flagId >= flags.length || flagId < 0) return null;
        DebugText log = flags[flagId];
        return log == null ? null : log.toString();
    }

    public static int ensureBedrockCorrectionLog(CultPlayer player, PredictionResult result) {
        if (result.getIdentifier() == 0) {
            result.setIdentifier(SimulationProcessor.nextDebugIdentifier());
            flags[result.getIdentifier()] = formatBedrockLog(player, result).toText();
        }
        return result.getIdentifier();
    }

    private static DebugText.Builder formatBedrockLog(CultPlayer player, PredictionResult result) {
        DebugText.Builder sb = new DebugText.Builder(256, 128);
        sb.append("Cult Version: ")
                .append(CultAPI.INSTANCE.getExternalAPI().getGrimVersion())
                .append("\nTime: ")
                .append(System.currentTimeMillis())
                .append("\nPlayer Name: ")
                .append(player.user.getName());
        BedrockPredictionDebug.appendDetails(player, sb, result);
        sb.append("\n\n");
        appendFlagDetails(sb, result);
        return sb;
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        PredictionResult result = predictionComplete.getPredictionResult();
        if (player.isBedrockMovement()) {
            if (predictionComplete.isTeleport() || result == null) return;
            if (result.getIdentifier() != 0) {
                storeDebugLog(formatBedrockLog(player, result), result);
            } else if (result.isLogOffered()) {
                buildWhenShown(result, () -> formatBedrockLog(player, result));
            }
            return;
        }

        Location location = player.platformPlayer == null
                ? new Location(player.x, player.y, player.z, player.xRot, player.yRot, null, "")
                : new Location(
                        player.x,
                        player.y,
                        player.z,
                        player.xRot,
                        player.yRot,
                        player.platformPlayer.getWorld().getName(),
                        Location.marks(predictionComplete.isExempt(), predictionComplete.isTeleport()));

        if (predictionComplete.isTeleport()) {
            movementHistory.add(new MovementFrame(TELEPORT_MARKER, TELEPORT_MARKER, location));
            PredictionObserver watch = diag;
            if (watch != null) captureTeleport(watch);
            return;
        }

        PredictionResult lastResult =
                player.checkManager.getSimulationProcessor().getLastPrediction();
        Vec3 target = result.getTarget();
        Vec3 closest = MovementProfiles.forPlayer(player).debugPredictionVector(result);

        movementHistory.add(new MovementFrame(closest, target, location));

        PredictionObserver watch = diag;
        if (watch != null) {
            capture(watch, result, lastResult, closest, target, predictionComplete.isExempt());
        }

        if (result.getIdentifier() != 0) {
            storeDebugLog(javaLog(result, lastResult), result);
        } else if (result.isLogOffered()) {
            buildWhenShown(result, () -> javaLog(result, lastResult));
        }
    }

    /**
     * Builds {@code result}'s Cult Log the first time text showing its identifier is rendered, on this thread while
     * the prediction's listeners run. Until then nothing is built, so a flag nobody sees costs no log.
     */
    private void buildWhenShown(PredictionResult result, Supplier<DebugText.Builder> log) {
        Thread packetThread = Thread.currentThread();
        result.buildLogWhenShown(() -> {
            // Another thread may not read the player's state.
            if (Thread.currentThread() != packetThread) return 0;
            result.setIdentifier(SimulationProcessor.nextDebugIdentifier());
            storeDebugLog(log.get(), result);
            return result.getIdentifier();
        });
    }

    private DebugText.Builder javaLog(PredictionResult result, PredictionResult lastResult) {
        DebugText.Builder sb = newText();
        sb.append("Cult Version: ").append(CultAPI.INSTANCE.getExternalAPI().getGrimVersion());
        sb.append("\n");
        sb.append("Time: ");
        sb.append(System.currentTimeMillis());
        sb.append("\n");
        sb.append("Player Name: ");
        sb.append(player.user.getName());
        sb.append("\nClient Version: ");
        sb.append(player.getClientVersion().getReleaseName());
        sb.append("\nClient Brand: ");
        sb.append(player.getBrand());
        sb.append("\nServer Version: ");
        sb.append(ProtocolVersion.V26_3.minecraftVersion());
        sb.append("\nPing: ");
        sb.append(player.getTransactionPing());
        sb.append(" ms\n\n");
        appendState(sb, result, lastResult);
        appendMovementHistory(sb);
        appendTail(sb, result, lastResult);
        sb.append(BlockGrid.around(player));
        return sb;
    }

    /** A builder sized for this player's last log or capture. */
    private DebugText.Builder newText() {
        return new DebugText.Builder(lastParts + 64, lastNumbers + 32);
    }

    private void sized(DebugText.Builder sb) {
        lastParts = sb.parts();
        lastNumbers = sb.numbers();
    }

    /**
     * The tick's prediction state, recorded here on the packet thread, and its block grid, which the diagnostics thread
     * prints. Nothing of the prediction or of this listener changes.
     */
    private void capture(
            PredictionObserver watch,
            PredictionResult result,
            PredictionResult lastResult,
            Vec3 closest,
            Vec3 target,
            boolean exempt) {
        long start = System.nanoTime();
        try {
            long seq = watch.pendingSeq();
            DebugText state = null;
            BlockGrid grid = null;
            if (result.getSimulationContext() != null) {
                DebugText.Builder sb = newText();
                appendState(sb, result, lastResult);
                appendTail(sb, result, lastResult);
                sized(sb);
                state = sb.toText();
                grid = BlockGrid.around(player);
            }
            double offset = result.getValidMovements() != null ? result.getOffset() : Double.NaN;
            watch.captureText(seq, offset, trace(closest, target, exempt, false), state, grid, start);
        } catch (RuntimeException failure) {
            watch.failed(failure);
        }
    }

    private void captureTeleport(PredictionObserver watch) {
        long start = System.nanoTime();
        try {
            long seq = watch.pendingSeq();
            watch.capture(seq, Double.NaN, trace(null, null, false, true), null, null, start);
        } catch (RuntimeException failure) {
            watch.failed(failure);
        }
    }

    /** The movement history as a watch starts: what came before its first tick. On the packet thread. */
    public String history() {
        StringBuilder sb = new StringBuilder(4096);
        for (MovementFrame frame : movementHistory) frame.appendTo(sb);
        return sb.toString();
    }

    /** The observer of this player's predictions; null when none. */
    public PredictionObserver watch() {
        return diag;
    }

    /** Sends each prediction to {@code watch} too; from any thread. */
    public void watch(PredictionObserver watch) {
        diag = watch;
    }

    /** Clears {@link #diag} if it still holds {@code watch}; from any thread. */
    public void unwatch(PredictionObserver watch) {
        DIAG.compareAndSet(this, watch, null);
    }

    /** One line: predicted and actual movement, position, rotation, ground, exempt or teleport. */
    private String trace(Vec3 predicted, Vec3 actual, boolean exempt, boolean teleport) {
        StringBuilder sb = new StringBuilder(192);
        if (predicted != null) {
            sb.append("pred ")
                    .append(predicted.x)
                    .append(' ')
                    .append(predicted.y)
                    .append(' ')
                    .append(predicted.z);
            sb.append(" act ")
                    .append(actual.x)
                    .append(' ')
                    .append(actual.y)
                    .append(' ')
                    .append(actual.z)
                    .append(' ');
        }
        sb.append("pos ")
                .append(player.x)
                .append(' ')
                .append(player.y)
                .append(' ')
                .append(player.z);
        sb.append(" rot ").append(player.xRot).append(' ').append(player.yRot);
        sb.append(" ground ").append(player.onGround);
        if (exempt) sb.append(" exempt");
        if (teleport) sb.append(" teleport");
        return sb.toString();
    }

    /** Knockback and explosion state, starting velocities, packet, vehicle and boat state, and the candidates. */
    private void appendState(DebugText.Builder sb, PredictionResult result, PredictionResult lastResult) {
        sb.append("\nKnockback first bread: ");
        sb.append(player.checkManager.getKnockbackHandler().firstBread);
        sb.append("\nKnockback second bread: ");
        sb.append(player.checkManager.getKnockbackHandler().secondBread);
        sb.append("\nExplosion first bread: ");
        sb.append(player.checkManager.getExplosionHandler().firstBread);
        sb.append("\nExplosion second bread: ");
        sb.append(player.checkManager.getExplosionHandler().secondBread);
        sb.append("\nStarting vel: ");
        sb.append(result.getInitialStartingVel());
        sb.append("\nStarting velocity candidates used: ");
        sb.appendAll(player.checkManager.getSimulationProcessor().getLastStartingVelocitiesUsed());
        if (result.getValidMovements() != null) {
            sb.append("\nClosest movement: ");
            sb.append(result.getAcceptedClosestToTarget());
            sb.append("\nTarget movement: ");
            sb.append(result.getTarget());
            sb.append("\nPrediction offset: ");
            sb.append(result.getOffset());
        }
        sb.append("\nDerived next-tick velocity candidates: ");
        sb.appendAll(player.checkManager.getSimulationProcessor().getLastDerivedNextTickVelocities());
        sb.append("\nPacket state: vehicleMovementFromClientTick=")
                .append(player.packetStateData.isVehicleMovementFromClientTick())
                .append(" passengerRotationThisClientTick=")
                .append(player.packetStateData.hasPassengerRotationThisClientTick());
        appendRideableControlDetails(sb, result);
        PacketEntity debugVehicle = result.getSimulationContext() == null
                ? null
                : result.getSimulationContext().getVehicle();
        if (result.getSimulationContext() != null) {
            sb.append("\nRoot packet state: rawVelocityAtPrediction=")
                    .append(result.getSimulationContext().getRootClientVelocityAtPrediction())
                    .append(" normalizedVelocityAtPrediction=")
                    .append(result.getSimulationContext().getNormalizedRootClientVelocityAtPrediction())
                    .append(" pendingAtPrediction=")
                    .append(result.getSimulationContext().isRootClientVelocityPendingAtPrediction())
                    .append(" requiredCurrentMoveStuckSpeed=")
                    .append(result.getSimulationContext().getRequiredCurrentMoveStuckSpeed());
        }
        if (debugVehicle != null) {
            sb.append("\nRoot vehicle packet state: queuedVehicleTeleports=")
                    .append(player.getSetbackTeleportUtil().vehicleTeleports().queuedVehicleTeleportCount());
        }
        sb.append("\nBoat data: oldStatus=")
                .append(player.boatData.oldStatus)
                .append(" status=")
                .append(player.boatData.status)
                .append(" waterLevel=")
                .append(player.boatData.waterLevel)
                .append(" landFriction=")
                .append(player.boatData.landFriction)
                .append(" lastYd=")
                .append(player.boatData.lastYd)
                .append(" deltaRotation=")
                .append(player.boatData.deltaRotation)
                .append(" nullifyNextY=")
                .append(player.boatData.nullifyNextY)
                .append(" oldStatusMayBeInAir=")
                .append(player.boatData.oldStatusMayBeInAir)
                .append(" stagedInput=(")
                .append(player.boatData.vehicleHoriz)
                .append(", 0.0, ")
                .append(player.boatData.vehicleForward)
                .append(") nextInput=(")
                .append(player.boatData.nextVehicleHoriz)
                .append(", 0.0, ")
                .append(player.boatData.nextVehicleForward)
                .append(')');
        final long lastAttack = player.actionManager.lastAttack;
        if (lastAttack > 0) {
            sb.append("\nLast attack (ms ago): ").append(System.currentTimeMillis() - lastAttack);
        }

        String boatTickDebug =
                BoatTransform.debugBoatTick(player, result.getSimulationContext(), result.getInitialStartingVel());
        if (boatTickDebug != null) {
            sb.append("\n").append(boatTickDebug);
        }
        appendStuckSpeedDebug(sb, "Current stuck-speed sources", result.getSimulationContext());
        if (lastResult != null) {
            appendStuckSpeedDebug(sb, "Last stuck-speed sources", lastResult.getSimulationContext());
        }

        appendExternalMovementDetails(sb, result, lastResult);

        sb.append("\n\n");
        sb.append("Alternative results (diagnostic candidates; final effective flags are below): \n");
        for (PredictionResult allowedVel : result.getAnyReality()) {
            sb.append(allowedVel.getInitialStartingVel());
            sb.append("\ncandidate flags:\n");
            for (PredictionResult.Flag flag : allowedVel.getFlags()) {
                sb.append(flag.getCheck().getCheckName())
                        .append(" - ")
                        .append(flag.getVerbose().getString());
                sb.append("\n");
            }
            sb.append("\n\n");
        }
        sb.append("\n\n");
    }

    /** The flags, both contexts and ticks' collisions, pistons, the bounding box and nearby entity boxes. */
    private void appendTail(DebugText.Builder sb, PredictionResult result, PredictionResult lastResult) {
        appendFlagDetails(sb, result);

        sb.append("================== context ==================\n\n");
        sb.append(result.getSimulationContext());

        if (lastResult != null) {
            sb.append("\n\n============= last context ==============\n\n");
            sb.append(lastResult.getSimulationContext());
        }

        sb.append("\n\n=============== collisions =================\n\n");
        sb.append(result.getCollideAxisData());

        sb.append("\n\n=========== last tick collisions ============\n\n");
        if (lastResult != null) {
            sb.append(lastResult.getCollideAxisData());
        }

        sb.append("\n\npistons: \n");
        for (PistonData piston : player.compensatedWorld.pistons.activePistons()) {
            for (SimpleCollisionBox box : piston.getBoxes()) {
                sb.append(box);
                sb.append(", ");
            }
        }

        sb.append("\n\n");
        sb.append("Bounding box: ");
        sb.append("minX=");
        sb.append(player.boundingBox.minX);
        sb.append(", minY=");
        sb.append(player.boundingBox.minY);
        sb.append(", minZ=");
        sb.append(player.boundingBox.minZ);
        sb.append(", maxX=");
        sb.append(player.boundingBox.maxX);
        sb.append(", maxY=");
        sb.append(player.boundingBox.maxY);
        sb.append(", maxZ=");
        sb.append(player.boundingBox.maxZ);
        sb.append('}');
        sb.append("\n");
        appendNearbyEntityBoxes(sb, result);
    }

    /** One tick of the movement history. Its vectors and location are immutable, so a log keeps the frame itself. */
    private record MovementFrame(Vec3 predicted, Vec3 actual, Location location) implements DebugText.Part {
        @Override
        public void appendTo(StringBuilder sb) {
            sb.append("Predicted: ");
            sb.append(predicted);
            sb.append("\nEnd: ");
            sb.append(actual);
            sb.append("\nLocation: ");
            sb.append("x: ")
                    .append(location.x())
                    .append(" y: ")
                    .append(location.y())
                    .append(" z: ")
                    .append(location.z())
                    .append(" xRot: ")
                    .append(location.xRot())
                    .append(" yRot: ")
                    .append(location.yRot())
                    .append(" world: ")
                    .append(location.world())
                    .append(location.marks());
            sb.append("\n\n");
        }
    }

    private void appendMovementHistory(DebugText.Builder sb) {
        for (MovementFrame frame : movementHistory) sb.append(frame);
    }

    private static void appendFlagDetails(DebugText.Builder sb, PredictionResult result) {
        sb.append("==================== flags ==================\n\n");
        if (!result.isExempt()) {
            appendFlags(sb, result.getFlags());
        }
        sb.append("=============================================\n\n");

        if (result.isExempt() && !result.getFlags().isEmpty()) {
            sb.append("========= ignored diagnostic flags ==========\n\n");
            appendFlags(sb, result.getFlags());
            sb.append("=============================================\n\n");
        }
    }

    private static void appendFlags(DebugText.Builder sb, List<PredictionResult.Flag> resultFlags) {
        for (PredictionResult.Flag flag : resultFlags) {
            sb.append(flag.getCheck().getCheckName())
                    .append(" - ")
                    .append(flag.getVerbose().getString())
                    .append('\n');
        }
    }

    private void storeDebugLog(DebugText.Builder sb, PredictionResult result) {
        sized(sb);
        DebugText debugLog = sb.toText();
        flags[result.getIdentifier()] = debugLog;
        BedrockPredictionDebug.sendMovementVerbose(player, result, debugLog);
    }

    private void appendRideableControlDetails(DebugText.Builder sb, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        PacketEntity vehicle = context == null ? null : context.getVehicle();
        if (vehicle == null) {
            return;
        }

        sb.append("\nRideable control: vehicleId=")
                .append(vehicle.getEntityId())
                .append(" vehicleType=")
                .append(vehicle.type)
                .append(" selfRiding=")
                .append(player.compensatedEntities.getSelf().getRiding() == vehicle)
                .append(" serverVehicle=")
                .append(player.compensatedEntities.vehicles.serverPlayerVehicle)
                .append(" serverVehicleTx=")
                .append(player.compensatedEntities.vehicles.serverPlayerVehicleTransaction)
                .append(" lastTxReceived=")
                .append(player.lastTransactionReceived.get())
                .append(" hasObservedServerVehicle=")
                .append(player.compensatedEntities.vehicles.hasClientObservedServerVehicle())
                .append(" canLocalVehicleControl=")
                .append(player.compensatedEntities.vehicles.canServerPlayerVehicleBeLocalAuthoritative());

        List<Vec3> inputVectors = debugRideableInputVectors(context, vehicle);
        if (!inputVectors.isEmpty()) {
            sb.append("\nRideable input vectors: ").appendAll(inputVectors);
        }
    }

    private List<Vec3> debugRideableInputVectors(SimulationContext context, PacketEntity vehicle) {
        if (!player.packetStateData.isVehicleMovementFromClientTick()
                || !player.compensatedEntities.vehicles.canServerPlayerVehicleBeLocalAuthoritative()
                || EntityTypeUtil.isBoat(vehicle.type)) {
            return List.of();
        }

        Vec3 riddenInput = null;
        if (vehicle.type == EntityTypeIds.PIG || vehicle.type == EntityTypeIds.STRIDER) {
            riddenInput = new Vec3(0.0D, 0.0D, 1.0D);
        }

        if (riddenInput == null) {
            return List.of();
        }

        List<Vec3> vectors = new ArrayList<>(4);
        boolean anyFluid = false;

        for (boolean water : context.getWorldData().getInWater().getStates()) {
            if (!water) {
                continue;
            }

            anyFluid = true;
            addDistinctInputVector(
                    vectors,
                    inputVectorForSpeed(
                            context, riddenInput, context.getMoveRelativeSpeed(player, false, true, false)));
        }

        for (boolean lava : context.getWorldData().getInLava().getStates()) {
            if (!lava) {
                continue;
            }

            anyFluid = true;
            addDistinctInputVector(
                    vectors,
                    inputVectorForSpeed(
                            context, riddenInput, context.getMoveRelativeSpeed(player, false, false, true)));
        }

        if (!anyFluid) {
            for (boolean onGround : context.getWorldData().getLastOnGround().getStates()) {
                addDistinctInputVector(
                        vectors,
                        inputVectorForSpeed(
                                context, riddenInput, context.getMoveRelativeSpeed(player, onGround, false, false)));
            }
        }

        return vectors;
    }

    private Vec3 inputVectorForSpeed(SimulationContext context, Vec3 riddenInput, float speed) {
        Vec3 scaled = riddenInput.scale(speed);
        float yawRadians = context.getXRot() * ((float) Math.PI / 180F);
        float sin = player.trigHandler.sin(yawRadians);
        float cos = player.trigHandler.cos(yawRadians);
        return new Vec3(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    private void addDistinctInputVector(List<Vec3> vectors, Vec3 candidate) {
        for (Vec3 existing : vectors) {
            if (existing.distanceToSqr(candidate) <= 1.0E-12D) {
                return;
            }
        }
        vectors.add(candidate);
    }

    private void appendExternalMovementDetails(
            DebugText.Builder sb, PredictionResult result, PredictionResult lastResult) {
        ExternalMovementUncertainty.Snapshot snapshot = ExternalMovementUncertainty.capture(result, lastResult);
        Vec3 positionOnlyDelta = result.getPositionOnlyDelta();
        if (!snapshot.hasExternalMove() && positionOnlyDelta.lengthSqr() <= 1.0E-14) {
            return;
        }

        sb.append("\n\n======= external movement uncertainty =======\n\n");
        sb.append("Target-space max abs external delta: ")
                .append(ExternalMovementUncertainty.formatVector(snapshot.maxAbsTargetDelta()))
                .append('\n');
        sb.append("Client/raw max abs position-only delta: ")
                .append(ExternalMovementUncertainty.formatVector(snapshot.maxAbsClientPositionOnlyDelta()))
                .append('\n');
        sb.append("Closest trace position-only delta: ")
                .append(ExternalMovementUncertainty.formatVector(positionOnlyDelta))
                .append('\n');

        if (snapshot.hasExternalMove()) {
            SimpleCollisionBox allowedTarget =
                    snapshot.combinedTargetEnvelopeCopy().offset(result.getInitialStartingVel());
            sb.append("Allowed target-space movement envelope around starting velocity: ")
                    .append(ExternalMovementUncertainty.formatBox(allowedTarget))
                    .append('\n');
        }

        sb.append("Combined target-space external envelope: ")
                .append(ExternalMovementUncertainty.formatBox(snapshot.combinedTargetEnvelope()))
                .append('\n');
        sb.append("Combined client/raw position-only envelope: ")
                .append(ExternalMovementUncertainty.formatBox(snapshot.combinedClientPositionOnlyEnvelope()))
                .append('\n');
        sb.append("Last tick piston target-space envelope: ")
                .append(ExternalMovementUncertainty.formatBox(snapshot.lastPistonTargetEnvelope()))
                .append('\n');
        sb.append("Last tick shulker target-space envelope: ")
                .append(ExternalMovementUncertainty.formatBox(snapshot.lastShulkerTargetEnvelope()))
                .append('\n');
        sb.append("This tick piston target-space envelope: ")
                .append(ExternalMovementUncertainty.formatBox(snapshot.currentPistonTargetEnvelope()))
                .append('\n');
        sb.append("This tick shulker target-space envelope: ")
                .append(ExternalMovementUncertainty.formatBox(snapshot.currentShulkerTargetEnvelope()))
                .append('\n');
        sb.append("Last stuck-speed multiplier: ")
                .append(ExternalMovementUncertainty.formatVector(
                        result.getSimulationContext().getLastStuckSpeed()))
                .append('\n');
    }

    private void appendNearbyEntityBoxes(DebugText.Builder sb, PredictionResult result) {
        SimpleCollisionBox queryBox = queryScratch.set(player.boundingBox).expand(3.0);
        PacketEntity movingEntity = result.getSimulationContext().getVehicle();

        sb.append("\nNearby packet entity boxes:\n");
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (player.compensatedEntities.isUntrackedRider(entity)) continue;
            SimpleCollisionBox box = entity.getPossibleCollisionBoxes(entityBox, entityScratch);
            if (!box.isCollided(queryBox)) {
                continue;
            }

            sb.append("id=")
                    .append(entity.getEntityId())
                    .append(" type=")
                    .append(entity.type)
                    .append(" moving=")
                    .append(entity == movingEntity)
                    .append(" dead=")
                    .append(entity.isDead)
                    .append(" riding=")
                    .append(
                            entity.getRiding() == null
                                    ? "none"
                                    : entity.getRiding().getEntityId())
                    .append(" passengers=");
            if (entity.passengers.isEmpty()) {
                sb.append("[]");
            } else {
                sb.append('[');
                for (int i = 0; i < entity.passengers.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(entity.passengers.get(i).getEntityId());
                }
                sb.append(']');
            }
            sb.append(" box=").append(box).append('\n');
        }
    }

    private void appendStuckSpeedDebug(DebugText.Builder sb, String label, SimulationContext context) {
        if (context == null) {
            return;
        }

        sb.append('\n').append(label).append(": ");
        Collisions.appendStuckSpeedSources(
                sb, player, context.getFromMaximumExtent(), context.getToMaximumExtent(), context.getTarget());
    }

    /**
     * Where the player was: {@code world} is null without a platform player, and {@code marks} notes an exempt or
     * teleport tick after the world's name.
     */
    private record Location(double x, double y, double z, float xRot, float yRot, String world, String marks) {
        static String marks(boolean exempt, boolean teleport) {
            if (exempt) return teleport ? " exempt! teleport!" : " exempt!";
            return teleport ? "  teleport!" : " ";
        }
    }
}
