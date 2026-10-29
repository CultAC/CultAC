package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPush;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRavager;
import ac.cult.cultac.utils.latency.ClientRavagerRoars;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Velocity writes by other ticking entities, on their actual side of LocalPlayer#tick and in that tick's order. */
public final class JavaEntityVelocityTicks {
    private JavaEntityVelocityTicks() {}

    public static List<PredVector> beforeTravel(
            CultPlayer player, SimulationContext context, List<PredVector> velocities) {
        var ticks = ticks(player, context, context.getStart(), true);
        if (ticks.isEmpty()) return velocities;
        var candidates = new ArrayList<PredVector>();
        double scale = rangeScale(ticks);
        for (PredVector velocity : velocities) {
            var transformed = JavaMovementMath.calculate(
                    player,
                    velocity,
                    () -> apply(ticks, velocity, ReachUtils.getLook(player, context.getXRot(), context.getYRot())));
            for (Vec3 vector : transformed)
                candidates.add(withScaledRange(velocity.with(vector, "entities before player"), scale));
        }
        return candidates;
    }

    public static List<Vec3> afterTravel(
            CultPlayer player, SimulationContext context, PredVector candidate, Vec3 end, Vec3 velocity) {
        if (!JavaBlockEntityPass.reportsAfterPass(context.getVersion())) return List.of(velocity);
        return afterPlayerTick(player, context, candidate, end, velocity);
    }

    /** Before 26.3, late entity ticks follow the report and are committed by CLIENT_TICK_END. */
    public static List<Vec3> afterPlayerTick(
            CultPlayer player, SimulationContext context, PredVector candidate, Vec3 end, Vec3 velocity) {
        var ticks = ticks(player, context, end, false);
        if (ticks.isEmpty()) return List.of(velocity);
        List<Vec3> moved = JavaMovementMath.calculate(
                player,
                candidate,
                () -> apply(ticks, velocity, ReachUtils.getLook(player, context.getXRot(), context.getYRot())));
        if (!(velocity instanceof PredVector ranged) || ranged.getVelocityRange() == null) return moved;
        // A carried velocity range moves with the velocity.
        double scale = rangeScale(ticks);
        var kept = new ArrayList<Vec3>(moved.size());
        for (Vec3 vector : moved) kept.add(withScaledRange(ranged.with(vector, "entities after player"), scale));
        return kept;
    }

    /**
     * How the late ticks scale a velocity range: LivingEntity#push adds one impulse to every velocity in it, and
     * FireworkRocketEntity's boost keeps half of each.
     */
    private static double rangeScale(List<Tick> ticks) {
        double scale = 1;
        for (Tick tick : ticks) if (tick.impulses == null) scale *= 0.5D;
        return scale;
    }

    private static PredVector withScaledRange(PredVector vector, double scale) {
        return scale == 1 ? vector : JavaVelocityRange.scaled(vector, new Vec3(scale, scale, scale));
    }

    private static List<Tick> ticks(CultPlayer player, SimulationContext context, Vec3 position, boolean before) {
        return ticks(player, context, position, before, null);
    }

    static List<Tick> passengerTicks(
            CultPlayer player, SimulationContext context, Vec3 position, ac.cult.cultac.utils.enums.Pose pose) {
        return ticks(player, context, position, false, pose);
    }

