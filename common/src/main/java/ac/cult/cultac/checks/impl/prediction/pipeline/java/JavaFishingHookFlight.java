package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.data.packetentity.PacketEntityInteraction;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.FluidCurrent;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.ArrayList;
import java.util.List;

/** FishingHook#tick's FLYING move, using the same Entity#move and block callbacks as the Java runner. */
public final class JavaFishingHookFlight {
    private JavaFishingHookFlight() {}

    public record Result(JavaPassState state, boolean bobbing, boolean leftOwner, Vec3 recordedMovement) {}

    public static List<Result> tick(
            CultPlayer player, PacketEntityHook hook, JavaPassState previous, boolean leftOwner, int age) {
        Vec3 start = hook.clientPhysicalPosition;
        Vec3 velocity = hook.deltaMovement;
        var old = previous == null
                ? new JavaPassState(start, velocity, hook.onGround,
                        new MainSupportingBlockData(null, false), null, 0, ClientPistonDeltas.NONE)
                : new JavaPassState(start, velocity, hook.onGround, previous.support(), previous.stuckSpeed(),
                        previous.fallDistance(), previous.pistonDeltas(), false);
        SimpleCollisionBox box = box(player, hook, start);
        int ownerOverlap = leftOwner ? 0 : ownerOverlap(player, hook,
                box.copy().expandToCoordinate(velocity.x, velocity.y, velocity.z).expand(1.0));
        List<Boolean> leftOwners = leftOwner || ownerOverlap == 0 ? List.of(true)
                : ownerOverlap == 2 ? List.of(false) : List.of(false, true);
        // Projectile#tick -> Entity#baseTick pushes water first and lava second, before the hook state machine.
        var current = new FluidCurrent();
        if (current.compute(player, box, false, velocity.x, velocity.z, FluidTags.WATER, 0.014))
            velocity = velocity.add(current.x, current.y, current.z);
        if (current.compute(player, box, false, velocity.x, velocity.z, FluidTags.LAVA,
                player.compensatedWorld.hasFastLava() ? 0.007 : 0.0023333333333333335))
            velocity = velocity.add(current.x, current.y, current.z);
        BlockPos pos = BlockPos.containing(start.x, start.y, start.z);
        var fluid = ClientFluidQueries.fluidAt(player.compensatedWorld, pos);
        boolean water = ClientFluidQueries.is(player, fluid, FluidTags.WATER);
        var results = new ArrayList<Result>();
        for (boolean left : leftOwners) {
            if (water && ClientFluidQueries.height(player.compensatedWorld, pos, fluid) > 0.0F) {
                results.add(new Result(old.withVelocity(velocity.multiply(0.3, 0.2, 0.3)), true, left, null));
                continue;
            }
            for (Vec3 rayVelocity : rayVelocities(player, hook, start, velocity, left, age)) {
                // 1.21.6 added the onGround/hookedIn guard; 26.3 promotes the float GRAVITY constant.
                if (!water && (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_6) || !old.onGround()))
                    rayVelocity = rayVelocity.add(0, player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                            ? -(double) 0.03F : -0.03, 0);
                var env = new JavaBlockEntityMove.Environment(player, null, Pose.STANDING, 1, false, start,
                        player.compensatedWorld.endedTickGameTime(), hook, hook.pistonMovementPatch());
                var moved = JavaBlockEntityMove.moveDetached(env, old.withVelocity(rayVelocity), rayVelocity);
                var state = JavaBlockEntityMove.applyTravelEffects(env, moved.state(), start,
                        moved.recordedMovement() ? moved.delta() : null);
                boolean horizontal = !CultMath.equal(moved.delta().x, moved.movement().x)
                        || !CultMath.equal(moved.delta().z, moved.movement().z);
                Vec3 nextVelocity = state.onGround() || horizontal ? Vec3.ZERO : state.velocity();
                results.add(new Result(state.withVelocity(nextVelocity.scale(0.92)), false, left,
                        moved.recordedMovement() ? moved.movement() : null));
            }
        }
        return results;
    }

    private static SimpleCollisionBox box(CultPlayer player, PacketEntityHook hook, Vec3 position) {
        return GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, hook);
    }

    private static int ownerOverlap(CultPlayer player, PacketEntityHook hook, SimpleCollisionBox query) {
        PacketEntity owner = hook.clientOwner(player);
        if (owner == null) return 0;
        while (owner.getRiding() != null) owner = owner.getRiding();
        return ownerTreeOverlap(player, owner, query);
    }

    /** 0 means outside every candidate; 1 means either side is possible; 2 means certainly still inside. */
    private static int ownerTreeOverlap(CultPlayer player, PacketEntity entity, SimpleCollisionBox query) {
        if (player.compensatedEntities.isUntrackedRider(entity)) return 0;
        int overlap = 0;
        if (pickable(player, entity)) {
            int count = 0, hits = 0;
            for (var box : boxes(player, entity)) {
                count++;
                if (query.isIntersected(box)) hits++;
            }
            if (hits > 0) overlap = hits == count ? 2 : 1;
        }
        for (PacketEntity passenger : entity.passengers)
            overlap = Math.max(overlap, ownerTreeOverlap(player, passenger, query));
        return overlap;
    }

    private static List<Vec3> rayVelocities(
            CultPlayer player, PacketEntityHook hook, Vec3 start, Vec3 velocity, boolean leftOwner, int age) {
        var collision = JavaCollisionState.of(player, hook, 0);
        var context = EntityCollisionContext.position(start.y);
        var hit = player.compensatedWorld.geometry().clipBlocks(start, start.add(velocity),
                (state, pos) -> NativeBlockCollisionHelper.getCollisionShape(
                        player, state, pos, start.y, collision, context));
        Vec3 to = hit == null ? start.add(velocity)
                : new Vec3(hit.location().x(), hit.location().y(), hit.location().z());
        Vec3 bounded = borderHit(player, start, to);
        if (hit == null && bounded == to) return List.of(velocity);
        to = bounded;
        Vec3 blocked = velocity.normalize().scale(Math.sqrt(start.distanceToSqr(to)));
        // onHitEntity is server-only; a client entity hit only suppresses onHitBlock's ray shortening.
        SimpleCollisionBox query = box(player, hook, start).expandToCoordinate(velocity.x, velocity.y, velocity.z)
                .expand(1.0);
        float margin = Math.max(0.0F, Math.min(0.3F, (age - 2) / 20.0F));
        boolean possible = false;
        var candidates = new ArrayList<>(player.compensatedEntities.entityMap.values());
        candidates.add(player.compensatedEntities.getSelf());
        PacketEntity owner = hook.clientOwner(player);
        for (PacketEntity target : candidates) {
            if (target == hook || player.compensatedEntities.isUntrackedRider(target)
                    || target.isDead || !target.actionAlive
                    || target instanceof PacketEntityInteraction
                    || !(target.type == EntityTypeIds.ITEM || pickable(player, target))
                    || !leftOwner && owner != null && sameRoot(owner, target)) continue;
            int count = 0, hits = 0;
            for (var targetBox : boxes(player, target)) {
                count++;
                if (query.isIntersected(targetBox)
                        && targetBox.copy().expand(margin).clip(start, to).isPresent()) hits++;
            }
            if (hits > 0 && hits == count) return List.of(velocity);
            possible |= hits > 0;
        }
        return possible && !blocked.equals(velocity) ? List.of(blocked, velocity) : List.of(blocked);
    }

    /** CollisionGetter#clipIncludingBorder clamps the block ray's endpoint, before its entity-ray query. */
    private static Vec3 borderHit(CultPlayer player, Vec3 start, Vec3 to) {
        var border = player.checkManager.getWorldBorder();
        double half = border.getBlockInteractionDiameter() / 2;
        double limit = border.getAbsoluteMaxSize();
        double minX = Math.max(-limit, Math.min(limit, border.getCenterX() - half));
        double maxX = Math.max(-limit, Math.min(limit, border.getCenterX() + half));
        double minZ = Math.max(-limit, Math.min(limit, border.getCenterZ() - half));
        double maxZ = Math.max(-limit, Math.min(limit, border.getCenterZ() + half));
        if (start.x < minX || start.x >= maxX || start.z < minZ || start.z >= maxZ
                || to.x >= minX && to.x < maxX && to.z >= minZ && to.z < maxZ) return to;
        return new Vec3(Math.max(minX, Math.min(maxX - (double) 1.0E-5F, to.x)), to.y,
                Math.max(minZ, Math.min(maxZ - (double) 1.0E-5F, to.z)));
    }

    private static boolean sameRoot(PacketEntity a, PacketEntity b) {
        while (a.getRiding() != null) a = a.getRiding();
        while (b.getRiding() != null) b = b.getRiding();
        return a == b;
    }

    private static boolean pickable(CultPlayer player, PacketEntity entity) {
        if (entity == player.compensatedEntities.getSelf()) return player.gamemode != GameMode.SPECTATOR;
        return new ClientEntityQueries(player).pickable(entity);
    }

    private static List<SimpleCollisionBox> boxes(CultPlayer player, PacketEntity entity) {
        var entities = player.compensatedEntities;
        if (entities.isUntrackedRider(entity)) return List.of();
        if (entity != entities.getSelf()) return entity.getPossibleMovementCollisionBoxCandidates();
        var result = player.checkManager.getSimulationProcessor().getLastPrediction();
        var context = result == null ? null : result.getSimulationContext();
        if (context == null || context.getVehicle() != null) return List.of(player.boundingBox);
        boolean before = entities.tickedThroughOrder() < entities.clientTickOrder(entity);
        var pass = JavaMovementEngine.passAfterTravel(result);
        Vec3 position = before ? context.getStart() : pass == null ? context.getEnd() : pass.travel().position();
        Pose pose = before ? context.getPose() : player.pose;
        float scale = before ? context.getScale() : player.dimensionScale;
        // LivingEntity's sleeping dimensions and the avatar's dying dimensions are fixed.
        if (pose == Pose.SLEEPING || pose == Pose.DYING) scale = 1.0F;
        return List.of(GetBoundingBox.getBoundingBoxFromPosAndSize(
                position.x, position.y, position.z, pose.width * scale, pose.height * scale));
    }
}
