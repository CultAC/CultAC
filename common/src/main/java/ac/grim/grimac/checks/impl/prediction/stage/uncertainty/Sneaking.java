package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.DesyncStatus;
import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.StuckEdgeData;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.VectorUtils;
import ac.grim.grimac.utils.nmsutil.BlockProperties;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.network.protocol.ClientVersion;
import net.minecraft.world.phys.Vec3;

public class Sneaking implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        StuckEdgeData lastOnEdge = lastResult == null ? null : lastResult.getSimulationContext().getWorldData().getSneak();
        StuckEdgeData thisEdge = result.getSimulationContext().getWorldData().getSneak();

        if ((lastOnEdge != null && lastOnEdge.isOnEdge()) || thisEdge.isOnEdge()) {
            float blockUnderPlayer = BlockProperties.getFriction(player, context.getLastTickMainSupportingBlockData(), start);
            double friction = blockUnderPlayer * 0.91f;

            // So, this velocity is built up while on the ground, but due to buggy sneaking behaviour
            // we should assume that the player gained all their velocity when on the ground.
            DesyncStatus lastOnGround = result.getSimulationContext().getLastOnGround();
            result.getSimulationContext().getWorldData().setLastOnGround(DesyncStatus.UNKNOWN);
            // Get the speed when the player is on the ground
            double maxSpeed = context.getMaxSpeed(player) * 1.3;
            result.getSimulationContext().getWorldData().setLastOnGround(lastOnGround);

            Vec3 from = result.getSimulationContext().getStart();
            SimpleCollisionBox oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(from.x, from.y, from.z, 0.6f, 0.6f);
            boolean isSlowed = result.getSimulationContext().getVersion().isOlderThanOrEquals(ClientVersion.V_1_14) || Collisions.isEmpty(player, oldBox);

            // Thanks Physiq for this terminal velocity formula
            double multiplier = lastOnEdge != null && lastOnEdge.isZeroHorizBug() ? 1.3 : isSlowed ? 0.3 + Math.min(context.getSwiftSneakLevel() * 0.15, 0.7) : 1;
            double sprintingMovementHidden = (friction * maxSpeed * multiplier) / (1 - friction);

            SimpleCollisionBox collide = new SimpleCollisionBox(start, start);


            SimpleCollisionBox hiddenMovement = lastResult == null ? null : lastResult.getRealitiesExtent();
            if (hiddenMovement != null) {
                hiddenMovement.maxY = start.y;
                hiddenMovement.minY = start.y;
                collide = collide.union(hiddenMovement);
            }

            collide.expand(sprintingMovementHidden);
            collide.minY = start.y;
            collide.maxY = start.y;
            start = start.with(VectorUtils.cutBoxToVector(end, collide), "Sneaking hidden");

            boolean isNorth = thisEdge.isNorth() || (lastOnEdge != null && lastOnEdge.isNorth());
            boolean isSouth = thisEdge.isSouth() || (lastOnEdge != null && lastOnEdge.isSouth());
            boolean isEast = thisEdge.isEast() || (lastOnEdge != null && lastOnEdge.isEast());
            boolean isWest = thisEdge.isWest() || (lastOnEdge != null && lastOnEdge.isWest());

            if (isNorth && start.z <= 0) { // -Z
                double bestZ = GrimMath.clamp(end.z, start.z, 0);
                start = start.withZ(bestZ, "Sneaking");
            }
            if (isSouth && start.z >= 0) { // +Z
                double bestZ = GrimMath.clamp(end.z, 0, start.z);
                start = start.withZ(bestZ, "Sneaking");
            }
            if (isWest && start.x <= 0) { // -X
                double bestX = GrimMath.clamp(end.x, start.x, 0);
                start = start.withX(bestX, "Sneaking");
            }
            if (isEast && start.x >= 0) { // +X
                double bestX = GrimMath.clamp(end.x, 0, start.x);
                start = start.withX(bestX, "Sneaking");
            }
        }

        return start;
    }
}
