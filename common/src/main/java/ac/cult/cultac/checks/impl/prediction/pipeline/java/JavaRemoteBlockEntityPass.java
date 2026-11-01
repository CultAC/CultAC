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
import java.util.List;

/** The tracked pushers' Level#tickBlockEntities, after their interpolation and LivingEntity#pushEntities. */
public final class JavaRemoteBlockEntityPass {
    private JavaRemoteBlockEntityPass() {}

    public static void tick(CultPlayer player) {
        if (player.isBedrockMovement()) return;
        int riderPass = player.compensatedEntities.beginRiderPushPass();
        var pass = player.compensatedWorld.lastBlockEntityPass();
        var prediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        var selected = prediction == null ? null : JavaMovementEngine.passAfterTravel(prediction);
        var path = selected == null ? null : selected.result().path();
        player.compensatedEntities.prepareLocalBlockEntityPath(
                path != null
                                && path.pass().gameTime() == pass.gameTime()
                                && prediction
                                                .getSimulationContext()
                                                .getItemUse()
                                                .tick()
                                        == player.packetStateData.acceptedClientTick
                        ? path
                        : null);
        if (pass.movers().isEmpty()) return;
        var entities = player.compensatedEntities;
        var selfRoot = entities.rootOfSelf();
        var serverRoot = entities.serverVehicleRoot();
        java.util.List<PacketEntity> actors = entities.pushOrCollideCandidates();
        if (!entities.fishingHooks().isEmpty()) {
            actors = new java.util.ArrayList<>(actors);
            actors.addAll(entities.fishingHooks());
        }
        // The roots the pass reaches move together, mover by mover in client order.
        List<JavaBlockEntityPass.EntityStart> moved = null;
        for (var actor : actors) {
            if (actor instanceof PacketEntityHook hook) {
                pushFollowedHook(player, hook, pass);
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
                if (!actor.passengers.isEmpty()) recordRidersOfUnmovedRoot(player, actor, box, pass, riderPass);
                continue;
            }
            if (moved == null) moved = new java.util.ArrayList<>(2);
            moved.add(new JavaBlockEntityPass.EntityStart(actor, start(actor)));
        }
        // PistonMovingBlockEntity#moveCollidedEntities moves each rider on its own, so a root the player neither
        // pushes nor collides with still leaves its riders to the pass.
        if (entities.hasTrackedPassengers()) {
            for (PacketEntity root : entities.entityMap.values()) {
                if (root.passengers.isEmpty() || root.getRiding() != null || root.mayPushOrCollide()) continue;
                recordRidersOfUnmovedRoot(player, root, root.getPossibleMovementCollisionBoxes(), pass, riderPass);
            }
        }
        if (moved == null) return;
        var results = JavaBlockEntityPass.runEntities(player, moved, pass);
        for (int i = 0; i < moved.size(); i++) commit(player, moved.get(i).actor(), results.get(i), pass, riderPass);
    }

    /** The Entity fields the root carries into the pass. */
    private static JavaPassState start(PacketEntity actor) {
        JavaPassState previous = actor.blockEntityState;
        return new JavaPassState(
                actor.clientPhysicalPosition,
                actor.deltaMovement,
                actor.onGround,
                previous == null ? new MainSupportingBlockData(null, actor.onGround) : previous.support(),
                previous == null ? null : previous.stuckSpeed(),
                previous == null ? 0 : previous.fallDistance(),
                previous == null ? ClientPistonDeltas.NONE : previous.pistonDeltas(),
                false);
    }

    /** Keeps what the pass left the root, and notes where it leaves the root's riders. */
    private static void commit(
            CultPlayer player,
            PacketEntity actor,
            JavaBlockEntityPass.Result replay,
            ClientBlockEntityPass pass,
            int riderPass) {
        if (actor instanceof PacketEntityStrider strider) strider.commitFluidHeights(replay.fluidHeights());
        JavaPassState result = replay.state();
        actor.blockEntityState = result;
        actor.deltaMovement = result.velocity();
        actor.onGround = result.onGround();
        if (!actor.passengers.isEmpty()) {
            var entities = player.compensatedEntities;
            // The riders of the player's own riding trees are positioned, and never push it.
            if (actor != entities.selfRidingRoot() && actor != entities.serverVehicleRoot()) {
                recordRiders(player, actor, pass, riderPass, new SimpleCollisionBox());
            }
        }
    }