    private static List<Tick> ticks(
            CultPlayer player, SimulationContext context, Vec3 position, boolean before,
            ac.cult.cultac.utils.enums.Pose passengerPose) {
        if (player.isBedrockMovement()) return List.of();
        var entities = context.getEntities();
        long localOrder = entities.clientTickOrder(entities.getSelf());
        var ticks = new ArrayList<Tick>();
        boolean gliding = before && player.packetStateData.glidingBeforeClientAction != null
                ? player.packetStateData.glidingBeforeClientAction
                : context.isGliding() && (before || !context.stopsFallFlyingAfterTravel());
        if (gliding && context.getVehicle() == null)
            player.compensatedFireworks.forEachTickingRocket(rocket -> {
                long order = rocket.tickingOrder(player);
                if ((order < localOrder) == before) ticks.add(new Tick(order, null));
            });
        if (context.getVehicle() == null
                && !entities.pushOrCollideCandidates().isEmpty()
                && JavaLivingEntityPushes.playerPushable(player, context, position, gliding)) {
            var pose = passengerPose != null ? passengerPose : before
                    ? context.getPose()
                    : player.updatedPose(
                            context.getWorldData().isSwimming(),
                            context.isGliding() && !context.stopsFallFlyingAfterTravel(),
                            position.x,
                            position.y,
                            position.z);
            SimpleCollisionBox box = GetBoundingBox.getBoundingBoxFromPosAndSize(
                    position.x,
                    position.y,
                    position.z,
                    pose.width * context.getScale(),
                    pose.height * context.getScale());
            SimpleCollisionBox rootBox = new SimpleCollisionBox(), scratch = new SimpleCollisionBox();
            entities.beginMovementPositionReads();
            long phase = entities.passTickedThroughOrder();
            try {
                for (var entity : entities.pushOrCollideCandidates()) {
                    boolean boat = entity.isBoat();
                    if (!boat && !JavaLivingEntityPushes.ticksPush(entity)) continue;
                    long order = entity.tickingOrder(player);
                    if ((order < localOrder) != before
                            // A dying LivingEntity still runs aiStep/pushEntities until removal. Only the
                            // queried player's isPushable is tested by that entity's pushable-entity query.
                            || boat && entity.isDead
                            || JavaLivingEntityPushes.noPhysics(player, entity)
                            || !entities.teamCollision.allows(entity.scoreboardName, player.getName())
                            || entities.sharesRootVehicleWithSelf(entity, entities.rootOfSelf())) continue;
                    boolean rider = entities.isUntrackedRider(entity);
                    List<SimpleCollisionBox> boxes;
                    if (rider) {
                        var root = entities.rootOf(entity);
                        if (root == null || !entities.ticksInClientTick(root)) continue;
                        // The rider pushes before positionRider reseats it: from its last seat, or where the last
                        // block-entity pass left it. Neither depends on this read's place in the tick.
                        boxes = entities.untrackedRiderPushBoxes(entity, box, rootBox, scratch);
                        if (boxes.isEmpty()) continue;
                        entities.setPassTickedThroughOrder(order);
                    } else {
                        if (!entities.ticksInClientTick(entity)) continue;
                        entities.setPassTickedThroughOrder(order);
                        boxes = entity.getPossibleMovementCollisionBoxCandidates();
                    }
                    List<Vec3> impulses;
                    if (entity instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityShulker shulker) {
                        // A rider's push uses the position before positionRider, including its independent
                        // block-entity shove. Its shell is shaped at that position, before this tick's peek.
                        impulses = shulker.livingPushImpulses(rider ? boxes : null, box, position);
                    } else {
                        var physicalBoxes = new ArrayList<SimpleCollisionBox>(boxes.size());
                        boolean touches = !rider;
                        for (var candidate : boxes) {
                            var physical = JavaLivingEntityPushes.physicalBox(player, entity, candidate);
                            touches |= JavaLivingEntityPushes.intersects(physical, box);
                            physicalBoxes.add(physical);
                        }
                        if (!touches) continue;
                        if (boat) {
                            var pushes = new ArrayList<Vec3>();
                            EntityPush.addPossibleBoatPushes(pushes, physicalBoxes, box, position);
                            impulses = pushes;
                        } else impulses = JavaLivingEntityPushes.impulses(physicalBoxes, box, position);
                    }
                    if (!impulses.isEmpty()
                            && !(impulses.size() == 1 && impulses.getFirst().equals(Vec3.ZERO)))
                        ticks.add(new Tick(order, List.copyOf(impulses)));
                }
            } finally {
                entities.setPassTickedThroughOrder(phase);
                entities.endMovementPositionReads();
            }
        }
        addRoars(player, context, position, before, localOrder, ticks, passengerPose);
        ticks.sort(Comparator.comparingLong(Tick::order));
        return ticks;
    }

    private static void addRoars(
            CultPlayer player,
            SimulationContext context,
            Vec3 position,
            boolean before,
            long localOrder,
            List<Tick> ticks,
            ac.cult.cultac.utils.enums.Pose passengerPose) {
        if (!PacketEntityRavager.usesRoarCountdown(player.getClientVersion())) return;
        var entities = context.getEntities();
        var actor = context.getVehicle() == null ? entities.getSelf() : context.getVehicle();
        if (!actor.isLivingEntity() || !actor.actionAlive || actor.isDead || actor instanceof PacketEntityRavager)
            return;
        var pose = passengerPose != null ? passengerPose : before
                ? context.getPose()
                : player.updatedPose(
                        context.getWorldData().isSwimming(),
                        context.isGliding() && !context.stopsFallFlyingAfterTravel(),
                        position.x,
                        position.y,
                        position.z);
        SimpleCollisionBox target = GetBoundingBox.getBoundingBoxFromPosAndSize(
                position.x,
                position.y,
                position.z,
                context.getVehicle() == null ? pose.width * context.getScale() : context.getMaxWidth(),
                context.getVehicle() == null ? pose.height * context.getScale() : context.getMaxHeight());
        entities.beginMovementPositionReads();
        long phase = entities.passTickedThroughOrder();
        try {
            for (var entity : entities.entityMap.values()) {
                if (!(entity instanceof PacketEntityRavager ravager)
                        || !ravager.roarsThisTick(player)
                        || !entities.ticksInClientTick(ravager)) continue;
                long order = ravager.tickingOrder(player);
                if ((order < localOrder) != before) continue;
                entities.setPassTickedThroughOrder(order);
                var impulses = ClientRavagerRoars.impulses(
                        ravager.getPossibleMovementCollisionBoxCandidates(), target, position);
                if (!(impulses.size() == 1 && impulses.getFirst().equals(Vec3.ZERO)))
                    ticks.add(new Tick(order, impulses));
            }
        } finally {
            entities.setPassTickedThroughOrder(phase);
            entities.endMovementPositionReads();
        }
    }

    record Tick(long order, List<Vec3> impulses) {}

    private static List<Vec3> apply(List<Tick> ticks, Vec3 velocity, Vector3dm look) {
        List<Vec3> candidates = List.of(velocity);
        for (Tick tick : ticks) {
            var next = new ArrayList<Vec3>();
            for (Vec3 start : candidates) {
                if (tick.impulses == null) next.add(boost(start, look));
                else {
                    for (Vec3 impulse : tick.impulses) {
                        Vec3 pushed = start.add(impulse);
                        if (!next.contains(pushed)) next.add(pushed);
                    }
                }
            }
            candidates = next;
        }
        return candidates;
    }

    public static Vec3 boost(Vec3 movement, Vector3dm look) {
        return movement.add(
                look.getX() * 0.1D + (look.getX() * 1.5D - movement.x) * 0.5D,
                look.getY() * 0.1D + (look.getY() * 1.5D - movement.y) * 0.5D,
                look.getZ() * 0.1D + (look.getZ() * 1.5D - movement.z) * 0.5D);
    }
}
