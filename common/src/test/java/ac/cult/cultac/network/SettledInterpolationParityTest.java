package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * PacketEntity#onMovement's settled skip leaves every entity exactly as running the tick would. Two fixtures take the
 * same operations; the reference runs every tick. After each operation every field of every entity and of its
 * interpolations, other than the skip's own bookkeeping and the kept bounds, must hold the same bits, and so must the
 * movement bounds read back.
 */
class SettledInterpolationParityTest {
    private static final String[] TYPES = {
        "minecraft:zombie",
        "minecraft:pig",
        "minecraft:oak_boat",
        "minecraft:minecart",
        "minecraft:armor_stand",
        "minecraft:horse",
        "minecraft:shulker",
        "minecraft:arrow",
        "minecraft:chicken"
    };
    private static final int[] DIRECT_TYPES = {
        EntityTypeIds.ZOMBIE,
        EntityTypeIds.OAK_BOAT,
        EntityTypeIds.ARMOR_STAND,
        EntityTypes.defaults().byKey("minecraft:minecart").id(),
        EntityTypeIds.PIG
    };
    private static final Vec3 PLAYER_AT = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Set<String> SKIP_BOOKKEEPING = Set.of(
            "settledInterpolation",
            "settledPhysicalPosition",
            "exactAfterClientTickBox",
            "tracker",
            "loadedChunkPosition",
            "loadedChunkWorld",
            "loadedChunkEpoch",
            "loadedChunk");

    private int settledStepped;
    private int settledLegacy;
    private int skippedTicks;

    @Test
    void packetStreamsMatch() throws Exception {
        for (long seed = 1; seed <= 8; seed++) stream(seed, ClientVersion.V_26_3, 600);
        for (long seed = 101; seed <= 104; seed++) stream(seed, ClientVersion.V_1_21_4, 600);
        assertTrue(settledStepped > 0, "no stepped interpolation ever settled");
        assertTrue(settledLegacy > 0, "no legacy interpolation ever settled");
        assertTrue(skippedTicks > 0, "no tick was ever skipped");
    }

    @Test
    void directWritesMatch() throws Exception {
        for (ClientVersion version : List.of(ClientVersion.V_26_3, ClientVersion.V_1_21_4)) {
            for (long seed = 1; seed <= 6; seed++) direct(seed, version, 500);
        }
        assertTrue(settledStepped > 0, "no stepped interpolation ever settled");
        assertTrue(settledLegacy > 0, "no legacy interpolation ever settled");
        assertTrue(skippedTicks > 0, "no tick was ever skipped");
    }

    private void stream(long seed, ClientVersion version, int steps) throws Exception {
        try (var reference = new JavaTickFixture();
                var candidate = new JavaTickFixture()) {
            StreamWorld old = new StreamWorld(reference, version, false);
            StreamWorld current = new StreamWorld(candidate, version, true);
            Random random = new Random(seed);
            for (int step = 0; step < steps; step++) {
                long op = random.nextLong();
                // Timer checks pace ticks against the wall clock; both fixtures must stay inside their balance.
                reference.resetTimerBalances();
                candidate.resetTimerBalances();
                forgetSettled(old.entities);
                old.apply(op);
                observeSettled(current.entities);
                current.apply(op);
                assertEquals(
                        digest(old.entities, old.failure),
                        digest(current.entities, current.failure),
                        version + " seed=" + seed + " step=" + step);
            }
            assertTrue(current.failures < steps / 20, "seed " + seed + ": " + current.failures + " operations threw");
        }
    }

    private void direct(long seed, ClientVersion version, int steps) throws Exception {
        try (var reference = new JavaTickFixture();
                var candidate = new JavaTickFixture()) {
            DirectWorld old = new DirectWorld(reference, version, false);
            DirectWorld current = new DirectWorld(candidate, version, true);
            Random random = new Random(seed * 31 + version.ordinal());
            for (int step = 0; step < steps; step++) {
                long op = random.nextLong();
                forgetSettled(old.entities);
                old.apply(op);
                observeSettled(current.entities);
                current.apply(op);
                assertEquals(
                        digest(old.entities, old.failure),
                        digest(current.entities, current.failure),
                        version + " seed=" + seed + " step=" + step);
            }
        }
    }

