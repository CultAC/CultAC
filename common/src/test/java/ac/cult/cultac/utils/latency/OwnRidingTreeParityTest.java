package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The local player's own riding tree is positioned exactly as at 07abeb541, where every rider of every tree was
 * positioned: a scripted world (nested co-riders, the local player as a passenger and ridden, reorders, scale and
 * baby changes, dismount, remount into a nested chain, removal of a mid-chain vehicle, other trees moving beside it)
 * must reproduce, bit for bit, the trace recorded at that commit.
 *
 * <p>Roots, and riders in the own tree since the scenario began, are traced in full; a rider that joins it later is
 * traced by position only, as its interpolation state froze while it was an untracked rider. Run with system property
 * {@code rider.trace.write} (or environment variable {@code RIDER_TRACE_WRITE}) set to a directory to record the traces instead of comparing them.
 */
class OwnRidingTreeParityTest {
    private static final int SELF = 7;
    private static final int SELF_VEHICLE = 2000;
    private static final int CO_RIDER = 2001;
    private static final int CO_TOP = 2002;
    private static final int SELF_RIDER = 2003;
    private static final int NESTED_ROOT = 2100;
    private static final int NESTED_MID = 2101;
    private static final int OTHER_PLAYER = 3000;
    private static final int COSMETIC = 3001;
    private static final int COSMETIC_TOP = 3002;
    private static final int BOAT = 3100;
    private static final int BOAT_RIDER = 3101;
    private static final int MOBS = 10;
    private static final int MOB = 1000;
    private static final int[] MOVERS = {SELF_VEHICLE, CO_RIDER, NESTED_ROOT, OTHER_PLAYER, BOAT};

    @Test
    void legacyOwnTreeMatchesTheRecordedTrace() throws IOException {
        runScenario(ClientVersion.V_1_21_11);
    }

    @Test
    void steppedOwnTreeMatchesTheRecordedTrace() throws IOException {
        runScenario(ClientVersion.V_26_3);
    }

