package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;

/** The tracked pushers' Level#tickBlockEntities, after their interpolation and LivingEntity#pushEntities. */
public final class JavaRemoteBlockEntityPass {
    private JavaRemoteBlockEntityPass() {}

    public static void tick(CultPlayer player) {
        if (player.isBedrockMovement()) return;
        int riderPass = player.compensatedEntities.beginRiderPushPass();
        var pass = player.compensatedWorld.lastBlockEntityPass();
        if (pass.movers().isEmpty()) return;
        var entities = player.compensatedEntities;
        var selfRoot = entities.rootOfSelf();
        var serverRoot = entities.serverVehicleRoot();
        java.util.List<PacketEntity> actors = entities.pushOrCollideCandidates();
        if (!entities.fishingHooks().isEmpty()) {
            actors = new java.util.ArrayList<>(actors);
            actors.addAll(entities.fishingHooks());
        }
        for (var actor : actors) {
            if (actor instanceof PacketEntityHook hook && hook.isBobbing()) {
                tickBobbing(player, hook, pass);
                continue;
            }
            // Controlled roots receive the authoritative local pass. A passive own root still receives
            // the same non-authoritative pass as other roots; its passengers keep their earlier seats.
            // Other riders are never ticked or positioned here: recordRiders only notes where the pass
            // leaves the living ones, for their next push. PistonMovingBlockEntity#moveCollidedEntities
            // selects every non-spectator, so a dying entity, which pushes until it is removed, is moved too.
            if (actor == entities.getSelf()
                    || actor.getRiding() != null
                    || (actor == selfRoot || actor == serverRoot)
                            && entities.vehicles.isLocallyTickedVelocityRoot(actor)
                    || actor.newPacketLocation == null
                    || !actor.clientPhysicalPositionExact
                    || actor.oldPacketLocation != null) continue;
            SimpleCollisionBox box = actor.getPossibleMovementCollisionBoxes();
            if (!mayReach(pass, box)) {
                // The pass misses this root, but may still move a rider seated on or beside it.
                if (!actor.passengers.isEmpty() && !actor.hasPistonPatchStates())
                    recordRidersOfUnmovedRoot(player, actor, box, pass, riderPass);
                continue;
            }
            if (player.getClientVersion() == ClientVersion.V_1_21_9) {
                actor.ensurePistonPatchStates();
                actor.runPistonPatchStates(() -> tickActor(player, actor, pass, riderPass));
            } else {
                tickActor(player, actor, pass, riderPass);
            }
        }
    }

