package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;

public class EntityPush implements UncertaintyHandler {
    private static final double MAX_HORIZONTAL_IMPULSE_PER_ENTITY = Math.sqrt(2.0D) * 0.05D;

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            PredVector start,
            Vec3 end) {
        WorldData world = context.getWorldData();
        SimpleCollisionBox loading = world.getLoadingLevelPushes();
        if (loading != null) {
            start = start.withXYZOrSelf(
                    CultMath.clamp(end.x, start.x + loading.minX, start.x + loading.maxX),
                    start.y,
                    CultMath.clamp(end.z, start.z + loading.minZ, start.z + loading.maxZ),
                    "loading level pushes");
        }
        int carried = world.getNumCarriedPushing();
        // ClientPacketListener#handleSetEntityMotion replaces the delta movement that carries last tick's push.
        CarriedPush carriedPush = world.getCarriedPush();
        if (carried == 0 || carriedPush.isIdentity() && !start.isEntityMotion()) {
            return handleEntityPush(start, end, world.getNumPushing());
        }
        PredVector pushed =
                start.isEntityMotion() ? start : carriedPush.push(start, end, maxHorizontalImpulse(carried));
        return handleEntityPush(pushed, end, world.getNumPushing() - carried);
    }

    /**
     * Adds to {@code pushes} the x/z range of Entity#push's impulse on an entity from a pusher whose centre is
     * {@code minDx..maxDx}, {@code minDz..maxDz} from its own. The range includes no push: EntitySelector#pushableBy's
     * team collision rules and LivingEntity#isPushable are not modelled.
     */
    public static void addPushRange(SimpleCollisionBox pushes, double minDx, double maxDx, double minDz, double maxDz) {
        double maxAxis =
                Math.max(Math.max(Math.abs(minDx), Math.abs(maxDx)), Math.max(Math.abs(minDz), Math.abs(maxDz)));
        if (maxAxis < 0.01F) return;
        double minAxis = Math.max(minAbs(minDx, maxDx), minAbs(minDz, maxDz));
        // Entity#push scales each axis by 1/sqrt(absMax) and min(1, 1/sqrt(absMax)), which fall as absMax grows.
        double maxScale = pushScale(Math.max(minAxis, 0.01F));
        double minScale = pushScale(maxAxis);
        double minX = -0.05F * Math.max(maxDx * minScale, maxDx * maxScale);
        double maxX = -0.05F * Math.min(minDx * minScale, minDx * maxScale);
        double minZ = -0.05F * Math.max(maxDz * minScale, maxDz * maxScale);
        double maxZ = -0.05F * Math.min(minDz * minScale, minDz * maxScale);
        pushes.minX += Math.min(minX, 0.0D);
        pushes.maxX += Math.max(maxX, 0.0D);
        pushes.minZ += Math.min(minZ, 0.0D);
        pushes.maxZ += Math.max(maxZ, 0.0D);
    }

    private static double pushScale(double absMax) {
        double root = Math.sqrt(absMax);
        return 1.0D / root * Math.min(1.0D, 1.0D / root);
    }

    private static double minAbs(double min, double max) {
        return min <= 0.0D && max >= 0.0D ? 0.0D : Math.min(Math.abs(min), Math.abs(max));
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

    public static void addVehiclePushCandidates(
            CultPlayer player, List<PredVector> start, SimulationContext state, PredictionResult lastResult) {
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
        boolean pushableLastTick = lastResult.getSimulationContext().getVehicle() == null
                && lastResult.getSimulationContext().getWorldData().isPushable();
        boolean pushableThisTick = state.getWorldData().isPushable();

        // ClientLevel#tickEntities iterates client tick-list order. A remote boat that
        // ticks before LocalPlayer can push the player before LocalPlayer#sendPosition
        // in the same tick; a later-ticking remote boat pushes after sendPosition and
        // is carried into the following player-body tick.
        for (PacketEntity entity : state.getEntities().boats()) {
            if (!entity.isBoat()
                    || entity.isDead
                    || entity.hasPassenger(player.compensatedEntities.getSelf())
                    || state.getEntities().isUntrackedRider(entity)) {
                continue;
            }

            boolean pushesBeforeCurrentPlayerTick =
                    pushableThisTick && entity.getClientTickOrder() < localPlayerTickOrder;
            boolean pushedAfterPreviousPlayerTick =
                    pushableLastTick && entity.getClientTickOrder() > localPlayerTickOrder;
            if (!pushesBeforeCurrentPlayerTick && !pushedAfterPreviousPlayerTick) {
                continue;
            }

            addPossibleBoatPushes(
                    pushCandidates, getBoatPushCollisionCandidatesForNextTick(entity), playerBox, state.getStart());
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
    private static void addPossibleBoatPushes(
            List<Vec3> pushCandidates,
            List<SimpleCollisionBox> boatBoxes,
            SimpleCollisionBox playerBox,
            Vec3 pushedPosition) {
        SimpleCollisionBox pushBox = new SimpleCollisionBox();
        for (int i = 0; i < boatBoxes.size(); i++) {
            SimpleCollisionBox boatBox = boatBoxes.get(i);
            pushBox.set(boatBox).expand(0.2D, -0.01D, 0.2D);
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
        return one.minX < two.maxX
                && one.maxX > two.minX
                && one.minY < two.maxY
                && one.maxY > two.minY
                && one.minZ < two.maxZ
                && one.maxZ > two.minZ;
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
