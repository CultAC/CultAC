package ac.cult.cultac.checks.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestProtocolRuntime;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * One seeded entity packet script runs through a player that reuses its proof tasks and one that allocates each
 * afresh; tracked entities, FairReach targets and attack decisions match after every step. Reused tasks are
 * overwritten with plausible wrong values while they wait, so a task run after its reuse shows up as a difference.
 * With CULT_PROOF_TASK_DIGEST set to a file, the reference transcript's digest is appended to it.
 */
class ProofTaskReuseEquivalenceTest {
    private static final int STEPS = 1_500;
    private static final int[] PLAYERS = {3000, 3001, 3002};
    private static final int[] MOBS = {4000, 4001, 4002, 4003};
    private static final int[] MOB_TYPES = {
        EntityTypeIds.ZOMBIE, EntityTypeIds.PIG, EntityTypeIds.ARMOR_STAND, EntityTypeIds.CHICKEN
    };
    private static final int UNKNOWN = 5000;
    private static final int MAX_DEPTH = 5;
    private static final double POISON_X = 12345.678;
    private static final double POISON_Y = 64.25;
    private static final double POISON_Z = -9876.5;

    enum Script {
        V1_21_6(ProtocolVersion.V1_21_6, 61),
        V1_21_11(ProtocolVersion.V1_21_11, 62),
        V26_3(ProtocolVersion.V26_3, 63);

        final ProtocolVersion protocol;
        final long seed;

        Script(ProtocolVersion protocol, long seed) {
            this.protocol = protocol;
            this.seed = seed;
        }
    }