    private static void tickActor(CultPlayer player, PacketEntity actor, ClientBlockEntityPass pass, int riderPass) {
        if (actor.runPhysicsPaths(() -> tickActor(player, actor, pass, riderPass))) return;
        if (!actor.clientPhysicalPositionExact || actor.oldPacketLocation != null) return;
        JavaPassState previous = actor.blockEntityState;
        Vec3 position = actor.clientPhysicalPosition;
        var start = new JavaPassState(
                position,
                actor.deltaMovement,
                actor.onGround,
                previous == null ? new MainSupportingBlockData(null, actor.onGround) : previous.support(),
                previous == null ? null : previous.stuckSpeed(),
                previous == null ? 0 : previous.fallDistance(),
                previous == null ? ClientPistonDeltas.NONE : previous.pistonDeltas(),
                false);
        var replay = JavaBlockEntityPass.runEntity(player, actor, pass, start);
        if (actor instanceof PacketEntityStrider strider) strider.commitFluidHeights(replay.fluidHeights());
        JavaPassState result = replay.state();
        actor.blockEntityState = result;
        actor.deltaMovement = result.velocity();
        actor.onGround = result.onGround();
        if (!actor.passengers.isEmpty()) {
            var entities = player.compensatedEntities;
            // The riders of the player's own riding trees are positioned, and never push it.
            if (actor != entities.selfRidingRoot() && actor != entities.serverVehicleRoot()) {
                SimpleCollisionBox seatedFrom =
                        GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, actor);
                recordRiders(
                        player,
                        actor,
                        seatedFrom,
                        !result.position().equals(position),
                        pass,
                        riderPass,
                        actor.pistonMovementPatch());
            }
        }
    }

    /**
     * PistonMovingBlockEntity#moveCollidedEntities and ShulkerBoxBlockEntity#moveCollidedEntities move every entity in
     * their area, riders included, after this tick's Entity#positionRider seated them. A rider keeps that place until
     * its next Entity#rideTick, whose LivingEntity#pushEntities runs before positionRider reseats it. For each living
     * rider of {@code vehicle}'s tree, records the place this pass leaves it: its seat on the root at {@code rootStart},
     * moved by the pass when the pass may reach it. Riders of a root the pass neither moved nor reaches keep their seat,
     * which the push reader derives without a record.
     */
    private static void recordRiders(
            CultPlayer player,
            PacketEntity vehicle,
            SimpleCollisionBox rootStart,
            boolean rootMoved,
            ClientBlockEntityPass pass,
            int riderPass,
            JavaMovementPatch patch) {
        var passengers = vehicle.passengers;
        for (int i = 0; i < passengers.size(); i++) {
            PacketEntity rider = passengers.get(i);
            if (JavaLivingEntityPushes.ticksPush(rider)) {
                SimpleCollisionBox seat = player.compensatedEntities.untrackedRiderSeatOn(rider, rootStart);
                if (seat != null) {
                    boolean reached = mayReach(pass, seat);
                    if (reached) seat = movedRider(player, rider, seat, pass, patch);
                    if (reached || rootMoved) rider.recordRiderPushBox(riderPass, seat);
                }
            }
            if (!rider.passengers.isEmpty()) recordRiders(player, rider, rootStart, rootMoved, pass, riderPass, patch);
        }
    }

    /**
     * {@link #recordRiders} for a root the pass does not reach, once a mover's area may meet its rider stack: every
     * seat lies within the summed vehicle and rider widths beside the root, and its riders' heights (with a block of
     * attachment slack per seat) above or below it.
     */
    private static void recordRidersOfUnmovedRoot(
            CultPlayer player,
            PacketEntity root,
            SimpleCollisionBox rootBox,
            ClientBlockEntityPass pass,
            int riderPass) {
        var entities = player.compensatedEntities;
        if (root == entities.selfRidingRoot() || root == entities.serverVehicleRoot()) return;
        double side = riderSideReach(player, root, 0);
        double up = riderHeightReach(player, root, 0);
        if (!mayReach(
                pass,
                rootBox.minX - side,
                rootBox.minY - up,
                rootBox.minZ - side,
                rootBox.maxX + side,
                rootBox.maxY + up,
                rootBox.maxZ + side)) return;
        Vec3 at = root.clientPhysicalPosition;
        recordRiders(
                player,
                root,
                GetBoundingBox.getPacketEntityBoundingBox(player, at.x, at.y, at.z, root),
                false,
                pass,
                riderPass,
                root.pistonMovementPatch());
    }

    // Each hop seats a rider within its vehicle's width plus its own of the vehicle's centre.
    private static double riderSideReach(CultPlayer player, PacketEntity vehicle, int depth) {
        if (depth > player.compensatedEntities.entityMap.size()) return Double.POSITIVE_INFINITY;
        double reach = 0.0;
        var passengers = vehicle.passengers;
        for (int i = 0; i < passengers.size(); i++) {
            PacketEntity rider = passengers.get(i);
            reach = Math.max(
                    reach,
                    BoundingBoxSize.getWidth(player, vehicle)
                            + BoundingBoxSize.getWidth(player, rider)
                            + riderSideReach(player, rider, depth + 1));
        }
        return reach;
    }

    private static double riderHeightReach(CultPlayer player, PacketEntity vehicle, int depth) {
        if (depth > player.compensatedEntities.entityMap.size()) return Double.POSITIVE_INFINITY;
        double reach = 0.0;
        var passengers = vehicle.passengers;
        for (int i = 0; i < passengers.size(); i++) {
            PacketEntity rider = passengers.get(i);
            reach = Math.max(
                    reach,
                    1.0
                            + Math.abs(BoundingBoxSize.getHeight(player, rider))
                            + riderHeightReach(player, rider, depth + 1));
        }
        return reach;
    }

    /** Where the pass leaves a rider seated at {@code seat}; it moves on its own, apart from its vehicle. */
    private static SimpleCollisionBox movedRider(
            CultPlayer player,
            PacketEntity rider,
            SimpleCollisionBox seat,
            ClientBlockEntityPass pass,
            JavaMovementPatch patch) {
        Vec3 start = new Vec3((seat.minX + seat.maxX) / 2.0, seat.minY, (seat.minZ + seat.maxZ) / 2.0);
        // Entity#rideTick zeroes a passenger's deltaMovement, and a remote rider never travels.
        var state = new JavaPassState(
                start,
                Vec3.ZERO,
                rider.onGround,
                new MainSupportingBlockData(null, rider.onGround),
                null,
                0,
                ClientPistonDeltas.NONE,
                false);
        var replay = JavaBlockEntityPass.runDetachedEntity(player, rider, pass, state, patch);
        Vec3 end = replay.state().position();
        SimpleCollisionBox box = end.equals(start)
                ? seat
                : GetBoundingBox.getPacketEntityBoundingBox(player, end.x, end.y, end.z, rider);
        rider.recordRiderInterpolationPass(box, replay.interpolation(), replay.oldInterpolation());
        return box;
    }

    /**
     * A BOBBING hook's hidden Y (and any earlier possible shove) decides whether a block entity reaches it: replay
     * the pass from the possible hook position nearest the first mover that can reach it, and leave that shove
     * possible rather than certain.
     */
    private static void tickBobbing(CultPlayer player, PacketEntityHook hook, ClientBlockEntityPass pass) {
        SimpleCollisionBox column = hook.bobbingColumn(player);
        SimpleCollisionBox range = hook.pullRange(player);
        Vec3 at = hook.clientPhysicalPosition;
        double height = column.maxY - column.minY - (range.maxY - range.minY);
        for (var mover : pass.movers()) {
            SimpleCollisionBox area = area(mover);
            if (area == null || !area.isIntersected(column)) continue;
            Vec3 start = new Vec3(
                    clamp((area.minX + area.maxX) / 2, at.x + range.minX, at.x + range.maxX),
                    clamp((area.minY + area.maxY - height) / 2, at.y + range.minY, at.y + range.maxY),
                    clamp((area.minZ + area.maxZ) / 2, at.z + range.minZ, at.z + range.maxZ));
            var state = new JavaPassState(
                    start,
                    hook.deltaMovement,
                    hook.onGround,
                    new MainSupportingBlockData(null, hook.onGround),
                    null,
                    0,
                    ClientPistonDeltas.NONE,
                    false);
            Vec3 end = hook.withoutCommitting(() -> JavaBlockEntityPass.runEntity(player, hook, pass, state)
                    .state()
                    .position());
            hook.addPush(end.subtract(start));
            return;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static SimpleCollisionBox area(ClientBlockEntityPass.Mover mover) {
        if (mover instanceof ClientBlockEntityPass.Piston piston)
            return new SimpleCollisionBox(piston.pos()).expand(1.0);
        if (mover instanceof ClientBlockEntityPass.ShulkerBox box) return box.area();
        if (mover instanceof ClientBlockEntityPass.ShulkerPeek peek) return peek.area();
        return null;
    }

    private static boolean mayReach(ClientBlockEntityPass pass, SimpleCollisionBox box) {
        return mayReach(pass, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /** Whether a mover's area meets the box with these bounds. Allocates nothing: it runs for every pusher each tick. */
    private static boolean mayReach(
            ClientBlockEntityPass pass, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        var movers = pass.movers();
        for (int i = 0; i < movers.size(); i++) {
            var mover = movers.get(i);
            if (mover instanceof ClientBlockEntityPass.Piston piston) {
                // A piston moves within its block and the adjacent block; the honey carry query reaches 1.500001 high.
                var pos = piston.pos();
                if (areaMeets(
                        pos.getX() - 1.0,
                        pos.getY() - 1.0,
                        pos.getZ() - 1.0,
                        pos.getX() + 2.0,
                        pos.getY() + 2.0,
                        pos.getZ() + 2.0,
                        minX,
                        minY,
                        minZ,
                        maxX,
                        maxY,
                        maxZ)) return true;
            } else {
                SimpleCollisionBox area = ((ClientBlockEntityPass.ShulkerBox) mover).area();
                if (areaMeets(
                        area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ, minX, minY, minZ, maxX, maxY,
                        maxZ)) return true;
            }
        }
        return false;
    }

    // SimpleCollisionBox#isIntersected of the area with the box.
    private static boolean areaMeets(
            double areaMinX,
            double areaMinY,
            double areaMinZ,
            double areaMaxX,
            double areaMaxY,
            double areaMaxZ,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        double epsilon = SimpleCollisionBox.COLLISION_EPSILON;
        return maxX - epsilon > areaMinX
                && minX + epsilon < areaMaxX
                && maxY - epsilon > areaMinY
                && minY + epsilon < areaMaxY
                && maxZ - epsilon > areaMinZ
                && minZ + epsilon < areaMaxZ;
    }
}
