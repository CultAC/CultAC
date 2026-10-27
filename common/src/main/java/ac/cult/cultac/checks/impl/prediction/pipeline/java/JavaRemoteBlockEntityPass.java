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

/** The tracked pushers' Level#tickBlockEntities, after their interpolation and LivingEntity#pushEntities. */
public final class JavaRemoteBlockEntityPass {
    private JavaRemoteBlockEntityPass() {}

    public static void tick(CultPlayer player) {
        if (player.isBedrockMovement()) return;
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
            // Other riders are never ticked or positioned here.
            if (actor == entities.getSelf()
                    || actor.getRiding() != null
                    || (actor == selfRoot || actor == serverRoot)
                            && entities.vehicles.isLocallyTickedVelocityRoot(actor)
                    || actor.isDead
                    || actor.newPacketLocation == null
                    || !actor.clientPhysicalPositionExact
                    || actor.oldPacketLocation != null) continue;
            SimpleCollisionBox box = actor.getPossibleMovementCollisionBoxes();
            if (!mayReach(pass, box)) continue;
            if (player.getClientVersion() == ClientVersion.V_1_21_9) {
                actor.ensurePistonPatchStates();
                actor.runPistonPatchStates(() -> tickActor(player, actor, pass));
            } else {
                tickActor(player, actor, pass);
            }
        }
    }

    private static void tickActor(CultPlayer player, PacketEntity actor, ClientBlockEntityPass pass) {
        if (actor.runPhysicsPaths(() -> tickActor(player, actor, pass))) return;
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
            var state = new JavaPassState(start, hook.deltaMovement, hook.onGround,
                    new MainSupportingBlockData(null, hook.onGround), null, 0, ClientPistonDeltas.NONE, false);
            Vec3 end = hook.withoutCommitting(() -> JavaBlockEntityPass.runEntity(player, hook, pass, state).state().position());
            hook.addPush(end.subtract(start));
            return;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static SimpleCollisionBox area(ClientBlockEntityPass.Mover mover) {
        if (mover instanceof ClientBlockEntityPass.Piston piston) return new SimpleCollisionBox(piston.pos()).expand(1.0);
        if (mover instanceof ClientBlockEntityPass.ShulkerBox box) return box.area();
        if (mover instanceof ClientBlockEntityPass.ShulkerPeek peek) return peek.area();
        return null;
    }

    private static boolean mayReach(ClientBlockEntityPass pass, SimpleCollisionBox box) {
        for (var mover : pass.movers()) {
            // A piston moves within its block and the adjacent block; the honey carry query reaches 1.500001 high.
            SimpleCollisionBox area = mover instanceof ClientBlockEntityPass.Piston piston
                    ? new SimpleCollisionBox(piston.pos()).expand(1.0)
                    : ((ClientBlockEntityPass.ShulkerBox) mover).area();
            if (area.isIntersected(box)) return true;
        }
        return false;
    }
}