    // Counts settled interpolations about to tick, which the candidate then skips.
    private void observeSettled(CompensatedEntities entities) {
        for (PacketEntity entity : entities.entityMap.values()) {
            ReachInterpolationData data = entity.newPacketLocation;
            if (data == null || !data.isSettled()) continue;
            if (data.usesSteppedInterpolation()) settledStepped++;
            else settledLegacy++;
        }
    }

    private void countSkips(CompensatedEntities entities, boolean counts, Runnable tick) {
        if (!counts) {
            forgetSettled(entities);
            tick.run();
            return;
        }
        List<PacketEntity> settled = new ArrayList<>();
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entity.newPacketLocation != null && entity.newPacketLocation.isSettled()) settled.add(entity);
        }
        List<ReachInterpolationData> before = new ArrayList<>();
        for (PacketEntity entity : settled) before.add(entity.newPacketLocation);
        tick.run();
        for (int i = 0; i < settled.size(); i++) {
            if (settled.get(i).newPacketLocation == before.get(i)
                    && before.get(i).isSettled()) skippedTicks++;
        }
    }

    /** A packet stream through the replication handlers, with runs of client ticks. */
    private final class StreamWorld {
        private final JavaTickFixture f;
        private final CultPlayer player;
        final CompensatedEntities entities;
        private int nextId = 100;
        private final boolean counts;
        String failure = "";
        int failures;

        StreamWorld(JavaTickFixture f, ClientVersion version, boolean counts) throws Exception {
            this.counts = counts;
            this.f = f;
            this.player = f.player;
            this.entities = player.compensatedEntities;
            JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", version);
            f.place(PLAYER_AT, Vec3.ZERO);
        }

        void apply(long op) {
            failure = "";
            try {
                applyOrThrow(new Random(op));
            } catch (RuntimeException | AssertionError e) {
                failure = e.getClass().getName() + ": " + e.getMessage();
                failures++;
            }
        }

        private void applyOrThrow(Random random) {
            int kind = random.nextInt(100);
            List<Integer> ids = new ArrayList<>(entities.entityMap.keySet());
            ids.sort(null);
            if (kind < 10 || ids.size() < 3) {
                int id = !ids.isEmpty() && random.nextInt(8) == 0 ? ids.get(random.nextInt(ids.size())) : nextId++;
                send(
                        ClientboundPackets.ADD_ENTITY,
                        new ClientboundAddEntity(
                                id,
                                TYPES[random.nextInt(TYPES.length)],
                                position(random),
                                random.nextInt(360) - 180,
                                random.nextInt(60) - 30,
                                0),
                        random);
            } else if (kind < 24) {
                int id = pick(random, ids);
                EntityDelta delta;
                if (random.nextInt(4) == 0) {
                    List<EntityDelta.Step> steps = new ArrayList<>();
                    int count = 1 + random.nextInt(3);
                    for (int s = 0; s < count; s++)
                        steps.add(new EntityDelta.Step(
                                delta(random), delta(random), delta(random), 1 + random.nextInt(3)));
                    delta = new EntityDelta.Stepped(steps);
                } else if (random.nextInt(4) == 0) {
                    delta = new EntityDelta.Linear((short) 0, (short) 0, (short) 0);
                } else {
                    delta = new EntityDelta.Linear(delta(random), delta(random), delta(random));
                }
                send(
                        ClientboundPackets.MOVE_ENTITY,
                        new ClientboundMoveEntity(
                                id,
                                delta,
                                random.nextInt(360) - 180,
                                random.nextInt(60) - 30,
                                random.nextBoolean(),
                                random.nextInt(5) != 0,
                                random.nextBoolean()),
                        random);
            } else if (kind < 31) {
                int id = pick(random, ids);
                PositionPath path;
                if (random.nextInt(3) == 0) {
                    List<PositionPath.Step> steps = new ArrayList<>();
                    int count = 1 + random.nextInt(3);
                    for (int s = 0; s < count; s++)
                        steps.add(new PositionPath.Step(position(random), 1 + random.nextInt(3)));
                    path = new PositionPath.Stepped(steps);
                } else {
                    path = new PositionPath.Linear(position(random));
                }
                send(
                        ClientboundPackets.ENTITY_POSITION_SYNC,
                        new ClientboundEntityPositionSync(
                                id, path, random.nextInt(360) - 180, random.nextInt(60) - 30, random.nextBoolean()),
                        random);
            } else if (kind < 34) {
                send(
                        ClientboundPackets.TELEPORT_ENTITY,
                        new ClientboundTeleportEntity(
                                pick(random, ids),
                                position(random),
                                Vec3d.ZERO,
                                random.nextInt(360) - 180,
                                random.nextInt(60) - 30,
                                Set.of(),
                                random.nextBoolean()),
                        random);
            } else if (kind < 36) {
                send(
                        ClientboundPackets.REMOVE_ENTITIES,
                        new ClientboundRemoveEntities(List.of(pick(random, ids))),
                        random);
            } else if (kind < 40) {
                int vehicle = pick(random, ids);
                List<Integer> passengers = new ArrayList<>();
                if (random.nextInt(3) == 0) passengers.add(player.entityID);
                else if (random.nextBoolean()) {
                    int passenger = pick(random, ids);
                    if (passenger != vehicle && !rides(vehicle, passenger)) passengers.add(passenger);
                }
                send(ClientboundPackets.SET_PASSENGERS, new ClientboundSetPassengers(vehicle, passengers), random);
            } else if (kind < 50) {
                pong(random);
            } else {
                int ticks = 1 + random.nextInt(4);
                for (int t = 0; t < ticks; t++) countSkips(entities, counts, () -> tick(random));
            }
        }

        private void tick(Random random) {
            PacketEntity vehicle = entities.getSelf().getRiding();
            if (vehicle == null) {
                f.move(PLAYER_AT, true);
                f.tickEnd();
                return;
            }
            while (vehicle.getRiding() != null) vehicle = vehicle.getRiding();
            Vec3 at = vehicle.newPacketLocation == null ? PLAYER_AT : vehicle.newPacketLocation.targetPosition();
            Vec3 to = at.add((random.nextInt(9) - 4) / 16.0, 0, (random.nextInt(9) - 4) / 16.0);
            try {
                f.rideTick(to, true);
            } catch (AssertionError ignored) {
                // A ridden tick without a fresh prediction still drives the vehicle's interpolation.
            }
        }

        private boolean rides(int vehicle, int passenger) {
            PacketEntity candidate = entities.getEntity(passenger);
            for (PacketEntity e = entities.getEntity(vehicle); e != null; e = e.getRiding())
                if (e == candidate) return true;
            return false;
        }

        private int pick(Random random, List<Integer> ids) {
            if (ids.isEmpty() || random.nextInt(25) == 0) return 9_000 + random.nextInt(3);
            return ids.get(random.nextInt(ids.size()));
        }

        private static short delta(Random random) {
            return (short) (random.nextInt(4097) - 2048);
        }

        private static Vec3d position(Random random) {
            boolean far = random.nextInt(6) == 0;
            double x = (far ? 60 : 0) + random.nextInt(97) / 8.0 - 6 + (random.nextBoolean() ? random.nextDouble() : 0);
            double z = random.nextInt(97) / 8.0 - 6;
            double y = JavaTickFixture.FLOOR_Y + random.nextInt(25) / 8.0;
            return new Vec3d(x, y, z);
        }

        private <T extends ClientboundPacket> void send(PacketType<T> type, T packet, Random random) {
            var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
            Consumer<PacketSendEvent<T>> handler = handler(type);
            handler.accept(event);
            event.tasksAfter().forEach(Runnable::run);
            if (random.nextInt(3) == 0) pong(random);
        }

        @SuppressWarnings("unchecked")
        private <T extends ClientboundPacket> Consumer<PacketSendEvent<T>> handler(PacketType<T> type) {
            var replication = player.packetEntityReplication;
            Consumer<?> handler;
            if (type == ClientboundPackets.ADD_ENTITY) {
                handler =
                        (PacketSendEvent<ClientboundAddEntity> e) -> replication.onAddEntity(e, player, e.getPacket());
            } else if (type == ClientboundPackets.MOVE_ENTITY) {
                handler = (PacketSendEvent<ClientboundMoveEntity> e) ->
                        replication.onMoveEntity(e, player, e.getPacket());
            } else if (type == ClientboundPackets.ENTITY_POSITION_SYNC) {
                handler = (PacketSendEvent<ClientboundEntityPositionSync> e) ->
                        replication.onEntityPositionSync(e, player, e.getPacket());
            } else if (type == ClientboundPackets.TELEPORT_ENTITY) {
                handler = (PacketSendEvent<ClientboundTeleportEntity> e) ->
                        replication.onTeleportEntity(e, player, e.getPacket());
            } else if (type == ClientboundPackets.REMOVE_ENTITIES) {
                handler = (PacketSendEvent<ClientboundRemoveEntities> e) ->
                        replication.onRemoveEntities(e, player, e.getPacket());
            } else {
                handler = (PacketSendEvent<ClientboundSetPassengers> e) ->
                        replication.onSetPassengers(e, player, e.getPacket());
            }
            return (Consumer<PacketSendEvent<T>>) handler;
        }

        private void pong(Random random) {
            int pending = player.lastTransactionSent.get() - player.lastTransactionReceived.get();
            if (pending <= 0) return;
            f.pongThrough(player.lastTransactionReceived.get() + 1 + random.nextInt(pending));
        }
    }

    /**
     * Direct calls on a few entities: client ticks, every public interpolation write, preserving-physical ticks with a
     * copied interpolation, an earlier interpolation restored, a physical position replaced by equal bits, and the
     * exactness and second-transaction inputs toggled.
     */
    private final class DirectWorld {
        private final CultPlayer player;
        final CompensatedEntities entities;
        private final List<List<ReachInterpolationData>> history = new ArrayList<>();
        private final boolean counts;
        String failure = "";

        DirectWorld(JavaTickFixture f, ClientVersion version, boolean counts) throws Exception {
            this.counts = counts;
            this.player = f.player;
            this.entities = player.compensatedEntities;
            JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", version);
            f.place(PLAYER_AT, Vec3.ZERO);
            for (int t = 0; t < DIRECT_TYPES.length; t++) {
                entities.addEntity(10 + t, DIRECT_TYPES[t], new Vec3(t * 2 + 0.5, 64, 2.5), 0, 0, 0);
                history.add(new ArrayList<>());
            }
        }

        void apply(long op) {
            failure = "";
            try {
                applyOrThrow(new Random(op));
            } catch (RuntimeException | AssertionError e) {
                failure = e.getClass().getName() + ": " + e.getMessage();
            }
        }

        private void applyOrThrow(Random random) {
            int index = random.nextInt(DIRECT_TYPES.length);
            PacketEntity entity = entities.getEntity(10 + index);
            ReachInterpolationData data = entity.newPacketLocation;
            int kind = random.nextInt(100);
            if (kind < 55) {
                int ticks = 1 + random.nextInt(4);
                boolean reliably = random.nextInt(5) != 0;
                for (int t = 0; t < ticks; t++) {
                    countSkips(entities, counts, () -> {
                        for (int id = 10; id < 10 + DIRECT_TYPES.length; id++)
                            entities.getEntity(id).onMovement(player, reliably);
                    });
                }
            } else if (kind < 70) {
                if (data != null) {
                    history.get(index).add(data);
                    mutate(entity, data, random);
                }
            } else if (kind < 75) {
                history.get(index).add(data);
                entity.tickPacketInterpolationPreservingPhysical(player, feet(box(entity, random)), data);
            } else if (kind < 78) {
                entity.tickPacketInterpolationPreservingPhysical(player, entity.clientPhysicalPosition);
            } else if (kind < 83) {
                List<ReachInterpolationData> earlier = history.get(index);
                if (!earlier.isEmpty()) entity.newPacketLocation = earlier.get(random.nextInt(earlier.size()));
            } else if (kind < 88) {
                Vec3 physical = entity.clientPhysicalPosition;
                if (physical != null) entity.clientPhysicalPosition = new Vec3(physical.x, physical.y, physical.z);
            } else if (kind < 91) {
                entity.clientPhysicalPosition = feet(box(entity, random));
            } else if (kind < 95) {
                entity.clientPhysicalPositionExact = !entity.clientPhysicalPositionExact;
            } else if (kind < 98) {
                if (data != null) entity.oldPacketLocation = data.copy();
            } else {
                entity.onSecondTransaction();
            }
        }

        private void mutate(PacketEntity entity, ReachInterpolationData data, Random random) {
            float yaw = random.nextInt(360) - 180;
            float pitch = random.nextInt(60) - 30;
            Vec3 movement = new Vec3(
                    random.nextInt(9) / 8.0 - 0.5, random.nextInt(5) / 8.0 - 0.25, random.nextInt(9) / 8.0 - 0.5);
            switch (random.nextInt(10)) {
                case 0 -> {
                    if (!data.usesSteppedInterpolation()) return;
                    EntityPositionPath path = random.nextBoolean()
                            ? EntityPositionPath.linear(feet(box(entity, random)))
                            : new EntityPositionPath(
                                    feet(box(entity, random)),
                                    List.of(
                                            new EntityPositionPath.Step(feet(box(entity, random)), 1),
                                            new EntityPositionPath.Step(feet(box(entity, random)), 2)));
                    if (random.nextBoolean())
                        data.setPhysicalBeforePathAppend(path, yaw, pitch, feet(box(entity, random)), yaw, pitch);
                    data.appendPath(path, yaw, pitch);
                }
                case 1 -> data.setExactMovementLocation(box(entity, random));
                case 2 -> data.shiftTargetIfCollisionFree(player, movement);
                case 3 -> data.shiftRootVehicleTargetIfCollisionFree(player, entity, movement);
                case 4 -> data.shiftTargetRotationByPhysical(yaw, pitch);
                case 5 -> data.setExactRotation(yaw, pitch);
                case 6 -> data.setCurrentAndTargetRotation(yaw, pitch);
                case 7 -> data.updatePossibleStartingLocation(box(entity, random));
                case 8 -> data.tickMovement(random.nextBoolean(), random.nextBoolean());
                default -> {
                    if (!data.usesSteppedInterpolation()) return;
                    data.setPhysicalBeforePathAppend(
                            EntityPositionPath.linear(feet(box(entity, random))),
                            yaw,
                            pitch,
                            feet(box(entity, random)),
                            yaw,
                            pitch);
                }
            }
        }

        private SimpleCollisionBox box(PacketEntity entity, Random random) {
            return GetBoundingBox.getPacketEntityBoundingBox(
                    player, coordinate(random, -4), 64 + coordinate(random, 0), coordinate(random, -4), entity);
        }
    }

    // Half the coordinates have no short binary fraction, so box arithmetic rounds.
    private static double coordinate(Random random, double offset) {
        return offset + (random.nextBoolean() ? random.nextInt(65) / 8.0 : random.nextDouble() * 8);
    }

    private static Vec3 feet(SimpleCollisionBox box) {
        return new Vec3((box.minX + box.maxX) / 2.0D, box.minY, (box.minZ + box.maxZ) / 2.0D);
    }

    private static String digest(CompensatedEntities entities, String failure) throws Exception {
        StringBuilder out = new StringBuilder(failure);
        List<PacketEntity> all = new ArrayList<>(entities.entityMap.values());
        all.sort((a, b) -> Integer.compare(a.getEntityId(), b.getEntityId()));
        all.add(entities.getSelf());
        for (PacketEntity entity : all) {
            out.append('\n')
                    .append(entity.getEntityId())
                    .append(' ')
                    .append(entity.getClass().getSimpleName());
            for (Class<?> c = entity.getClass(); c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || SKIP_BOOKKEEPING.contains(field.getName())) continue;
                    field.setAccessible(true);
                    out.append(' ').append(field.getName()).append('=');
                    value(out, field.get(entity), 0);
                }
            }
        }
        return out.toString();
    }

    private static void interpolation(StringBuilder out, ReachInterpolationData data) throws Exception {
        out.append('{');
        for (Field field : ReachInterpolationData.class.getDeclaredFields()) {
            String name = field.getName();
            if (Modifier.isStatic(field.getModifiers())
                    || name.equals("settled")
                    || name.startsWith("probe")
                    || name.startsWith("bounds")) continue;
            field.setAccessible(true);
            out.append(name).append('=');
            value(out, field.get(data), 1);
            out.append(' ');
        }
        out.append("bounds=");
        value(out, data.getPossibleMovementLocationCombined(new SimpleCollisionBox()), 1);
        out.append('}');
    }

    private static void value(StringBuilder out, Object value, int depth) throws Exception {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Double d) {
            out.append(Long.toHexString(Double.doubleToRawLongBits(d)));
        } else if (value instanceof Float f) {
            out.append(Integer.toHexString(Float.floatToRawIntBits(f)));
        } else if (value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof String
                || value instanceof Enum<?>) {
            out.append(value);
        } else if (value.getClass().getName().startsWith("java.")) {
            out.append(value.getClass().getSimpleName());
        } else if (value instanceof ReachInterpolationData data) {
            interpolation(out, data);
        } else if (value instanceof PacketEntity entity) {
            out.append('#').append(entity.getEntityId());
        } else if (value instanceof CultPlayer || value instanceof CompensatedEntities) {
            out.append(value.getClass().getSimpleName());
        } else if (value instanceof Collection<?> collection) {
            out.append('[');
            for (Object element : collection) {
                value(out, element, depth + 1);
                out.append(',');
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                value(out, entry.getKey(), depth + 1);
                out.append(':');
                value(out, entry.getValue(), depth + 1);
                out.append(',');
            }
            out.append('}');
        } else if (value.getClass().isArray()) {
            out.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                value(out, Array.get(value, i), depth + 1);
                out.append(',');
            }
            out.append(']');
        } else if (depth > 5) {
            out.append(value.getClass().getSimpleName());
        } else {
            out.append(value.getClass().getSimpleName()).append('(');
            for (Class<?> c = value.getClass(); c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    out.append(field.getName()).append('=');
                    value(out, field.get(value), depth + 1);
                    out.append(' ');
                }
            }
            out.append(')');
        }
    }

    /** Clears every entity's settled state, so its next PacketEntity#onMovement runs in full, as before the skip. */
    private static void forgetSettled(CompensatedEntities entities) {
        try {
            Field field = PacketEntity.class.getDeclaredField("settledInterpolation");
            field.setAccessible(true);
            for (PacketEntity entity : entities.entityMap.values()) field.set(entity, null);
            field.set(entities.getSelf(), null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
