package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
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
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Entity tracking and interpolation match the code they replace: tickClientEntities' root order against the
 * copy-filter-sort, ReachInterpolationData's kept movement bounds against a fresh computation, the target position
 * against the copied target box, step queue copies, and fromProtocol against its stream. Random streams of spawns,
 * relative moves, position syncs, teleports, passengers, removals, partly answered transactions, client ticks, tick
 * order resets and level clears drive two fixtures: the reference always takes the sort.
 */
class EntityTrackingParityTest {
    private static final String[] TYPES = {
        "minecraft:zombie",
        "minecraft:pig",
        "minecraft:oak_boat",
        "minecraft:minecart",
        "minecraft:chicken",
        "minecraft:armor_stand",
        "minecraft:horse",
        "minecraft:spider",
        "minecraft:strider",
        "minecraft:shulker",
        "minecraft:arrow",
        "minecraft:happy_ghast"
    };
    private static final Vec3 PLAYER_AT = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);

    private static int fastRoots;
    private static int sortedRoots;
    private static int activeStepped;

    @Test
    void modernClientsMatch() throws Exception {
        fastRoots = sortedRoots = activeStepped = 0;
        for (long seed = 1; seed <= 10; seed++) compare(seed, ClientVersion.V_26_3, 500);
        assertTrue(fastRoots > 0, "the add order never served the roots");
        assertTrue(sortedRoots > 0, "the sort never served the roots");
        assertTrue(activeStepped > 0, "no stepped interpolation was ever in progress");
    }

    @Test
    void everyInterpolationWriteRenewsTheKeptBounds() throws Exception {
        for (ClientVersion version : List.of(ClientVersion.V_26_3, ClientVersion.V_1_21_4)) {
            try (var f = new JavaTickFixture()) {
                CultPlayer player = f.player;
                JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", version);
                CompensatedEntities entities = player.compensatedEntities;
                int[] types = {EntityTypeIds.ZOMBIE, EntityTypeIds.OAK_BOAT, EntityTypeIds.ARMOR_STAND};
                for (int t = 0; t < types.length; t++)
                    entities.addEntity(10 + t, types[t], new Vec3(t * 2 + 0.5, 64, 2.5), 0, 0, 0);
                Random random = new Random(version.ordinal());
                for (int run = 0; run < 60; run++) {
                    PacketEntity entity = entities.getEntity(10 + random.nextInt(types.length));
                    SimpleCollisionBox start = box(player, entity, random);
                    Vec3 target = feet(box(player, entity, random));
                    var data = new ReachInterpolationData(
                            player, start, target.x, target.y, target.z, random.nextBoolean(), entity);
                    String where = version + " " + entity.type + " run " + run;
                    checkKeptBounds(data, where);
                    for (int op = 0; op < 40; op++) {
                        mutate(player, entity, data, random);
                        checkKeptBounds(data, where + " op " + op);
                    }
                }
            }
        }
    }

    private static SimpleCollisionBox box(CultPlayer player, PacketEntity entity, Random random) {
        return GetBoundingBox.getPacketEntityBoundingBox(
                player, coordinate(random, -4), 64 + coordinate(random, 0), coordinate(random, -4), entity);
    }

    // Half the coordinates have no short binary fraction, so box arithmetic rounds.
    private static double coordinate(Random random, double offset) {
        return offset + (random.nextBoolean() ? random.nextInt(65) / 8.0 : random.nextDouble() * 8);
    }

    private static Vec3 feet(SimpleCollisionBox box) {
        return new Vec3((box.minX + box.maxX) / 2.0D, box.minY, (box.minZ + box.maxZ) / 2.0D);
    }

    /** One call of a public ReachInterpolationData method that writes its state. */
    private static void mutate(CultPlayer player, PacketEntity entity, ReachInterpolationData data, Random random) {
        float yaw = random.nextInt(360) - 180;
        float pitch = random.nextInt(60) - 30;
        Vec3 movement =
                new Vec3(random.nextInt(9) / 8.0 - 0.5, random.nextInt(5) / 8.0 - 0.25, random.nextInt(9) / 8.0 - 0.5);
        switch (random.nextInt(11)) {
            case 0 -> {
                if (!data.usesSteppedInterpolation()) return;
                EntityPositionPath path = random.nextBoolean()
                        ? EntityPositionPath.linear(feet(box(player, entity, random)))
                        : new EntityPositionPath(
                                feet(box(player, entity, random)),
                                List.of(
                                        new EntityPositionPath.Step(feet(box(player, entity, random)), 1),
                                        new EntityPositionPath.Step(feet(box(player, entity, random)), 2)));
                if (random.nextBoolean())
                    data.setPhysicalBeforePathAppend(path, yaw, pitch, feet(box(player, entity, random)), yaw, pitch);
                data.appendPath(path, yaw, pitch);
            }
            case 1 -> data.setExactMovementLocation(box(player, entity, random));
            case 2 -> data.shiftTargetIfCollisionFree(player, movement);
            case 3 -> data.shiftRootVehicleTargetIfCollisionFree(player, entity, movement);
            case 4 -> data.shiftTargetRotationByPhysical(yaw, pitch);
            case 5 -> data.setExactRotation(yaw, pitch);
            case 6 -> data.setCurrentAndTargetRotation(yaw, pitch);
            case 7 -> data.updatePossibleStartingLocation(box(player, entity, random));
            case 8 -> data.tickMovement(random.nextBoolean(), random.nextBoolean());
            case 9 -> {
                if (!data.usesSteppedInterpolation()) return;
                data.setPhysicalBeforePathAppend(
                        EntityPositionPath.linear(feet(box(player, entity, random))),
                        yaw,
                        pitch,
                        feet(box(player, entity, random)),
                        yaw,
                        pitch);
            }
            default ->
                checkKeptBounds(data.copyAdvancedForClientTick(random.nextBoolean(), random.nextBoolean()), "copy");
        }
    }

    private static void checkKeptBounds(ReachInterpolationData data, String where) {
        ReachInterpolationData fresh = fresh(data);
        String kept = bits(data.getPossibleMovementLocationCombined(new SimpleCollisionBox()));
        assertEquals(bits(fresh.getPossibleMovementLocationCombined(new SimpleCollisionBox())), kept, where);
        assertEquals(kept, bits(data.getPossibleMovementLocationCombined(new SimpleCollisionBox())), where);
    }

    @Test
    void olderClientsMatch() throws Exception {
        for (long seed = 101; seed <= 104; seed++) compare(seed, ClientVersion.V_1_21_4, 400);
        for (long seed = 201; seed <= 203; seed++) compare(seed, ClientVersion.V_1_20_5, 400);
    }

    @Test
    void selfTiedWithAnEntityTicksAfterIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            CompensatedEntities entities = f.player.compensatedEntities;
            for (int id = 10; id < 14; id++)
                entities.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(id - 10, 64, 2), 0, 0, 0);
            entities.resetClientTickOrder();
            entities.appendSelfToClientTickOrder();
            assertEquals(1L, entities.getSelf().getClientTickOrder());
            List<PacketEntity> roots = rootsInAddOrder(entities);
            assertNotNull(roots);
            assertEquals(sortedRoots(entities), roots);
            assertSame(entities.getSelf(), roots.get(1));
        }
    }

    @Test
    void aDirectEntityMapReplacementTakesTheSort() throws Exception {
        try (var f = new JavaTickFixture()) {
            CompensatedEntities entities = f.player.compensatedEntities;
            for (int id = 10; id < 14; id++)
                entities.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(id - 10, 64, 2), 0, 0, 0);
            assertNotNull(rootsInAddOrder(entities));
            PacketEntity replaced = entities.getEntity(12);
            entities.addEntity(40, EntityTypeIds.PIG, new Vec3(3, 64, 5), 0, 0, 0);
            PacketEntity pig = entities.entityMap.remove(40);
            addOrder(entities).remove(pig);
            entities.entityMap.put(12, pig);
            assertEquals(addOrder(entities).size(), entities.entityMap.size());
            assertTrue(addOrder(entities).contains(replaced));
            assertNull(rootsInAddOrder(entities));
            List<PacketEntity> ticked = new ArrayList<>();
            entities.tickClientEntities(ticked::add);
            List<PacketEntity> expected = sortedRoots(entities);
            expected.remove(entities.getSelf());
            assertEquals(expected, ticked);
            assertTrue(ticked.contains(pig));
            assertFalse(ticked.contains(replaced));
        }
    }

    @Test
    void theAddOrderIsRecheckedAfterEveryMapWrite() throws Exception {
        try (var f = new JavaTickFixture()) {
            CompensatedEntities entities = f.player.compensatedEntities;
            for (int id = 10; id < 14; id++)
                entities.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(id - 10, 64, 2), 0, 0, 0);
            assertEquals(sortedRoots(entities), rootsInAddOrder(entities));
            assertEquals(sortedRoots(entities), rootsInAddOrder(entities));
            PacketEntity kept = entities.getEntity(11);
            entities.entityMap.put(11, new PacketEntity(EntityTypeIds.PIG, 40));
            assertNull(rootsInAddOrder(entities));
            assertNull(rootsInAddOrder(entities));
            entities.entityMap.put(11, kept);
            assertEquals(sortedRoots(entities), rootsInAddOrder(entities));
            entities.removeEntity(12);
            assertEquals(sortedRoots(entities), rootsInAddOrder(entities));
            entities.addEntity(12, EntityTypeIds.PIG, new Vec3(3, 64, 5), 0, 0, 0);
            entities.addEntity(13, EntityTypeIds.PIG, new Vec3(4, 64, 5), 0, 0, 0);
            assertEquals(sortedRoots(entities), rootsInAddOrder(entities));
            entities.removeEntity(99);
            assertEquals(sortedRoots(entities), rootsInAddOrder(entities));
            entities.clearEntityMap();
            assertEquals(sortedRoots(entities), rootsInAddOrder(entities));
            entities.addEntity(20, EntityTypeIds.PIG, new Vec3(1, 64, 4), 0, 0, 0);
            assertEquals(sortedRoots(entities), rootsInAddOrder(entities));
        }
    }

    @Test
    void reusedTickOrdersTakeTheSort() throws Exception {
        try (var f = new JavaTickFixture()) {
            CompensatedEntities entities = f.player.compensatedEntities;
            for (int id = 10; id < 14; id++)
                entities.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(id - 10, 64, 2), 0, 0, 0);
            entities.resetClientTickOrder();
            entities.addEntity(20, EntityTypeIds.PIG, new Vec3(1, 64, 4), 0, 0, 0);
            assertNull(rootsInAddOrder(entities));
            entities.removeEntity(10);
            assertNull(rootsInAddOrder(entities), "pig 1 now trails zombies 2 and 3");
            entities.clearClientLevel();
            entities.addEntity(30, EntityTypeIds.PIG, new Vec3(1, 64, 4), 0, 0, 0);
            assertEquals(sortedRoots(entities), rootsInAddOrder(entities));
        }
    }

    @Test
    void steppedPathsConvertAsTheStreamDid() {
        Random random = new Random(5);
        for (int i = 0; i < 2_000; i++) {
            List<PositionPath.Step> steps = new ArrayList<>();
            int count = 1 + random.nextInt(6);
            for (int s = 0; s < count; s++) {
                steps.add(new PositionPath.Step(
                        new Vec3d(coordinate(random), coordinate(random), coordinate(random)), random.nextInt(5)));
            }
            PositionPath path = random.nextInt(8) == 0
                    ? new PositionPath.Linear(steps.getFirst().position())
                    : new PositionPath.Stepped(steps);
            EntityPositionPath expected = streamFromProtocol(path);
            EntityPositionPath actual = EntityPositionPath.fromProtocol(path);
            assertEquals(describe(expected), describe(actual));
            assertEquals(expected, actual);
        }
    }

    private static double coordinate(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> -0.0D;
            case 1 -> random.nextInt(2_000_000) - 1_000_000;
            case 2 -> Double.longBitsToDouble(random.nextLong());
            default -> random.nextDouble() * 200 - 100;
        };
    }

    /** EntityPositionPath.fromProtocol before the loop. */
    private static EntityPositionPath streamFromProtocol(PositionPath path) {
        var position = path.endPosition();
        Vec3 end = new Vec3(position.x(), position.y(), position.z());
        if (path instanceof PositionPath.Linear) return EntityPositionPath.linear(end);
        var steps = ((PositionPath.Stepped) path)
                .steps().stream()
                        .map(step -> new EntityPositionPath.Step(
                                new Vec3(
                                        step.position().x(),
                                        step.position().y(),
                                        step.position().z()),
                                step.tickOffset()))
                        .toList();
        return new EntityPositionPath(end, steps);
    }

    private static String describe(EntityPositionPath path) {
        StringBuilder out = new StringBuilder(bits(path.endPosition()));
        for (EntityPositionPath.Step step : path.steps())
            out.append(' ').append(bits(step.position())).append('@').append(step.tickOffset());
        return out.toString();
    }

    private static void compare(long seed, ClientVersion version, int steps) throws Exception {
        try (var reference = new JavaTickFixture();
                var candidate = new JavaTickFixture()) {
            for (JavaTickFixture f : List.of(reference, candidate)) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                f.place(PLAYER_AT, Vec3.ZERO);
            }
            World old = new World(reference);
            World current = new World(candidate);
            Random random = new Random(seed);
            int moved = 0;
            for (int step = 0; step < steps; step++) {
                long op = random.nextLong();
                forceSort(reference.player.compensatedEntities);
                String before = current.digest();
                old.apply(op);
                String expected = old.digest();
                current.apply(op);
                String actual = current.digest();
                assertEquals(expected, actual, "seed=" + seed + " step=" + step);
                if (!actual.equals(before) && current.lastOpTicked) moved++;
                checkRoots(candidate.player.compensatedEntities);
                checkInterpolations(candidate.player.compensatedEntities, "seed=" + seed + " step=" + step);
            }
            assertTrue(moved > 0, "seed " + seed + " never moved an entity on a client tick");
            assertTrue(current.failures < steps / 20, "seed " + seed + ": " + current.failures + " operations threw");
        }
    }

    /** Keeps the add order one longer than entityMap, so tickClientEntities always sorts. */
    private static void forceSort(CompensatedEntities entities) throws Exception {
        List<PacketEntity> addOrder = addOrder(entities);
        if (!addOrder.contains(entities.getSelf())) addOrder.add(entities.getSelf());
    }

    private static void checkRoots(CompensatedEntities entities) throws Exception {
        List<PacketEntity> roots = rootsInAddOrder(entities);
        if (roots == null) {
            sortedRoots++;
            return;
        }
        fastRoots++;
        assertEquals(sortedRoots(entities), roots);
    }

    /** CompensatedEntities#tickClientEntities' roots before the add order. */
    private static List<PacketEntity> sortedRoots(CompensatedEntities entities) {
        var roots = new ArrayList<>(entities.entityMap.values());
        roots.add(entities.getSelf());
        roots.removeIf(entity -> entity.getRiding() != null || !entities.isTicking(entity));
        roots.sort(Comparator.comparingLong(PacketEntity::getClientTickOrder));
        return roots;
    }

    private static void checkInterpolations(CompensatedEntities entities, String where) throws Exception {
        List<PacketEntity> all = new ArrayList<>(entities.entityMap.values());
        all.add(entities.getSelf());
        for (PacketEntity entity : all) {
            for (ReachInterpolationData data :
                    new ReachInterpolationData[] {entity.newPacketLocation, entity.oldPacketLocation}) {
                if (data == null) continue;
                if (data.usesSteppedInterpolation() && data.hasActiveInterpolationTarget()) activeStepped++;
                ReachInterpolationData copy = fresh(data);
                String kept = bits(data.getPossibleMovementLocationCombined(new SimpleCollisionBox()));
                assertEquals(bits(copy.getPossibleMovementLocationCombined(new SimpleCollisionBox())), kept, where);
                assertEquals(kept, bits(data.getPossibleMovementLocationCombined()), where);
                SimpleCollisionBox target = data.getTargetLocation();
                Vec3 old = new Vec3(
                        (target.maxX - target.minX) / 2 + target.minX,
                        target.minY,
                        (target.maxZ - target.minZ) / 2 + target.minZ);
                assertEquals(bits(old), bits(data.targetPosition()), where);
                ArrayDeque<?> queue = queue(data);
                if (queue != null) assertEquals(List.copyOf(queue), List.copyOf(queue(copy)), where);
            }
        }
    }

    /** One fixture driven by the shared operation stream. */
    private static final class World {
        private final JavaTickFixture f;
        private final CultPlayer player;
        private final CompensatedEntities entities;
        private int nextId = 100;
        private String failure = "";
        int failures;
        boolean lastOpTicked;

        World(JavaTickFixture f) {
            this.f = f;
            this.player = f.player;
            this.entities = player.compensatedEntities;
        }

        void apply(long op) {
            lastOpTicked = false;
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
            if (kind < 14 || ids.size() < 3) {
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
            } else if (kind < 34) {
                int id = pick(random, ids);
                EntityDelta delta;
                if (random.nextInt(4) == 0) {
                    List<EntityDelta.Step> steps = new ArrayList<>();
                    int count = 1 + random.nextInt(3);
                    for (int s = 0; s < count; s++)
                        steps.add(new EntityDelta.Step(
                                delta(random), delta(random), delta(random), 1 + random.nextInt(3)));
                    delta = new EntityDelta.Stepped(steps);
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
            } else if (kind < 46) {
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
            } else if (kind < 54) {
                int id = pick(random, ids);
                Set<Relative> relatives = EnumSet.noneOf(Relative.class);
                Vec3d target = position(random);
                if (random.nextInt(3) == 0) {
                    relatives.add(Relative.X);
                    relatives.add(Relative.Z);
                    target = new Vec3d(random.nextInt(9) - 4 + 0.25, target.y(), random.nextInt(9) - 4);
                }
                send(
                        ClientboundPackets.TELEPORT_ENTITY,
                        new ClientboundTeleportEntity(
                                id,
                                target,
                                Vec3d.ZERO,
                                random.nextInt(360) - 180,
                                random.nextInt(60) - 30,
                                relatives,
                                random.nextBoolean()),
                        random);
            } else if (kind < 59) {
                List<Integer> removed = new ArrayList<>();
                removed.add(pick(random, ids));
                if (random.nextBoolean()) removed.add(pick(random, ids));
                send(ClientboundPackets.REMOVE_ENTITIES, new ClientboundRemoveEntities(removed), random);
            } else if (kind < 66) {
                int vehicle = pick(random, ids);
                List<Integer> passengers = new ArrayList<>();
                int count = random.nextInt(3);
                for (int p = 0; p < count; p++) {
                    int passenger = random.nextInt(4) == 0 ? player.entityID : pick(random, ids);
                    if (passenger != vehicle && !passengers.contains(passenger) && !rides(vehicle, passenger))
                        passengers.add(passenger);
                }
                send(ClientboundPackets.SET_PASSENGERS, new ClientboundSetPassengers(vehicle, passengers), random);
            } else if (kind < 76) {
                pong(random);
            } else if (kind < 94) {
                lastOpTicked = true;
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
            } else if (kind < 97) {
                entities.resetClientTickOrder();
                if (random.nextBoolean()) entities.appendSelfToClientTickOrder();
            } else if (kind < 99) {
                entities.appendSelfToClientTickOrder();
            } else {
                entities.clearClientLevel();
            }
        }

        // Whether passenger is vehicle or one of its vehicles, which a server mount would turn into a cycle.
        private boolean rides(int vehicle, int passenger) {
            PacketEntity riding = entities.getEntity(vehicle);
            PacketEntity candidate = passenger == player.entityID ? entities.getSelf() : entities.getEntity(passenger);
            for (PacketEntity e = riding; e != null; e = e.getRiding()) if (e == candidate) return true;
            return false;
        }

        private int pick(Random random, List<Integer> ids) {
            if (ids.isEmpty() || random.nextInt(20) == 0) return 9_000 + random.nextInt(3);
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

        // The client answers some of the transactions it has been sent, possibly none.
        private void pong(Random random) {
            int pending = player.lastTransactionSent.get() - player.lastTransactionReceived.get();
            if (pending <= 0) return;
            f.pongThrough(player.lastTransactionReceived.get() + 1 + random.nextInt(pending));
        }

        String digest() {
            StringBuilder out = new StringBuilder(failure);
            for (PacketEntity entity : entities.entityMap.values()) describe(out, entity);
            describe(out, entities.getSelf());
            out.append(" client=").append(bits(player.packetStateData.clientSidePosition));
            PredictionResult prediction =
                    player.checkManager.getSimulationProcessor().getLastPrediction();
            if (prediction != null) {
                out.append(" offset=")
                        .append(Double.doubleToRawLongBits(prediction.getOffset()))
                        .append(" flags=")
                        .append(String.valueOf(prediction.getFlags()).replaceAll("@[0-9a-f]+", ""));
            }
            out.append(" tx=")
                    .append(player.lastTransactionSent.get())
                    .append('/')
                    .append(player.lastTransactionReceived.get());
            return out.toString();
        }

        private static void describe(StringBuilder out, PacketEntity entity) {
            out.append('\n')
                    .append(entity.getEntityId())
                    .append(" type=")
                    .append(entity.type)
                    .append(" order=")
                    .append(entity.getClientTickOrder())
                    .append(" riding=")
                    .append(
                            entity.getRiding() == null
                                    ? "-"
                                    : entity.getRiding().getEntityId())
                    .append(" desync=")
                    .append(bits(entity.desyncClientPos))
                    .append(" physical=")
                    .append(bits(entity.clientPhysicalPosition))
                    .append(' ')
                    .append(Float.floatToRawIntBits(entity.clientPhysicalYaw))
                    .append(' ')
                    .append(Float.floatToRawIntBits(entity.clientPhysicalPitch))
                    .append(" new=")
                    .append(entity.newPacketLocation)
                    .append(" old=")
                    .append(entity.oldPacketLocation);
            if (entity.newPacketLocation != null) {
                out.append(" box=")
                        .append(bits(entity.getPossibleCollisionBoxes()))
                        .append(" move=")
                        .append(bits(entity.getPossibleMovementCollisionBoxes()))
                        .append(" candidates=");
                for (SimpleCollisionBox box : entity.getPossibleMovementCollisionBoxCandidates()) out.append(bits(box));
            }
        }
    }

    private static String bits(Vec3 v) {
        if (v == null) return "null";
        return Long.toHexString(Double.doubleToRawLongBits(v.x)) + ","
                + Long.toHexString(Double.doubleToRawLongBits(v.y)) + ","
                + Long.toHexString(Double.doubleToRawLongBits(v.z));
    }

    private static String bits(SimpleCollisionBox box) {
        return "[" + Long.toHexString(Double.doubleToRawLongBits(box.minX)) + ","
                + Long.toHexString(Double.doubleToRawLongBits(box.minY)) + ","
                + Long.toHexString(Double.doubleToRawLongBits(box.minZ)) + ","
                + Long.toHexString(Double.doubleToRawLongBits(box.maxX)) + ","
                + Long.toHexString(Double.doubleToRawLongBits(box.maxY)) + ","
                + Long.toHexString(Double.doubleToRawLongBits(box.maxZ)) + "," + box.isFullBlock() + "]";
    }

    @SuppressWarnings("unchecked")
    private static List<PacketEntity> rootsInAddOrder(CompensatedEntities entities) throws Exception {
        Method method = CompensatedEntities.class.getDeclaredMethod("rootsInAddOrder");
        method.setAccessible(true);
        return (List<PacketEntity>) method.invoke(entities);
    }

    @SuppressWarnings("unchecked")
    private static List<PacketEntity> addOrder(CompensatedEntities entities) throws Exception {
        Field field = CompensatedEntities.class.getDeclaredField("addOrder");
        field.setAccessible(true);
        return (List<PacketEntity>) field.get(entities);
    }

    /** A copy of data that computes its movement bounds from the same inputs at its first read. */
    private static ReachInterpolationData fresh(ReachInterpolationData data) {
        ReachInterpolationData copy = data.copy();
        try {
            Field field = ReachInterpolationData.class.getDeclaredField("boundsValid");
            field.setAccessible(true);
            field.setBoolean(copy, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return copy;
    }

    private static ArrayDeque<?> queue(ReachInterpolationData data) throws Exception {
        Field field = ReachInterpolationData.class.getDeclaredField("interpolationQueue");
        field.setAccessible(true);
        return (ArrayDeque<?>) field.get(data);
    }
}