    private static void runScenario(ClientVersion version) throws IOException {
        CultPlayer player = world(version);
        StringBuilder trace = new StringBuilder();
        Set<Integer> continuous = new HashSet<>(ownTree(player));
        try {
            trace.append("start\n").append(snapshot(player, continuous));
            for (int step = 1; step <= 14; step++) {
                advance(player, step);
                continuous.retainAll(ownTree(player));
                trace.append("step ").append(step).append('\n').append(snapshot(player, continuous));
            }
        } finally {
            close(player);
        }
        String name = "own-tree-" + version.name() + ".txt";
        String directory = System.getProperty("rider.trace.write", System.getenv("RIDER_TRACE_WRITE"));
        if (directory != null && !directory.isEmpty()) {
            Files.writeString(Path.of(directory, name), trace.toString(), StandardCharsets.UTF_8);
            return;
        }
        try (InputStream recorded = OwnRidingTreeParityTest.class.getResourceAsStream("/passenger/" + name)) {
            assertNotNull(recorded, name);
            String expected = new String(recorded.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(expected, trace.toString(), name);
        }
    }

    private static CultPlayer world(ClientVersion version) {
        CultPlayer player = trackingPlayer(version);
        player.entityID = SELF;
        player.x = 0;
        player.y = 64;
        player.z = 0;
        for (int i = 0; i < MOBS; i++) add(player, MOB + i, EntityTypeIds.ZOMBIE, i, 3);
        add(player, SELF_VEHICLE, EntityTypeIds.ZOMBIE, 0.5, 0.5);
        add(player, CO_RIDER, EntityTypeIds.ZOMBIE, 0.5, 0.5);
        add(player, CO_TOP, EntityTypeIds.ARMOR_STAND, 0.5, 0.5);
        add(player, SELF_RIDER, EntityTypeIds.ARMOR_STAND, 0.5, 0.5);
        add(player, NESTED_ROOT, EntityTypeIds.ZOMBIE, -3, 2);
        add(player, NESTED_MID, EntityTypeIds.ZOMBIE, -3, 2);
        add(player, OTHER_PLAYER, EntityTypeIds.PLAYER, 20, 20);
        add(player, COSMETIC, EntityTypeIds.ARMOR_STAND, 20, 20);
        add(player, COSMETIC_TOP, EntityTypeIds.ARMOR_STAND, 20, 20);
        add(player, BOAT, EntityTypeIds.OAK_BOAT, -20, 20);
        add(player, BOAT_RIDER, EntityTypeIds.ZOMBIE, -20, 20);
        entity(player, CO_TOP).scale = 0.6F;
        mount(player, SELF_VEHICLE, CO_RIDER, SELF);
        mount(player, CO_RIDER, CO_TOP);
        mount(player, SELF, SELF_RIDER);
        mount(player, NESTED_ROOT, NESTED_MID);
        mount(player, OTHER_PLAYER, COSMETIC);
        mount(player, COSMETIC, COSMETIC_TOP);
        mount(player, BOAT, BOAT_RIDER);
        return player;
    }

    private static void advance(CultPlayer player, int step) {
        switch (step) {
            case 3 -> {
                entity(player, CO_RIDER).scale = 1.4F;
                entity(player, CO_TOP).isBaby = true;
            }
            case 4 -> mount(player, SELF_VEHICLE, SELF, CO_RIDER);
            case 5 -> mount(player, SELF_VEHICLE, CO_RIDER);
            case 6 -> mount(player, NESTED_MID, SELF);
            case 8 -> entity(player, NESTED_ROOT).scale = 1.25F;
            case 10 -> player.compensatedEntities.removeEntity(NESTED_MID);
            case 11 -> mount(player, SELF_VEHICLE, CO_RIDER, SELF);
            case 13 -> entity(player, SELF_VEHICLE).scale = 0.8F;
            default -> {}
        }
        var replication = player.packetEntityReplication;
        replication.tickStartTick();
        List<PacketSendEvent<?>> events = new ArrayList<>();
        for (int id : MOVERS) {
            if (player.compensatedEntities.getEntity(id) != null && (step + id / 100) % 3 != 0) {
                events.add(move(player, id, step % 2 == 0 ? 4096 : -2048, step % 4 == 0 ? 512 : 0));
            }
        }
        for (PacketSendEvent<?> event : events) event.getTasksAfterSend().forEach(Runnable::run);
        ((EmbeddedChannel) player.user.getChannel()).runPendingTasks();
        player.onEndOfTickEvent(step);
        runWriteTasks(player);
        // Odd steps leave the last transaction unanswered, so vehicles span their old and new packet locations.
        acknowledgeThrough(player, player.lastTransactionSent.get() - (step % 2));
        replication.onPlayerTickEnd(null);
        if (step % 3 == 0) replication.onPlayerTickEnd(null);
    }

    /** Without CultEncoder the channel queues write tasks; run them at their place, as the encoder would. */
    private static void runWriteTasks(CultPlayer player) {
        var channel = (EmbeddedChannel) player.user.getChannel();
        var queued = new ArrayList<Object>();
        Object message;
        while ((message = channel.readOutbound()) != null) queued.add(message);
        for (Object item : queued) {
            if (item instanceof ac.cult.cultac.network.CultConnection.WriteTask task) task.action().run();
        }
    }

    /** The ids of the local player's riding tree: everything under the root of its riding chain, while it rides. */
    private static Set<Integer> ownTree(CultPlayer player) {
        Set<Integer> tree = new HashSet<>();
        PacketEntity root = player.compensatedEntities.playerEntity.getRiding();
        if (root == null) return tree;
        while (root.getRiding() != null) root = root.getRiding();
        collect(player, root, tree);
        return tree;
    }

    private static void collect(CultPlayer player, PacketEntity entity, Set<Integer> tree) {
        tree.add(entity == player.compensatedEntities.playerEntity ? SELF : entity.getEntityId());
        for (PacketEntity passenger : entity.passengers) collect(player, passenger, tree);
    }

    private static String snapshot(CultPlayer player, Set<Integer> continuous) {
        List<Integer> ids = new ArrayList<>(ownTree(player));
        ids.sort(Comparator.naturalOrder());
        StringBuilder out = new StringBuilder();
        for (int id : ids) {
            PacketEntity entity = entity(player, id);
            boolean full = continuous.contains(id) || entity.getRiding() == null;
            out.append('#').append(id).append(full ? " full" : " position");
            out.append(" riding=")
                    .append(
                            entity.getRiding() == null
                                    ? "-"
                                    : entity.getRiding().getEntityId());
            out.append(" passengers=");
            for (PacketEntity passenger : entity.passengers)
                out.append(passenger.getEntityId()).append(',');
            out.append(" physical=").append(dump(entity.clientPhysicalPosition));
            out.append(" exact=").append(entity.clientPhysicalPositionExact);
            if (id != SELF) {
                out.append(" box=").append(dump(entity.getPossibleCollisionBoxes()));
                out.append(" movement=").append(dump(entity.getPossibleMovementCollisionBoxes()));
                out.append(" candidates=");
                for (SimpleCollisionBox box : entity.getPossibleMovementCollisionBoxCandidates())
                    out.append(dump(box)).append(';');
            }
            if (full && id != SELF) {
                out.append(" desync=").append(dump(entity.desyncClientPos));
                out.append(" yaw=").append(dump(entity.clientPhysicalYaw));
                out.append(" pitch=").append(dump(entity.clientPhysicalPitch));
                out.append(" exactBox=").append(dump(entity.getExactMovementCollisionBox()));
                out.append(" new=").append(dump(entity.newPacketLocation));
                out.append(" old=").append(dump(entity.oldPacketLocation));
            }
            out.append('\n');
        }
        out.append("clientSide=")
                .append(dump(player.packetStateData.clientSidePosition))
                .append('\n');
        return out.toString();
    }

    private static String dump(ReachInterpolationData location) {
        if (location == null) return "null";
        StringBuilder out = new StringBuilder("{");
        out.append("combined=").append(dump(location.getPossibleLocationCombined(new SimpleCollisionBox())));
        out.append(" movement=").append(dump(location.getPossibleMovementLocationCombined()));
        out.append(" locations=");
        for (SimpleCollisionBox box : location.getPossibleMovementLocations())
            out.append(dump(box)).append(';');
        out.append(" active=").append(location.hasActiveInterpolationTarget());
        if (location.hasActiveInterpolationTarget()) {
            out.append(" target=").append(dump(location.getTargetLocation()));
            out.append(" targetYaw=").append(dump(location.getTargetYaw()));
        }
        return out.append('}').toString();
    }

    private static String dump(SimpleCollisionBox box) {
        if (box == null) return "null";
        return "[" + dump(box.minX) + " " + dump(box.minY) + " " + dump(box.minZ) + " " + dump(box.maxX) + " "
                + dump(box.maxY) + " " + dump(box.maxZ) + "]";
    }

    private static String dump(Vec3 vec) {
        return vec == null ? "null" : "(" + dump(vec.x) + " " + dump(vec.y) + " " + dump(vec.z) + ")";
    }

    private static String dump(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static String dump(float value) {
        return Integer.toHexString(Float.floatToRawIntBits(value));
    }

    private static void add(CultPlayer player, int id, int type, double x, double z) {
        player.packetEntityReplication.addEntity(id, type, new Vec3(x, 64, z), 0, 0, 0, -1);
    }

    private static void mount(CultPlayer player, int vehicle, int... passengers) {
        player.compensatedEntities.vehicles.applyVehiclePassengers(vehicle, passengers);
    }

    private static PacketEntity entity(CultPlayer player, int id) {
        PacketEntity entity = player.compensatedEntities.getEntity(id);
        assertNotNull(entity, "entity " + id);
        return entity;
    }

    private static PacketSendEvent<?> move(CultPlayer player, int id, int dx, int dz) {
        var move = new ClientboundMoveEntity(
                id, new EntityDelta.Linear((short) dx, (short) 0, (short) dz), 0, 0, true, true, false);
        var event =
                new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        player.packetEntityReplication.onMoveEntity(event, player, move);
        return event;
    }

    private static void acknowledgeThrough(CultPlayer player, int transaction) {
        for (int id = player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
            player.lastTransactionReceived.set(id);
            player.latencyUtils.handleNettySyncTransaction(id);
        }
    }

    private static CultPlayer trackingPlayer(ClientVersion version) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(
                new User.Profile(UUID.randomUUID(), ".Rider_Parity"),
                new EmbeddedChannel(),
                OfflineCultTestBootstrap.platformConnection());
        CultPlayer player = new CultPlayer(user);
        user.bind(Mockito.mock(PlatformPlayer.class));
        try {
            Field resolved = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            resolved.setAccessible(true);
            resolved.set(player, version);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return player;
    }

    private static void close(CultPlayer player) {
        Object channel = player.user.getChannel();
        player.onRemove();
        if (channel instanceof EmbeddedChannel embedded) {
            embedded.runPendingTasks();
            embedded.finishAndReleaseAll();
        }
    }
}
