package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
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
    // The positioned non-players of each chunk column, linked from the last one to enter it.
    private final Long2ObjectOpenHashMap<Member> columns = new Long2ObjectOpenHashMap<>();
    private long sectionEntryOrder;
    private PacketEntity localPlayer;

    ClientEntityTickOrder(CompensatedEntities owner, CompensatedWorld world) {
        this.owner = owner;
        this.world = world;
    }

    void add(PacketEntity entity) {
        Member member = new Member(entity);
        entity.setClientTickOrder(Long.MAX_VALUE);
        members.put(entity, member);
        move(member);
    }

    void localPlayer(PacketEntity entity, Vec3 position) {
        if (localPlayer != entity) {
            if (localPlayer != null) members.remove(localPlayer);
            localPlayer = entity;
            members.put(entity, new Member(entity));
        }
        move(members.get(entity), position.x, position.y, position.z);
    }

    long localSectionEntry() {
        return members.get(localPlayer).enteredSection;
    }

    /** When {@code entity} entered its current entity section, in section entry order; unknown entities last. */
    long sectionEntry(PacketEntity entity) {
        Member member = members.get(entity);
        return member == null ? Long.MAX_VALUE : member.enteredSection;
    }

    void remove(PacketEntity entity) {
        Member member = members.remove(entity);
        if (member != null) unlink(member);
    }

    void moved(PacketEntity entity) {
        Member member = members.get(entity);
        if (member != null) move(member);
    }

    boolean isTicking(PacketEntity entity) {
        Member member = members.get(entity);
        return member != null && member.ticking;
    }

    /** EntitySectionStorage visits X, then packed Z/Y, then ClassInstanceMultiMap insertion order. */
    boolean precedesInQuery(PacketEntity first, Vec3 a, long firstEntry, PacketEntity second, Vec3 b) {
        int x = Integer.compare(CultMath.floor(a.x) >> 4, CultMath.floor(b.x) >> 4);
        if (x != 0) return x < 0;
        int z = Integer.compare((CultMath.floor(a.z) >> 4) & 0x3FFFFF, (CultMath.floor(b.z) >> 4) & 0x3FFFFF);
        if (z != 0) return z < 0;
        int y = Integer.compare((CultMath.floor(a.y) >> 4) & 0xFFFFF, (CultMath.floor(b.y) >> 4) & 0xFFFFF);
        if (y != 0) return y < 0;
        Member left = members.get(first), right = members.get(second);
        return left != null && right != null
                ? firstEntry < right.enteredSection
                : first.getClientTickOrder() < second.getClientTickOrder();
    }

    void moved(PacketEntity entity, double x, double y, double z) {
        Member member = members.get(entity);
        if (member != null) move(member, x, y, z);
    }

    private void move(Member member) {
        Vec3 at = member.entity.clientPhysicalPosition;
        if (at != null) move(member, at.x, at.y, at.z);
    }

    private void move(Member member, double px, double py, double pz) {
        int x = CultMath.floor(px) >> 4;
        int y = CultMath.floor(py) >> 4;
        int z = CultMath.floor(pz) >> 4;
        if (!member.positioned || x != member.x || y != member.y || z != member.z) {
            boolean column = !member.positioned || x != member.x || z != member.z;
            if (column) unlink(member);
            member.x = x;
            member.y = y;
            member.z = z;
            member.positioned = true;
            member.enteredSection = sectionEntryOrder++;
            if (column) link(member);
        }
        if (member.entity != localPlayer)
            membership(member, member.entity.type == EntityTypeIds.PLAYER || world.isClientEntityTickingChunk(x, z));
    }

    void chunk(int x, int z, boolean ticking) {
        List<Member> entering = null;
        for (Member member = columns.get(column(x, z)); member != null; member = member.next) {
            if (!ticking) membership(member, false);
            else if (!member.ticking) {
                if (entering == null) entering = new ArrayList<>();
                entering.add(member);
            }
        }
        if (entering == null) return;
        entering.sort(ClientEntityTickOrder::reloadOrder);
        List<PacketEntity> appended = new ArrayList<>(entering.size());
        for (Member member : entering) {
            member.ticking = true;
            appended.add(member.entity);
        }
        owner.appendEntitiesToClientTickOrder(appended);
    }

    // SectionPos.asLong packs Y in its low 20 bits: sections >= 0 precede the negative sections of this chunk.
    private static int reloadOrder(Member a, Member b) {
        int y = Integer.compare(a.y & 0xFFFFF, b.y & 0xFFFFF);
        return y != 0 ? y : Long.compare(a.enteredSection, b.enteredSection);
    }

    private void link(Member member) {
        if (member.entity.type == EntityTypeIds.PLAYER) return;
        Member head = columns.put(column(member.x, member.z), member);
        member.next = head;
        if (head != null) head.previous = member;
        member.linked = true;
    }

    private void unlink(Member member) {
        if (!member.linked) return;
        if (member.previous != null) member.previous.next = member.next;
        else if (member.next != null) columns.put(column(member.x, member.z), member.next);
        else columns.remove(column(member.x, member.z));
        if (member.next != null) member.next.previous = member.previous;
        member.previous = member.next = null;
        member.linked = false;
    }

    private static long column(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
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
        private boolean positioned, ticking, linked;
        private Member previous, next;

        private Member(PacketEntity entity) {
            this.entity = entity;
        }
    }
}
