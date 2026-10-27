package ac.cult.cultac.utils.latency;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRavager;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Client-only Ravager#strongKnockback writes, ordered with the other packet-handler delta-movement writes. */
public final class ClientRavagerRoars {
    private final List<Impulse> pending = new ArrayList<>();

    public List<Impulse> pending(PacketEntity actor) {
        if (pending.isEmpty()) return List.of();
        return pending.stream().filter(impulse -> impulse.actor == actor).toList();
    }

    public void clear() {
        pending.clear();
    }

    /** Called at the trailing bundle proof: event 69 runs before the next client tick, without interpolation. */
    public void receive(CultPlayer player, int id, int transaction, long sequence) {
        var entities = player.compensatedEntities;
        if (!(entities.getEntity(id) instanceof PacketEntityRavager ravager)) return;
        PacketEntity actor = entities.getSelf().getRiding();
        if (actor == null) actor = entities.getSelf();
        else if (!actor.isLivingEntity() || !entities.vehicles.canClientAuthoritativelyMoveVisibleRoot(actor)) return;
        if (!actor.actionAlive || actor.isDead || actor instanceof PacketEntityRavager) return;
        SimpleCollisionBox target =
                actor == entities.getSelf() ? player.boundingBox.copy() : actor.getClientMovementCollisionBox();
        Vec3 position = actor == entities.getSelf()
                ? new Vec3(player.x, player.y, player.z)
                : new Vec3((target.minX + target.maxX) * 0.5, target.minY, (target.minZ + target.maxZ) * 0.5);
        List<SimpleCollisionBox> sources = entities.isUntrackedRider(ravager)
                ? List.of(entities.currentMovementBox(ravager))
                : ravager.getPossibleMovementCollisionBoxCandidates();
        List<Vec3> impulses = impulses(sources, target, position);
        if (impulses.size() == 1 && impulses.getFirst().equals(Vec3.ZERO)) return;
        pending.add(new Impulse(actor, id, transaction, sequence, impulses));
    }

    /** AABB.inflate(4), followed by the inverse squared horizontal distance (not normalized knockback). */
    public static List<Vec3> impulses(List<SimpleCollisionBox> sources, SimpleCollisionBox target, Vec3 position) {
        var impulses = new ArrayList<Vec3>(sources.size());
        for (var source : sources) {
            Vec3 impulse = Vec3.ZERO;
            if (source.copy().expand(4).isIntersected(target)) {
                double dx = position.x - (source.minX + source.maxX) * 0.5;
                double dz = position.z - (source.minZ + source.maxZ) * 0.5;
                double squared = Math.max(dx * dx + dz * dz, 0.001);
                impulse = new Vec3(dx / squared * 4.0, 0.2, dz / squared * 4.0);
            }
            if (!impulses.contains(impulse)) impulses.add(impulse);
        }
        return impulses;
    }

    public void resetVerticalBefore(PacketEntity self, long sequence) {
        for (Impulse impulse : pending) {
            if (impulse.actor != self || impulse.getOutboundSequence() >= sequence) continue;
            impulse.impulses = impulse.impulses.stream().map(velocity -> velocity.with(ac.cult.cultac.protocol.value.Direction.Axis.Y, 0)).toList();
            impulse.setVel(impulse.impulses.getFirst());
        }
    }

    public void afterTeleport(TeleportData teleport, PacketEntity self) {
        for (Impulse impulse : pending) {
            if (impulse.actor != self
                    || self.getRiding() != null
                    || impulse.getTransaction() > teleport.getTransaction()) continue;
            impulse.impulses = impulse.impulses.stream()
                    .map(teleport::transformInheritedVelocity)
                    .toList();
        }
    }

    /** Mandatory additive writes; unlike server knockback, ignoring a proven roar is a simulation mismatch. */
    public static final class Impulse extends TransactionVel {
        private final PacketEntity actor;
        private List<Vec3> impulses;

        private Impulse(PacketEntity actor, int source, int proof, long sequence, List<Vec3> impulses) {
            super(impulses.getFirst(), proof, false, false, source, sequence);
            this.actor = actor;
            this.impulses = List.copyOf(impulses);
        }

        public List<Vec3> impulses() {
            return impulses;
        }
    }
}