    @ParameterizedTest
    @EnumSource(Script.class)
    void reusedProofTasksApplyWhatFreshTasksApply(Script script) throws Exception {
        Rig fresh = new Rig(script.protocol, false);
        Rig reused = new Rig(script.protocol, true);
        try {
            Random random = new Random(script.seed);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            boolean waited = false;
            for (int step = 0; step < STEPS; step++) {
                String op = step(random, fresh, reused);
                String expected = fresh.snapshot();
                assertEquals(expected, reused.snapshot(), "step " + step + " (" + op + ")");
                digest.update((op + "\n" + expected).getBytes(StandardCharsets.UTF_8));
                waited |= reused.poison();
            }
            if (reused.reuses(reused.replication, "movementSlots"))
                assertTrue(waited, "entity movements wait for reuse");
            String out = System.getenv("CULT_PROOF_TASK_DIGEST");
            if (out != null && !out.isEmpty()) {
                Files.writeString(
                        Path.of(out),
                        script + " " + HexFormat.of().formatHex(digest.digest()) + "\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } finally {
            fresh.close();
            reused.close();
        }
    }

    private static String step(Random random, Rig a, Rig b) {
        int op = random.nextInt(100);
        if (op < 28) {
            int id = random.nextInt(8) == 0 ? UNKNOWN : anyEntity(random);
            boolean hasPosition = random.nextInt(8) != 0;
            boolean rotate = !hasPosition || random.nextBoolean();
            short dx = (short) (random.nextInt(16385) - 8192);
            short dy = (short) (random.nextInt(4097) - 2048);
            short dz = (short) (random.nextInt(16385) - 8192);
            float yaw = random.nextInt(360) - 180;
            float pitch = random.nextInt(180) - 90;
            boolean onGround = random.nextBoolean();
            both(
                    a,
                    b,
                    rig -> rig.moveEntity(new ClientboundMoveEntity(
                            id, new EntityDelta.Linear(dx, dy, dz), yaw, pitch, onGround, hasPosition, rotate)));
            return "move " + id;
        }
        if (op < 36) {
            int id = anyEntity(random);
            Vec3d position = position(random);
            float yaw = random.nextInt(360) - 180;
            boolean onGround = random.nextBoolean();
            both(
                    a,
                    b,
                    rig -> rig.teleport(
                            new ClientboundTeleportEntity(id, position, Vec3d.ZERO, yaw, 0.0F, Set.of(), onGround)));
            return "teleport " + id;
        }
        if (op < 42) {
            int id = anyEntity(random);
            Vec3d position = position(random);
            float yaw = random.nextInt(360) - 180;
            boolean onGround = random.nextBoolean();
            both(
                    a,
                    b,
                    rig -> rig.positionSync(new ClientboundEntityPositionSync(
                            id, new PositionPath.Linear(position), yaw, 0.0F, onGround)));
            return "position sync " + id;
        }
        if (op < 52) {
            int id = anyEntity(random);
            byte flags = (byte) random.nextInt(0x80);
            both(a, b, rig -> rig.entityData(id, flags));
            return "entity data " + id;
        }
        if (op < 62) {
            both(a, b, Rig::foreignPing);
            return "ping";
        }
        if (op < 74) {
            int pending = Math.max(0, a.player.lastTransactionSent.get() - a.player.lastTransactionReceived.get());
            // Now and then the receipt runs ahead of the sent count, so the next proofs run in place.
            int answered = random.nextInt(pending + 1) + (random.nextInt(6) == 0 ? 1 + random.nextInt(2) : 0);
            both(a, b, rig -> rig.acknowledge(rig.player.lastTransactionReceived.get() + answered));
            return "acknowledge " + answered;
        }
        if (op < 82) {
            int millis = random.nextInt(120);
            both(a, b, rig -> rig.advance(millis));
            return "advance " + millis;
        }
        if (op < 92) {
            both(a, b, Rig::clientTick);
            return "client tick";
        }
        if (op < 96) {
            int id = PLAYERS[random.nextInt(PLAYERS.length)];
            both(a, b, rig -> rig.attack(id));
            return "attack " + id;
        }
        int index = 1 + random.nextInt(PLAYERS.length + MOBS.length - 2);
        int id = index < PLAYERS.length ? PLAYERS[index] : MOBS[index - PLAYERS.length + 1];
        both(a, b, rig -> rig.respawn(id));
        return "respawn " + id;
    }

    private static void both(Rig a, Rig b, Consumer<Rig> action) {
        action.accept(a);
        action.accept(b);
    }

    private static int anyEntity(Random random) {
        int index = random.nextInt(PLAYERS.length + MOBS.length);
        return index < PLAYERS.length ? PLAYERS[index] : MOBS[index - PLAYERS.length];
    }

    private static Vec3d position(Random random) {
        double range = random.nextInt(6) == 0 ? 400 : 60;
        return new Vec3d(
                random.nextDouble() * range - range / 2,
                60 + random.nextInt(800) / 100.0,
                random.nextDouble() * range - range / 2);
    }

    private static final class Rig {
        final CultPlayer player;
        final EmbeddedChannel channel;
        final FairReach fairReach;
        final Object replication;
        final Method targetTick;
        final Method minDistance;
        final List<String> attacks = new ArrayList<>();

        Rig(ProtocolVersion protocol, boolean reuse) throws ReflectiveOperationException {
            player = player(protocol);
            channel = (EmbeddedChannel) player.user.getChannel();
            channel.freezeTime();
            replication = player.packetEntityReplication;
            fairReach = player.checkManager.getListener(FairReach.class);
            fairReach.max_ping_compensation = 60;
            player.entityID = 7;
            player.x = 0;
            player.y = 64;
            player.z = 0;
            targetTick = FairReach.FairReachEntity.class.getDeclaredMethod("tick");
            targetTick.setAccessible(true);
            minDistance = FairReach.FairReachEntity.class.getDeclaredMethod("getMinDistanceForReach");
            minDistance.setAccessible(true);
            if (!reuse) {
                emptyIfPresent(replication, "movementSlots");
            }
            for (int id : PLAYERS) add(id, EntityTypeIds.PLAYER);
            for (int i = 0; i < MOBS.length; i++) add(MOBS[i], MOB_TYPES[i]);
            acknowledge(Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get()));
        }

        void add(int id, int type) {
            Vec3 position = new Vec3((id % 7) - 3 + 0.5, 64, (id % 5) - 2 + 0.5);
            if (type == EntityTypeIds.PLAYER) {
                fairReach.onAddEntity(
                        null,
                        player,
                        new ClientboundAddEntity(
                                id, "minecraft:player", new Vec3d(position.x, position.y, position.z), 0, 0, 0));
            }
            player.packetEntityReplication.addEntity(id, type, position, 0, 0, 0, -1);
        }

        void moveEntity(ClientboundMoveEntity packet) {
            player.packetEntityReplication.onMoveEntity(
                    new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false),
                    player,
                    packet);
        }

        void teleport(ClientboundTeleportEntity packet) {
            player.packetEntityReplication.onTeleportEntity(
                    new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false),
                    player,
                    packet);
        }

