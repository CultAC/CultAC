package ac.cult.cultac.checks.impl.movement;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.Friction;
import ac.cult.cultac.utils.nmsutil.NextTickVelocityDeriver;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import ac.grim.grimac.api.config.ConfigManager;
import org.jetbrains.annotations.NotNull;

/**
 * Until the client applies the block-changed ack, a placement the server refused stays in the client's world and
 * LivingEntity#travel takes its friction. Simulation accepts that movement. This check follows the velocity the
 * player would carry if each refused block under it had the server's friction, and once the player moves faster
 * than that by the threshold, takes the speed it carries beyond that out of the client's velocity.
 */
public class GhostFriction extends Check implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("GhostFriction")
            .stableKey("cult.movement.ghost_friction")
            .description("Gained speed from a block the server refused")
            .setback(-1)
            .decay(0.05)
            .build();

    private double threshold;
    // Horizontal velocity the client carries beyond the server-friction velocity.
    private double excessX;
    private double excessZ;
    private boolean correctionPending;

    public GhostFriction(CultPlayer player) {
        super(player, INFO);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        threshold = config.getDoubleElse(getConfigName() + ".threshold", 0.05);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete complete) {
        if (!isEnabled() || complete.isTeleport()) {
            clear();
            return;
        }
        PredictionResult result = complete.getPredictionResult();
        SimulationContext context = result.getSimulationContext();
        if (context.getVehicle() != null
                || context.usesFallFlyingMovement()
                || player.isFlying
                || context.getWorldData().getInWater().determineOptimistically()
                || context.getWorldData().getInLava().determineOptimistically()
                || result.getValidMovements() == null
                || result.getValidMovements().getClosestTrace() == null) {
            clear();
            return;
        }
        // A set_entity_motion replaces the velocity the excess was carried in, a correction's included.
        if (result.getInitialStartingVel().isKnockback()) clear();

        // The closest candidate is input-free; the circular input allowance takes it to the reported move.
        Vec3 attempted = VelocityCandidates.preCollisionVelocity(result);
        Vec3 candidate = result.getAcceptedClosestToTarget();
        Vec3 target = result.getTarget();
        double inputX = target.x - candidate.x;
        double inputZ = target.z - candidate.z;
        MainSupportingBlockData support = context.getLastTickMainSupportingBlockData();
        float clientFriction = BlockProperties.getFriction(player, support, context.getStart());
        float serverFriction = context.getLastOnGround().determinePessimistically()
                ? refusedBlockServerFriction(support, context.getStart(), clientFriction)
                : Float.NaN;

        double clientInertia;
        double serverInertia;
        double extraInput;
        if (Float.isNaN(serverFriction)) {
            clientInertia = Friction.getPlayerFriction(
                    player,
                    context.getStart(),
                    support,
                    context.getLastOnGround().determineOptimistically());
            serverInertia = clientInertia;
            extraInput = 0;
        } else {
            clientInertia = Friction.getGroundFriction(player, clientFriction);
            serverInertia = Friction.getGroundFriction(player, serverFriction);
            // moveRelative's input scales with LivingEntity#getFrictionInfluencedSpeed: more grip, more input.
            extraInput = Friction.getGroundSpeedFactor(player, serverFriction)
                            / Friction.getGroundSpeedFactor(player, clientFriction)
                    - 1;
        }

        double serverMoveX = target.x - excessX + extraInput * inputX;
        double serverMoveZ = target.z - excessZ + extraInput * inputZ;
        double advantage = Math.hypot(target.x, target.z) - Math.hypot(serverMoveX, serverMoveZ);

        // Entity#move zeroes the deltaMovement of an axis the collision clipped.
        boolean clippedX = Math.abs(attempted.x - candidate.x) > 1.0E-7;
        boolean clippedZ = Math.abs(attempted.z - candidate.z) > 1.0E-7;
        double carriedX = clippedX ? 0 : target.x * clientInertia;
        double carriedZ = clippedZ ? 0 : target.z * clientInertia;
        double serverCarriedX = clippedX ? 0 : serverMoveX * serverInertia;
        double serverCarriedZ = clippedZ ? 0 : serverMoveZ * serverInertia;
        excessX = carriedX - serverCarriedX;
        excessZ = carriedZ - serverCarriedZ;

        if (advantage <= threshold || correctionPending) return;
        flag(() -> "advantage=" + NumFormatter.formatNumberStandard(advantage));
        if (!shouldModifyPackets()) return;
        Vec3 velocity = nextTickVelocity(result, target, carriedX, carriedZ);
        if (velocity == null) return;
        double velocityX = towardZero(velocity.x, velocity.x - excessX);
        double velocityZ = towardZero(velocity.z, velocity.z - excessZ);
        if (Math.hypot(velocityX, velocityZ) < Math.hypot(velocity.x, velocity.z)) {
            sendVelocity(velocityX, velocity.y, velocityZ);
        }
    }

    /** The engine's carried velocity after this tick: the candidate nearest {@code carried}. */
    private Vec3 nextTickVelocity(PredictionResult result, Vec3 target, double carriedX, double carriedZ) {
        Vec3 nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Vec3 velocity : NextTickVelocityDeriver.findVelocitiesForNextTick(
                player, result, player.checkManager.getSimulationProcessor().getLastPrediction(), target, null)) {
            double distance = Math.hypot(velocity.x - carriedX, velocity.z - carriedZ);
            if (distance < nearestDistance) {
                nearest = velocity;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /** {@code corrected}, or zero where it points against {@code velocity}. */
    private static double towardZero(double velocity, double corrected) {
        return Math.signum(corrected) == Math.signum(velocity) ? corrected : 0;
    }

    /**
     * The server's friction at Entity#getBlockPosBelowThatAffectsMyMovement when the client stands on its own
     * prediction there, the server answered it with a less slippery block and the client has not yet applied that
     * answer; NaN otherwise.
     */
    private float refusedBlockServerFriction(MainSupportingBlockData support, Vec3 from, float clientFriction) {
        CompensatedWorld world = player.compensatedWorld;
        if (!world.hasPendingBlockPredictions()) return Float.NaN;
        BlockPos below = BlockProperties.getBlockPosBelowThatAffectsMyMovementPos(player, support, from);
        if (!world.hasAnsweredBlockPrediction(below)) return Float.NaN;
        int serverState = world.getServerSentBlockStateIdAt(below);
        if (serverState == CompensatedWorld.UNKNOWN_SERVER_STATE) return Float.NaN;
        float serverFriction = BlockProperties.getMaterialFriction(serverState);
        return serverFriction < clientFriction ? serverFriction : Float.NaN;
    }

    private void sendVelocity(double x, double y, double z) {
        if (!player.canSendPlayPackets() || !player.user.getCultConnection().clientHasLevel()) return;
        if (!player.user.isPreparingWrites()) {
            correctionPending = true;
            player.user.executeAfterWrites(() -> {
                correctionPending = false;
                sendVelocity(x, y, z);
            });
            return;
        }
        // KnockbackHandler reads this write and proves it with its trailing ping, like the server's own.
        player.user.write(new CultWrite(new ClientboundEntityMotion(player.entityID, new Vec3d(x, y, z)), false));
        correctionPending = true;
        // The client answers this ping after it has set the velocity.
        player.sendTransaction();
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> correctionPending = false);
    }

    private void clear() {
        excessX = 0;
        excessZ = 0;
    }
}