    /**
     * Block entities move each rider independently from its committed seat. Untouched riders already retain their
     * seat; snapshot and replay only when a candidate is reached, preserving every existing candidate in that case.
     */
    private static void recordRiders(
            CultPlayer player,
            PacketEntity vehicle,
            ClientBlockEntityPass pass,
            int riderPass,
            SimpleCollisionBox scratch) {
        var passengers = vehicle.passengers;
        for (int i = 0; i < passengers.size(); i++) {
            PacketEntity rider = passengers.get(i);
            int count = rider.possibleMovementCollisionBoxCandidateCount();
            boolean reached = false;
            for (int candidate = 0; candidate < count && !reached; candidate++) {
                rider.positionCollisionBoxCandidate(candidate, scratch);
                reached = mayReach(pass, rider.movementBoxAt(scratch));
            }
            if (reached) {
                // Freeze the inputs before recording output: later reads can otherwise see this pass's first result.
                var seats = new java.util.ArrayList<SimpleCollisionBox>(count);
                for (int candidate = 0; candidate < count; candidate++)
                    seats.add(rider.positionCollisionBoxCandidate(candidate, new SimpleCollisionBox()));
                List<ClientPistonDeltas> held = rider.riderPistonDeltas;
                var after = new java.util.ArrayList<ClientPistonDeltas>(count);
                for (int candidate = 0; candidate < count; candidate++) {
                    SimpleCollisionBox original = seats.get(candidate);
                    rider.movementBoxAt(scratch.set(original));
                    boolean moves = mayReach(pass, scratch);
                    for (ClientPistonDeltas deltas : heldDeltas(rider, held, count, candidate)) {
                        Moved moved = moves ? movedRider(player, rider, original, pass, deltas) : null;
                        rider.recordRiderPushBox(riderPass, moved == null ? original : moved.box());
                        after.add(moved == null ? deltas : moved.deltas());
                    }
                }
                rider.riderPistonDeltas = List.copyOf(after);
            }
            if (!rider.passengers.isEmpty()) recordRiders(player, rider, pass, riderPass, scratch);
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
                        rootBox.maxZ + side)
                && !heldRiderMayReach(player, root, pass, rootBox, 0)) return;
        recordRiders(player, root, pass, riderPass, rootBox);
    }

    /** A non-ticking rider can keep its old seat after a packet moved its parent arbitrarily far away. */
    private static boolean heldRiderMayReach(
            CultPlayer player,
            PacketEntity vehicle,
            ClientBlockEntityPass pass,
            SimpleCollisionBox scratch,
            int depth) {
        if (depth > player.compensatedEntities.entityMap.size()) return true;
        for (int i = 0; i < vehicle.passengers.size(); i++) {
            PacketEntity rider = vehicle.passengers.get(i);
            for (int candidate = 0, count = rider.possibleMovementCollisionBoxCandidateCount();
                    candidate < count;
                    candidate++) {
                rider.positionCollisionBoxCandidate(candidate, scratch);
                if (mayReach(pass, rider.movementBoxAt(scratch))) return true;
            }
            if (heldRiderMayReach(player, rider, pass, scratch, depth + 1)) return true;
        }
        return false;
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

    /**
     * The Entity#pistonDeltas the rider at seat candidate {@code candidate} holds: Entity#positionRider keeps them, so
     * they are what the last pass that moved it left there, or, before any, the ones it had as a root. When the
     * candidates no longer match the held ones, each held value remains possible.
     */
    private static List<ClientPistonDeltas> heldDeltas(
            PacketEntity rider, List<ClientPistonDeltas> held, int count, int candidate) {
        if (held == null)
            return List.of(
                    rider.blockEntityState == null ? ClientPistonDeltas.NONE : rider.blockEntityState.pistonDeltas());
        if (held.size() == count) return List.of(held.get(candidate));
        return held.stream().distinct().toList();
    }

    private record Moved(SimpleCollisionBox box, ClientPistonDeltas deltas) {}

    /** Where the pass leaves a rider seated at {@code seat}; it moves on its own, apart from its vehicle. */
    private static Moved movedRider(
            CultPlayer player,
            PacketEntity rider,
            SimpleCollisionBox seat,
            ClientBlockEntityPass pass,
            ClientPistonDeltas deltas) {
        Vec3 start = new Vec3((seat.minX + seat.maxX) / 2.0, seat.minY, (seat.minZ + seat.maxZ) / 2.0);
        // Entity#rideTick zeroes a passenger's deltaMovement, and a remote rider never travels.
        var state = new JavaPassState(
                start,
                Vec3.ZERO,
                rider.onGround,
                new MainSupportingBlockData(null, rider.onGround),
                null,
                0,
                deltas,
                false);
        var replay = JavaBlockEntityPass.runDetachedEntity(player, rider, pass, state);
        Vec3 end = replay.state().position();
        SimpleCollisionBox box = end.equals(start)
                ? seat
                : GetBoundingBox.getPacketEntityBoundingBox(player, end.x, end.y, end.z, rider);
        rider.recordRiderInterpolationPass(box, replay.interpolation(), replay.oldInterpolation());
        return new Moved(box, replay.state().pistonDeltas());
    }

    /**
     * Block entities move after FishingHook#tick put a hooked hook on its entity, and the hook keeps that shove until
     * its next tick: replay the pass from the followed position nearest the first mover that can reach it, and leave
     * that shove possible rather than certain. Pulls read an unfollowed hook's position as a range already.
     */
    private static void pushFollowedHook(CultPlayer player, PacketEntityHook hook, ClientBlockEntityPass pass) {
        SimpleCollisionBox positions = hook.followedPositions();
        if (positions == null) return;
        SimpleCollisionBox size = GetBoundingBox.getPacketEntityBoundingBox(player, 0, 0, 0, hook);
        SimpleCollisionBox column = new SimpleCollisionBox(
                positions.minX + size.minX,
                positions.minY + size.minY,
                positions.minZ + size.minZ,
                positions.maxX + size.maxX,
                positions.maxY + size.maxY,
                positions.maxZ + size.maxZ);
        double height = size.maxY - size.minY;
        for (var mover : pass.movers()) {
            SimpleCollisionBox area = area(mover);
            if (area == null || !area.isIntersected(column)) continue;
            Vec3 start = new Vec3(
                    clamp((area.minX + area.maxX) / 2, positions.minX, positions.maxX),
                    clamp((area.minY + area.maxY - height) / 2, positions.minY, positions.maxY),
                    clamp((area.minZ + area.maxZ) / 2, positions.minZ, positions.maxZ));
            var state = new JavaPassState(
                    start,
                    hook.deltaMovement,
                    hook.onGround,
                    new MainSupportingBlockData(null, hook.onGround),
                    null,
                    0,
                    ClientPistonDeltas.NONE,
                    false);
            Vec3 end = JavaBlockEntityPass.runDetachedEntity(player, hook, pass, state)
                    .state()
                    .position();
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
