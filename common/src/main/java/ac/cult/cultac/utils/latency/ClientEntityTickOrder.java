package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * TransientEntitySectionManager's EntityTickList callbacks. A tracked non-player ticks only in a ticking chunk;
 * leaving removes it, entering appends it. Reloading a chunk visits sections in packed SectionPos order, then each
 * section's ClassInstanceMultiMap insertion order. Players are always ticking, including in unloaded chunks.
 */
final class ClientEntityTickOrder {
    private final CompensatedEntities owner;
    private final CompensatedWorld world;
    private final IdentityHashMap<PacketEntity, Member> members = new IdentityHashMap<>();
    private long sectionEntryOrder;

    ClientEntityTickOrder(CompensatedEntities owner, CompensatedWorld world) {
        this.owner = owner;
        this.world = world;
    }

    void add(PacketEntity entity) {
        Member member = new Member(entity);
        members.put(entity, member);
        move(member);
    }

    void remove(PacketEntity entity) {
        members.remove(entity);
    }

    void moved(PacketEntity entity) {
        Member member = members.get(entity);
        if (member != null) move(member);
    }

    private void move(Member member) {
        Vec3 at = member.entity.clientPhysicalPosition;
        if (at == null) return;
        int x = CultMath.floor(at.x) >> 4;
        int y = CultMath.floor(at.y) >> 4;
        int z = CultMath.floor(at.z) >> 4;
        if (!member.positioned || x != member.x || y != member.y || z != member.z) {
            member.x = x;
            member.y = y;
            member.z = z;
            member.positioned = true;
            member.enteredSection = sectionEntryOrder++;
        }
        membership(member, member.entity.type == EntityTypeIds.PLAYER || world.isClientEntityTickingChunk(x, z));
    }

    void chunk(int x, int z, boolean ticking) {
        List<Member> entering = null;
        for (Member member : members.values()) {
            if (member.entity.type == EntityTypeIds.PLAYER || !member.positioned || member.x != x || member.z != z)
                continue;
            if (!ticking) membership(member, false);
            else if (!member.ticking) {
                if (entering == null) entering = new ArrayList<>();
                entering.add(member);
            }
        }
        if (entering == null) return;
        // SectionPos.asLong packs Y in its low 20 bits: sections >= 0 precede the negative sections of this chunk.
        entering.sort(Comparator.comparingInt((Member member) -> member.y & 0xFFFFF)
                .thenComparingLong(member -> member.enteredSection));
        for (Member member : entering) membership(member, true);
    }

    private void membership(Member member, boolean ticking) {
        if (ticking == member.ticking) return;
        member.ticking = ticking;
        if (ticking) owner.appendEntityToClientTickOrder(member.entity);
        else member.entity.setClientTickOrder(Long.MAX_VALUE);
    }

    private static final class Member {
        private final PacketEntity entity;
        private int x, y, z;
        private long enteredSection;
        private boolean positioned, ticking;

        private Member(PacketEntity entity) {
            this.entity = entity;
            entity.setClientTickOrder(Long.MAX_VALUE);
        }
    }
}
