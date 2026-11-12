package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.codec.entity.EntityPositionSyncCodec;
import ac.cult.cultac.protocol.codec.entity.MoveEntityCodec;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * One seeded entity packet script runs through a player handed every move as its record and one handed each linear
 * move as {@link MoveEntityCodec#SUNK} and each linear position sync as {@link EntityPositionSyncCodec#SUNK} with its
 * values held on the connection; the tracked entities and the
 * transactions match after every step.
 */
class SunkMoveReplicationEquivalenceTest {
    private static final int STEPS = 2_000;
    private static final int[] ENTITIES = {3000, 3001, 4000, 4001, 4002};
    private static final int[] TYPES = {
        EntityTypeIds.PLAYER, EntityTypeIds.PLAYER, EntityTypeIds.ZOMBIE, EntityTypeIds.PIG, EntityTypeIds.ITEM
    };
    private static final int UNKNOWN = 5000;
    private static final int MAX_DEPTH = 5;

    enum Script {
        V1_21_11(ProtocolVersion.V1_21_11, ProtocolVersion.V1_21_11, 71),
        V26_3(ProtocolVersion.V26_3, ProtocolVersion.V26_3, 72),
        VIA_26_3(ProtocolVersion.V1_21_11, ProtocolVersion.V26_3, 73);
        final ProtocolVersion observed, client;
        final long seed;

        Script(ProtocolVersion observed, ProtocolVersion client, long seed) {
            this.observed = observed;
            this.client = client;
            this.seed = seed;
        }
    }

    @ParameterizedTest
    @EnumSource(Script.class)
    void sunkMovesApplyWhatTheirRecordsApply(Script script) {
        Rig records = new Rig(script, false);
        Rig sunk = new Rig(script, true);
        try {
            Random random = new Random(script.seed);
            int moves = 0;
            for (int step = 0; step < STEPS; step++) {
                String op = step(random, records, sunk);
                if (op.startsWith("move")) moves++;
                assertEquals(records.snapshot(), sunk.snapshot(), "step " + step + " (" + op + ")");
                assertFalse(sunk.connection().sunkMove().held());
                assertFalse(sunk.connection().sunkPositionSync().held());
            }
            assertTrue(moves > 500, "moves " + moves);
            assertTrue(sunk.sunkMoves > 400, "sunk " + sunk.sunkMoves);
            assertTrue(sunk.sunkSyncs > 60, "sunk syncs " + sunk.sunkSyncs);
        } finally {
            records.close();
            sunk.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Script.class)
    void aSunkMoveIsReadOnlyWhileHeld(Script script) {
        Rig rig = new Rig(script, true);
        try {
            SunkMove move = rig.connection().sunkMove();
            assertThrows(IllegalStateException.class, move::entityId);
            assertFalse(rig.connection().sinksMove());
            rig.connection().sinkMoves(true);
            assertTrue(rig.connection().sinksMove());
            rig.connection().move(ENTITIES[0], (short) 1, (short) 2, (short) 3, 4, 5, true, true, true);
            assertEquals(
                    new ClientboundMoveEntity(
                            ENTITIES[0],
                            new EntityDelta.Linear((short) 1, (short) 2, (short) 3),
                            4,
                            5,
                            true,
                            true,
                            true),
                    move.packet());
            assertFalse(rig.connection().sinksMove(), "one move is held at a time");
            move.release();
            assertTrue(rig.connection().sinksMove());
            rig.connection().sinkMoves(false);
            assertFalse(rig.connection().sinksMove());
            assertThrows(IllegalStateException.class, move::x);
            assertThrows(IllegalStateException.class, move::packet);
        } finally {
            rig.close();
        }
    }

    private static String step(Random random, Rig a, Rig b) {
        int op = random.nextInt(100);
        if (op < 45) {
            int id = random.nextInt(10) == 0 ? UNKNOWN : ENTITIES[random.nextInt(ENTITIES.length)];
            int variant = random.nextInt(3);
            boolean hasPosition = variant != 2;
            boolean rotate = variant != 0;
            float yaw = rotate ? (random.nextInt(256) * 360) / 256.0F : 0;
            float pitch = rotate ? (random.nextInt(256) * 360) / 256.0F : 0;
            boolean onGround = random.nextBoolean();
            EntityDelta delta;
            if (!hasPosition) delta = EntityDelta.ZERO;
            else if (random.nextInt(6) == 0) {
                var steps = new ArrayList<EntityDelta.Step>();
                for (int i = 0, n = 1 + random.nextInt(3); i < n; i++)
                    steps.add(new EntityDelta.Step(delta(random), delta(random), delta(random), 1 + random.nextInt(3)));
                delta = new EntityDelta.Stepped(steps);
            } else delta = new EntityDelta.Linear(delta(random), delta(random), delta(random));
            var packet = new ClientboundMoveEntity(id, delta, yaw, pitch, onGround, hasPosition, rotate);
            a.moveEntity(packet);
            b.moveEntity(packet);
            return "move " + id + " " + delta.getClass().getSimpleName();
        }
        if (op < 52) {
            int id = random.nextInt(10) == 0 ? UNKNOWN : ENTITIES[random.nextInt(ENTITIES.length)];
            var position =
                    new Vec3d(random.nextDouble() * 40 - 20, 60 + random.nextInt(8), random.nextDouble() * 40 - 20);
            float yaw = random.nextInt(360) - 180;
            float pitch = random.nextInt(4) == 0 ? random.nextInt(180) - 90 : 0;
            PositionPath path = random.nextInt(6) == 0
                    ? new PositionPath.Stepped(List.of(
                            new PositionPath.Step(new Vec3d(position.x() + 0.5, position.y(), position.z() + 0.5), 1),
                            new PositionPath.Step(position, 2)))
                    : new PositionPath.Linear(position);
            var packet = new ClientboundEntityPositionSync(id, path, yaw, pitch, random.nextBoolean());
            a.positionSync(packet);
            b.positionSync(packet);
            return "position sync " + id;
        }
        if (op < 70) {
            int pending = Math.max(0, a.player.lastTransactionSent.get() - a.player.lastTransactionReceived.get());
            int answered = random.nextInt(pending + 1);
            a.acknowledge(a.player.lastTransactionReceived.get() + answered);
            b.acknowledge(b.player.lastTransactionReceived.get() + answered);
            return "acknowledge " + answered;
        }
        if (op < 80) {
            int millis = random.nextInt(120);
            a.advance(millis);
            b.advance(millis);
            return "advance " + millis;
        }
        a.clientTick();
        b.clientTick();
        return "client tick";
    }

    private static short delta(Random random) {
        return (short) (random.nextInt(16385) - 8192);
    }

    private static final class Rig {
        final CultPlayer player;
        final EmbeddedChannel channel;
        final boolean sinks;
        int sunkMoves, sunkSyncs;

        Rig(Script script, boolean sinks) {
            this.sinks = sinks;
            player = player(script.observed, script.client);
            channel = (EmbeddedChannel) player.user.getChannel();
            channel.freezeTime();
            player.entityID = 7;
            player.x = 0;
            player.y = 64;
            player.z = 0;
            for (int i = 0; i < ENTITIES.length; i++) {
                int id = ENTITIES[i];
                player.packetEntityReplication.addEntity(
                        id, TYPES[i], new Vec3((id % 7) - 3 + 0.5, 64, (id % 5) - 2 + 0.5), 0, 0, 0, -1);
            }
            acknowledge(Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get()));
        }

        CultConnection connection() {
            return player.user.getCultConnection();
        }

        void moveEntity(ClientboundMoveEntity packet) {
            if (!sinks || !(packet.delta() instanceof EntityDelta.Linear linear)) {
                player.packetEntityReplication.onMoveEntity(event(packet), player, packet);
                return;
            }
            connection()
                    .move(
                            packet.entityId(),
                            linear.x(),
                            linear.y(),
                            linear.z(),
                            packet.yaw(),
                            packet.pitch(),
                            packet.onGround(),
                            packet.hasPosition(),
                            packet.hasRotation());
            sunkMoves++;
            try {
                player.packetEntityReplication.onMoveEntity(event(MoveEntityCodec.SUNK), player, MoveEntityCodec.SUNK);
            } finally {
                connection().sunkMove().release();
            }
        }

        PacketSendEvent<ClientboundMoveEntity> event(ClientboundMoveEntity packet) {
            return new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
        }

        void positionSync(ClientboundEntityPositionSync packet) {
            if (!sinks || !(packet.position() instanceof PositionPath.Linear linear)) {
                player.packetEntityReplication.onEntityPositionSync(syncEvent(packet), player, packet);
                return;
            }
            Vec3d end = linear.endPosition();
            connection()
                    .positionSync(
                            packet.entityId(),
                            end.x(),
                            end.y(),
                            end.z(),
                            packet.yaw(),
                            packet.pitch(),
                            packet.onGround());
            sunkSyncs++;
            try {
                player.packetEntityReplication.onEntityPositionSync(
                        syncEvent(EntityPositionSyncCodec.SUNK), player, EntityPositionSyncCodec.SUNK);
            } finally {
                connection().sunkPositionSync().release();
            }
        }

        PacketSendEvent<ClientboundEntityPositionSync> syncEvent(ClientboundEntityPositionSync packet) {
            return new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, packet, false);
        }

        void acknowledge(int transaction) {
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        void advance(int millis) {
            channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
        }

        void clientTick() {
            player.packetEntityReplication.onPlayerTickEnd(null);
        }

        String snapshot() {
            StringBuilder out = new StringBuilder();
            out.append("sent=")
                    .append(player.lastTransactionSent.get())
                    .append(" received=")
                    .append(player.lastTransactionReceived.get())
                    .append('\n');
            CompensatedEntities entities = player.compensatedEntities;
            for (var entry : new TreeMap<>(entities.entityMap).entrySet()) {
                out.append(entry.getKey()).append(' ');
                dump(out, entry.getValue(), 0, Collections.newSetFromMap(new IdentityHashMap<>()));
                out.append('\n');
            }
            for (int id : ENTITIES) {
                out.append("tracked ").append(id).append(' ');
                dump(out, entities.getTrackedEntity(id), 0, Collections.newSetFromMap(new IdentityHashMap<>()));
                out.append('\n');
            }
            return out.toString();
        }

        void close() {
            player.onRemove();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }
    }

    private static void dump(StringBuilder out, Object value, int depth, Set<Object> path) {
        if (value == null) {
            out.append("null");
            return;
        }
        Class<?> type = value.getClass();
        if (value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof String
                || value instanceof Enum<?>) {
            out.append(value);
            return;
        }
        if (type.isArray()) {
            out.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                if (i > 0) out.append(',');
                dump(out, Array.get(value, i), depth + 1, path);
            }
            out.append(']');
            return;
        }
        if (value instanceof Collection<?> collection) {
            List<String> elements = new ArrayList<>();
            for (Object element : collection) {
                StringBuilder dumped = new StringBuilder();
                dump(dumped, element, depth + 1, path);
                elements.add(dumped.toString());
            }
            if (value instanceof Set<?>) Collections.sort(elements);
            out.append(elements);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (var entry : map.entrySet()) {
                StringBuilder dumped = new StringBuilder();
                dump(dumped, entry.getKey(), depth + 1, path);
                dumped.append('=');
                dump(dumped, entry.getValue(), depth + 1, path);
                entries.add(dumped.toString());
            }
            Collections.sort(entries);
            out.append(entries);
            return;
        }
        if (type.isHidden() || type.isSynthetic()) {
            out.append("lambda");
            return;
        }
        if (!type.getName().startsWith("ac.cult.")
                || depth >= MAX_DEPTH
                || value instanceof CultPlayer
                || value instanceof CompensatedEntities
                || (depth > 0 && value instanceof PacketEntity)) {
            out.append(type.getSimpleName());
            return;
        }
        if (!path.add(value)) {
            out.append('^');
            return;
        }
        out.append(type.getSimpleName()).append('{');
        for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                field.setAccessible(true);
                out.append(field.getName()).append('=');
                try {
                    dump(out, field.get(value), depth + 1, path);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
                out.append(' ');
            }
        }
        out.append('}');
        path.remove(value);
    }

    private static CultPlayer player(ProtocolVersion observed, ProtocolVersion client) {
        OfflineCultTestBootstrap.installConfig();
        PlatformConnection platform = new PlatformConnection() {
            @Override
            public User.Profile authenticatedProfile() {
                return null;
            }

            @Override
            public PlayerBinding playerBinding() {
                return null;
            }

            @Override
            public ProtocolVersion getObservedProtocol() {
                return observed;
            }

            @Override
            public ProtocolVersion getClientProtocol() {
                return client;
            }

            @Override
            public void disconnect(Component reason) {}

            @Override
            public void sendMessage(Component message) {}
        };
        User user =
                TestUsers.create(new User.Profile(UUID.randomUUID(), "Sunk_Moves"), new EmbeddedChannel(), platform);
        CultPlayer player = new CultPlayer(user);
        user.bind(Mockito.mock(PlatformPlayer.class));
        return player;
    }
}
