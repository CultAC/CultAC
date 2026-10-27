package ac.cult.cultac.utils.latency;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** FishingHook#pullEntity's packet-handler additions, before LivingEntity#aiStep's tiny-velocity clamp. */
public final class ClientFishingPulls {
    private final List<Impulse> pending = new ArrayList<>();
    private final List<PacketEntityHook> followed = new ArrayList<>();

    public void followed(PacketEntityHook hook) {
        if (!followed.contains(hook)) followed.add(hook);
    }

    public void finishClientTick(CultPlayer player) {
        try (var ignored = player.compensatedWorld.beginBlockEntityReplay(player.compensatedWorld.lastBlockEntityPass())) {
            for (var hook : followed) hook.finishClientTick(player);
        }
        followed.clear();
    }

    public List<Impulse> pending(PacketEntity actor) {
        return pending.stream().filter(impulse -> impulse.actor == actor).toList();
    }

    public void clear() {
        pending.clear();
    }

    public void reset() {
        clear();
        followed.clear();
    }

    public void receive(CultPlayer player, int id, int transaction, long sequence) {
        var entities = player.compensatedEntities;
        if (!(entities.getEntity(id) instanceof PacketEntityHook hook)) return;
        PacketEntity actor = hook.hookedIn();
        PacketEntity owner = hook.clientOwner(player);
        if (actor == null || owner == null) return;
        if (actor != entities.getSelf()
                && (player.getClientVersion().isOlderThan(ClientVersion.V_26_3)
                        || !entities.vehicles.canClientAuthoritativelyMoveVisibleRoot(actor))) return;
        List<Vec3> owners;
        if (owner == entities.getSelf()) owners = List.of(new Vec3(player.x, player.y, player.z));
        else if (entities.isUntrackedRider(owner))
            owners = List.of(PacketEntityHook.feet(entities.currentMovementBox(owner)));
        else owners = owner.getPossibleMovementCollisionBoxCandidates().stream()
                    .map(PacketEntityHook::feet)
                    .distinct()
                    .toList();
        var impulses = new ArrayList<Vec3>();
        for (Vec3 source : owners) {
            for (Vec3 position : hook.pullPositions(player)) {
                Vec3 impulse = source.subtract(position).scale(0.1);
                if (!impulses.contains(impulse)) impulses.add(impulse);
            }
        }
        // FishingHook#pullEntity adds (owner - hook) * 0.1: a bobbing hook's range enters the pull negated.
        SimpleCollisionBox hookRange = hook.pullRange(player);
        SimpleCollisionBox range = hookRange == null ? null : new SimpleCollisionBox(
                -0.1 * hookRange.maxX, -0.1 * hookRange.maxY, -0.1 * hookRange.maxZ,
                -0.1 * hookRange.minX, -0.1 * hookRange.minY, -0.1 * hookRange.minZ);
        if (!impulses.isEmpty()) pending.add(new Impulse(actor, id, transaction, sequence, impulses, range));
    }

    /** LocalPlayer#onGameModeChanged zeroes the Y of the pulls the client already added before it. */
    public void resetVerticalBefore(PacketEntity self, long sequence) {
        for (Impulse impulse : pending) {
            if (impulse.actor != self || impulse.getOutboundSequence() >= sequence) continue;
            impulse.impulses = impulse.impulses.stream()
                    .map(velocity -> velocity.with(ac.cult.cultac.protocol.value.Direction.Axis.Y, 0))
                    .toList();
            if (impulse.range != null) {
                var range = impulse.range;
                impulse.range = new SimpleCollisionBox(range.minX, 0, range.minZ, range.maxX, 0, range.maxZ);
            }
            impulse.setVel(impulse.impulses.getFirst());
        }
    }

    public void afterTeleport(TeleportData teleport, PacketEntity self) {
        for (Impulse impulse : pending) {
            if (impulse.actor != self || self.getRiding() != null
                    || impulse.getTransaction() > teleport.getTransaction()) continue;
            impulse.impulses = impulse.impulses.stream().map(teleport::transformInheritedVelocity).toList();
            if (impulse.range != null) impulse.range = transformed(impulse.range, teleport);
        }
    }

    /** The hull of a range's corners after a teleport's relative rotation of inherited velocity. */
    private static SimpleCollisionBox transformed(SimpleCollisionBox range, TeleportData teleport) {
        Vec3 origin = teleport.transformInheritedVelocity(Vec3.ZERO);
        SimpleCollisionBox hull = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = teleport.transformInheritedVelocity(new Vec3(
                    (corner & 1) == 0 ? range.minX : range.maxX,
                    (corner & 2) == 0 ? range.minY : range.maxY,
                    (corner & 4) == 0 ? range.minZ : range.maxZ)).subtract(origin);
            SimpleCollisionBox box = new SimpleCollisionBox(point.x, point.y, point.z, point.x, point.y, point.z);
            hull = hull == null ? box : hull.union(box);
        }
        return hull;
    }

    public static final class Impulse extends TransactionVel {
        private final PacketEntity actor;
        private List<Vec3> impulses;
        private SimpleCollisionBox range;

        private Impulse(
                PacketEntity actor, int source, int proof, long sequence, List<Vec3> impulses, SimpleCollisionBox range) {
            super(impulses.getFirst(), proof, false, false, source, sequence);
            this.actor = actor;
            this.impulses = List.copyOf(impulses);
            this.range = range;
        }

        public List<Vec3> impulses() {
            return impulses;
        }

        /** Offsets around each impulse that a hook's hidden bobbing leaves possible, or null when exact. */
        public SimpleCollisionBox range() {
            return range;
        }
    }
}
