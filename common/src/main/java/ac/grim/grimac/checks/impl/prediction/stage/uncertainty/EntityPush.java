package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class EntityPush implements UncertaintyHandler {
    private static final double MAX_HORIZONTAL_IMPULSE_PER_ENTITY = Math.sqrt(2.0D) * 0.05D;

    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        return handleEntityPush(start, end, context.getWorldData().getNumColliding());
    }

    public static PredVector handleEntityPush(PredVector start, Vec3 end, int collidingEntities) {
        return UncertaintyHelper.handleCircular(start, end, maxHorizontalImpulse(collidingEntities));
    }

    public static double maxHorizontalImpulse(int collidingEntities) {
        if (collidingEntities <= 0) {
            return 0.0D;
        }

        // MCP-Reborn LivingEntity#pushEntities calls Entity#push for each
        // colliding entity. Entity#push clamps each horizontal component to
        // at most 0.05, so the tight horizontal vector bound is sqrt(2)*0.05.
        return collidingEntities * MAX_HORIZONTAL_IMPULSE_PER_ENTITY;
    }

    public static void addVehiclePushCandidates(GrimPlayer player, List<PredVector> start, SimulationContext state, PredictionResult lastResult) {
        if (lastResult == null
                || lastResult.getSimulationContext() == null
                || state.getVehicle() != null
                || start.isEmpty()) {
            return;
        }

        List<Vec3> pushCandidates = new ArrayList<>();
        SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                state.getStart().x, state.getStart().y, state.getStart().z, 0.6f, 1.8f);
        long localPlayerTickOrder = player.compensatedEntities.getSelf().getClientTickOrder();
        boolean previousTickPlayerBodyMovement = lastResult.getSimulationContext().getVehicle() == null;

        // ClientLevel#tickEntities iterates client tick-list order. A remote boat that
        // ticks before LocalPlayer can push the player before LocalPlayer#sendPosition
        // in the same tick; a later-ticking remote boat pushes after sendPosition and
        // is carried into the following player-body tick.
        for (PacketEntity entity : state.getEntities().entityMap.values()) {
            if (entity.isDead
                    || entity.hasPassenger(player.compensatedEntities.getSelf())
                    || !EntityTypeUtil.isBoat(entity.type)) {
                continue;
            }

            boolean pushesBeforeCurrentPlayerTick = entity.getClientTickOrder() < localPlayerTickOrder;
            boolean pushedAfterPreviousPlayerTick = previousTickPlayerBodyMovement
                    && entity.getClientTickOrder() > localPlayerTickOrder;
            if (!pushesBeforeCurrentPlayerTick && !pushedAfterPreviousPlayerTick) {
                continue;
            }

            addPossibleBoatPushes(pushCandidates, getBoatPushCollisionCandidatesForNextTick(entity), playerBox, state.getStart());
        }

        if (pushCandidates.isEmpty()) {
            return;
        }

        List<PredVector> transformed = new ArrayList<>(start.size() * (pushCandidates.size() + 1));
        transformed.addAll(start);
        for (PredVector vector : start) {
            for (Vec3 push : pushCandidates) {
                transformed.add(vector.add(push, "vehicle push"));
            }
        }
        start.clear();
        start.addAll(transformed);
    }

    private static List<SimpleCollisionBox> getBoatPushCollisionCandidatesForNextTick(PacketEntity boat) {
        return boat.getPossibleMovementCollisionBoxCandidatesAfterClientTick(true);
    }

    // TODO: Convert to more pure uncertainty
    private static void addPossibleBoatPushes(List<Vec3> pushCandidates, List<SimpleCollisionBox> boatBoxes,
                                              SimpleCollisionBox playerBox, Vec3 pushedPosition) {
        for (SimpleCollisionBox boatBox : boatBoxes) {
            SimpleCollisionBox pushBox = boatBox.copy().expand(0.2D, -0.01D, 0.2D);
            if (!vanillaIntersects(pushBox, playerBox) || playerBox.minY > boatBox.minY) {
                continue;
            }

            Vec3 impulse = getBoatPushImpulse(boatBox, pushedPosition);
            if (impulse != null) {
                addDistinctPush(pushCandidates, impulse);
            }
        }
    }

    private static void addDistinctPush(List<Vec3> pushes, Vec3 impulse) {
        for (Vec3 existing : pushes) {
            if (existing.distanceToSqr(impulse) <= 1.0E-12D) {
                return;
            }
        }
        pushes.add(impulse);
    }

    private static boolean vanillaIntersects(SimpleCollisionBox one, SimpleCollisionBox two) {
        return one.minX < two.maxX && one.maxX > two.minX
                && one.minY < two.maxY && one.maxY > two.minY
                && one.minZ < two.maxZ && one.maxZ > two.minZ;
    }

    private static Vec3 getBoatPushImpulse(SimpleCollisionBox boatBox, Vec3 pushedPosition) {
        double boatX = (boatBox.minX + boatBox.maxX) * 0.5D;
        double boatZ = (boatBox.minZ + boatBox.maxZ) * 0.5D;
        double x = pushedPosition.x - boatX;
        double z = pushedPosition.z - boatZ;
        double maxComponent = Math.max(Math.abs(x), Math.abs(z));
        if (maxComponent < 0.01F) {
            return null;
        }

        double root = Math.sqrt(maxComponent);
        x /= root;
        z /= root;
        double scale = Math.min(1.0D, 1.0D / root);
        return new Vec3(x * scale * 0.05F, 0.0D, z * scale * 0.05F);
    }
}