        void positionSync(ClientboundEntityPositionSync packet) {
            player.packetEntityReplication.onEntityPositionSync(
                    new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, packet, false),
                    player,
                    packet);
        }

        void entityData(int id, byte flags) {
            var packet = new EntityMetadata(id, List.of(EntityMetadata.Entry.of(0, flags, new byte[] {flags}, 0, 1)));
            player.packetEntityReplication.onSetEntityData(
                    new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, metadataType(), packet, false),
                    player,
                    packet);
        }

        void foreignPing() {
            player.lastTransactionSent.set(
                    Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get()) + 1);
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
            try {
                for (FairReach.FairReachEntity target : fairReach.targetPlayers.values()) targetTick.invoke(target);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        void attack(int id) {
            var packet = new ServerboundInteract(id, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            var event =
                    new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
            fairReach.onInteract(event, player, packet);
            attacks.add(id + (event.isCancelled() ? " cancelled" : " allowed"));
        }

        void respawn(int id) {
            var packet = new ClientboundRemoveEntities(List.of(id));
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
            fairReach.onRemoveEntities(event, player, packet);
            player.packetEntityReplication.onRemoveEntities(event, player, packet);
            boolean isPlayer = id < MOBS[0];
            add(id, isPlayer ? EntityTypeIds.PLAYER : MOB_TYPES[id - MOBS[0]]);
        }

        String snapshot() {
            StringBuilder out = new StringBuilder();
            out.append("sent=")
                    .append(player.lastTransactionSent.get())
                    .append(" received=")
                    .append(player.lastTransactionReceived.get())
                    .append(" attacks=")
                    .append(attacks)
                    .append('\n');
            CompensatedEntities entities = player.compensatedEntities;
            for (var entry : new TreeMap<>(entities.entityMap).entrySet()) {
                out.append(entry.getKey()).append(' ');
                dump(out, entry.getValue(), 0, Collections.newSetFromMap(new IdentityHashMap<>()));
                out.append('\n');
            }
            for (var entry : new TreeMap<>(fairReach.targetPlayers).entrySet()) {
                FairReach.FairReachEntity target = entry.getValue();
                out.append("reach ")
                        .append(entry.getKey())
                        .append(' ')
                        .append(target.currentX)
                        .append(',')
                        .append(target.currentY)
                        .append(',')
                        .append(target.currentZ)
                        .append(" -> ")
                        .append(target.targetX)
                        .append(',')
                        .append(target.targetY)
                        .append(',')
                        .append(target.targetZ)
                        .append(" lerp ")
                        .append(target.lerpTicks)
                        .append(" min ");
                try {
                    out.append(minDistance.invoke(target));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                out.append('\n');
            }
            return out.toString();
        }

        boolean reuses(Object owner, String pool) {
            return field(owner.getClass(), pool) != null;
        }

        /** Overwrites every task waiting for reuse with values a stale run would visibly apply. */
        boolean poison() throws ReflectiveOperationException {
            boolean waited = false;
            for (Object movement : waiting(replication, "movementSlots", "filledMovementSlots", "freeMovementSlots")) {
                waited = true;
                Class<?> type = movement.getClass();
                set(movement, "entityId", MOBS[0]);
                set(movement, "kind", constant(type, "kind", "TELEPORT_ENTITY"));
                set(movement, "relative", false);
                set(movement, "hasPosition", true);
                set(movement, "x", POISON_X);
                set(movement, "y", POISON_Y);
                set(movement, "z", POISON_Z);
                set(movement, "path", EntityPositionPath.linear(new Vec3(POISON_X, POISON_Y, POISON_Z)));
                set(movement, "hasRotation", true);
                set(movement, "rotationYaw", 77.0F);
                set(movement, "rotationPitch", 33.0F);
                set(movement, "onGround", Boolean.TRUE);
                Class<?> change = field(type, "deltaMovement").getType();
                Constructor<?> constructor = change.getDeclaredConstructor(
                        boolean.class, double.class, double.class, double.class, int.class);
                constructor.setAccessible(true);
                set(movement, "deltaMovement", constructor.newInstance(true, 0.5, 0.5, 0.5, 0));
                set(movement, "relativeToClientPosition", false);
                set(movement, "proofTransaction", player.lastTransactionSent.get());
            }
            return waited;
        }

        /** The tasks in the free slots of a slot pool; filled slots each hold their own task. */
        private static List<Object> waiting(Object owner, String pool, String filled, String free)
                throws IllegalAccessException {
            if (field(owner.getClass(), pool) == null) return List.of();
            Object tasks = get(owner, pool);
            int filledSlots = (int) get(owner, filled);
            long freeSlots = (long) get(owner, free);
            assertEquals(0, filledSlots == Long.SIZE ? 0 : freeSlots >>> filledSlots, "only filled slots are free");
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < Array.getLength(tasks); i++) {
                Object task = Array.get(tasks, i);
                assertEquals(i < filledSlots, task != null, "only filled slots hold a task");
                if (task == null) continue;
                assertEquals(i, get(task, "slot"), "a task sits in its own slot");
                assertTrue(seen.add(task), "a task fills two slots");
                if ((freeSlots >>> i & 1) != 0) list.add(task);
            }
            return list;
        }

        void close() throws IOException {
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
            // A hash set of identity-hashed keys iterates in an order that differs between runs.
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

    private static Field field(Class<?> type, String name) {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Declared further up, or not at all.
            }
        }
        return null;
    }

    private static Object get(Object target, String name) throws IllegalAccessException {
        return field(target.getClass(), name).get(target);
    }

    private static void set(Object target, String name, Object value) throws IllegalAccessException {
        field(target.getClass(), name).set(target, value);
    }

    /** Leaves no room for tasks to wait in, so every task is allocated afresh. */
    private static void emptyIfPresent(Object target, String pool) throws IllegalAccessException {
        Field field = field(target.getClass(), pool);
        if (field != null) field.set(target, Array.newInstance(field.getType().getComponentType(), 0));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(Class<?> owner, String fieldName, String name) {
        return Enum.valueOf((Class<? extends Enum>) field(owner, fieldName).getType(), name);
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    private static CultPlayer player(ProtocolVersion protocol) {
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
                return protocol;
            }

            @Override
            public ProtocolVersion getClientProtocol() {
                return protocol;
            }

            @Override
            public void disconnect(Component reason) {}

            @Override
            public void sendMessage(Component message) {}
        };
        User user =
                TestUsers.create(new User.Profile(UUID.randomUUID(), "Proof_Tasks"), new EmbeddedChannel(), platform);
        CultPlayer player = new CultPlayer(user);
        user.bind(Mockito.mock(PlatformPlayer.class));
        return player;
    }
}
