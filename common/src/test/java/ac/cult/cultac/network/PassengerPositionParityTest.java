package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * CompensatedEntities#updatePassengerPositions positions the local player's own riding trees exactly as an
 * unconditional scan of them does, and no other rider, across mount, eject, removal, untracked riders, riding chains
 * and level clears.
 */
class PassengerPositionParityTest {
    private static final int[] TYPES = {
        EntityTypeIds.OAK_BOAT,
        modelId("minecraft:minecart"),
        EntityTypeIds.HORSE,
        EntityTypeIds.ZOMBIE,
        EntityTypeIds.CHICKEN,
        EntityTypeIds.HAPPY_GHAST,
        EntityTypeIds.STRIDER,
        EntityTypeIds.CAMEL,
        EntityTypeIds.PIG,
        EntityTypeIds.SPIDER
    };

    private static int modelId(String key) {
        for (EntityTypes.Type type : EntityTypes.defaults().types())
            if (type.key().equals(key)) return type.id();
        throw new IllegalArgumentException(key);
    }

    @Test
    void randomRidingSequencesMatchTheFullScanOnModernClients() throws Exception {
        for (long seed = 1; seed <= 12; seed++) compare(seed, ClientVersion.V_26_3);
    }

    @Test
    void randomRidingSequencesMatchTheFullScanOnOlderClients() throws Exception {
        for (long seed = 101; seed <= 106; seed++) compare(seed, ClientVersion.V_1_21_2);
    }

    @Test
    void noPassengersSkipsAndMountingResumes() throws Exception {
        try (var f = new JavaTickFixture()) {
            CompensatedEntities entities = f.player.compensatedEntities;
            entities.addEntity(10, EntityTypeIds.OAK_BOAT, new Vec3(2, 64, 2), 0, 0, 0);
            entities.addEntity(11, EntityTypeIds.ZOMBIE, new Vec3(5, 64, 5), 0, 0, 0);
            PacketEntity boat = entities.getEntity(10);
            PacketEntity zombie = entities.getEntity(11);
            assertEquals(0, trackedPassengers(entities));
            zombie.mount(boat);
            assertEquals(1, trackedPassengers(entities));
            entities.updatePassengerPositions();
            var box = zombie.getPossibleCollisionBoxes();
            assertEquals(5.0, (box.minX + box.maxX) / 2, 1e-9, "a rider outside the own riding tree was positioned");
            entities.getSelf().mount(boat);
            assertEquals(2, trackedPassengers(entities));
            entities.updatePassengerPositions();
            box = zombie.getPossibleCollisionBoxes();
            assertEquals(2.0, (box.minX + box.maxX) / 2, 1e-9);
            entities.getSelf().eject();
            zombie.eject();
            zombie.eject();
            assertEquals(0, trackedPassengers(entities));
            zombie.mount(boat);
            entities.removeEntity(10);
            assertEquals(0, trackedPassengers(entities));
            entities.getSelf().mount(zombie);
            assertEquals(1, trackedPassengers(entities));
            entities.clearClientLevel();
            assertEquals(0, trackedPassengers(entities));
            entities.getSelf().eject();
            assertEquals(0, trackedPassengers(entities));
        }
    }

    @Test
    void countFollowsTheEntityMapAcrossLevelChanges() throws Exception {
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            CompensatedEntities entities = player.compensatedEntities;
            mountedPairs(entities, 10);
            assertInvariant(entities, 2);

            respawn(f, "minecraft:the_nether");
            assertEquals(0, entities.entityMap.size());
            assertInvariant(entities, 0);
            entities.getSelf().eject();
            assertInvariant(entities, 0);

            mountedPairs(entities, 20);
            assertInvariant(entities, 2);
            f.fixture.respawn((byte) 0);
            assertInvariant(entities, entities.entityMap.isEmpty() ? 0 : -1);

            mountedPairs(entities, 30);
            int before = sumOverMap(entities);
            assertTrue(before > 0);
            var spawn = new PlayerSpawnInfo(
                    ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld"),
                    "minecraft:overworld",
                    GameMode.SURVIVAL);
            var login = new ClientboundLogin(player.entityID, false, spawn);
            var loginEvent =
                    new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.LOGIN, login, false);
            new PacketPlayerRespawn().onLogin(loginEvent, player, login);
            loginEvent.tasksAfter().forEach(Runnable::run);
            assertInvariant(entities, before);
            // The level clear is a real-time task on the transaction after LOGIN.
            int next = player.lastTransactionSent.get() + 1;
            player.lastTransactionReceived.set(next);
            player.latencyUtils.handleNettySyncTransaction(next);
            assertEquals(0, entities.entityMap.size());
            assertInvariant(entities, 0);

            entities.addEntity(40, EntityTypeIds.OAK_BOAT, new Vec3(1, 64, 1), 0, 0, 0);
            entities.addEntity(41, EntityTypeIds.ZOMBIE, new Vec3(2, 64, 2), 0, 0, 0);
            entities.addEntity(42, modelId("minecraft:minecart"), new Vec3(3, 64, 3), 0, 0, 0);
            PacketEntity zombie = entities.getEntity(41);
            zombie.mount(entities.getEntity(40));
            assertInvariant(entities, 1);
            entities.removeEntity(40);
            assertInvariant(entities, 0);
            zombie.mount(entities.getEntity(42));
            assertInvariant(entities, 1);
            entities.removeEntity(41);
            assertInvariant(entities, 1);
            entities.addEntity(42, EntityTypeIds.OAK_BOAT, new Vec3(3, 64, 3), 0, 0, 0);
            assertInvariant(entities, 0);
            entities.vehicles.applyVehiclePassengers(42, new int[] {player.entityID});
            assertInvariant(entities, 1);
            entities.clearClientLevel();
            assertInvariant(entities, 0);
        }
    }

    /** A self-carrying boat and a zombie-ridden horse, with ids from {@code base}. */
    private static void mountedPairs(CompensatedEntities entities, int base) {
        entities.addEntity(base, EntityTypeIds.OAK_BOAT, new Vec3(1.5, 64, 1.5), 0, 0, 0);
        entities.addEntity(base + 1, EntityTypeIds.HORSE, new Vec3(3.5, 64, 3.5), 0, 0, 0);
        entities.addEntity(base + 2, EntityTypeIds.ZOMBIE, new Vec3(3.5, 64, 3.5), 0, 0, 0);
        entities.vehicles.applyVehiclePassengers(
                base, new int[] {entities.getSelf().getEntityId()});
        entities.getEntity(base + 2).mount(entities.getEntity(base + 1));
    }

    private static void respawn(JavaTickFixture f, String dimension) throws Exception {
        var spawn = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_nether"),
                dimension,
                GameMode.SURVIVAL);
        var packet = new ClientboundRespawn(spawn, (byte) 0);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        flush(f.player);
    }

    private static void flush(CultPlayer player) {
        for (int id = player.lastTransactionReceived.get() + 1; id <= player.lastTransactionSent.get(); id++) {
            player.lastTransactionReceived.set(id);
            player.latencyUtils.handleNettySyncTransaction(id);
        }
    }

    private static int sumOverMap(CompensatedEntities entities) {
        int total = 0;
        for (PacketEntity entity : entities.entityMap.values()) total += entity.passengers.size();
        return total;
    }

    /** The count equals the passengers of entityMap values; {@code expected} is that sum unless negative. */
    private static void assertInvariant(CompensatedEntities entities, int expected) throws Exception {
        int sum = sumOverMap(entities);
        if (expected >= 0) assertEquals(expected, sum);
        assertEquals(sum, trackedPassengers(entities));
    }

    private static void compare(long seed, ClientVersion version) throws Exception {
        try (var reference = new JavaTickFixture();
                var candidate = new JavaTickFixture()) {
            for (JavaTickFixture f : List.of(reference, candidate))
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            World old = new World(reference.player);
            World current = new World(candidate.player);
            Random random = new Random(seed);
            int positioned = 0;
            for (int step = 0; step < 400; step++) {
                long op = random.nextLong();
                old.apply(op);
                current.apply(op);
                ownTreeScan(reference.player.compensatedEntities);
                candidate.player.compensatedEntities.updatePassengerPositions();
                String expected = old.digest();
                assertEquals(expected, current.digest(), "seed=" + seed + " step=" + step);
                assertEquals(
                        sumOverMap(candidate.player.compensatedEntities),
                        trackedPassengers(candidate.player.compensatedEntities));
                if (current.anyTrackedPassengers()) positioned++;
            }
            assertTrue(positioned > 0, "seed " + seed + " never positioned a passenger");
        }
    }

    /**
     * The unconditional scan of the local player's own riding trees: the tree under the root of its riding chain and
     * the tree under the root of the vehicle the server last put it on, each while that root is tracked.
     */
    private static void ownTreeScan(CompensatedEntities entities) {
        PacketEntity selfRoot = trackedRoot(entities, entities.getSelf().getRiding());
        Integer serverVehicle = entities.vehicles.serverPlayerVehicle;
        PacketEntity serverRoot =
                serverVehicle == null ? null : trackedRoot(entities, entities.getEntity(serverVehicle));
        for (PacketEntity root : new PacketEntity[] {selfRoot, serverRoot == selfRoot ? null : serverRoot}) {
            if (root == null) continue;
            for (PacketEntity passenger : root.passengers) tick(entities, root, passenger);
        }
    }

    private static PacketEntity trackedRoot(CompensatedEntities entities, PacketEntity vehicle) {
        if (vehicle == null) return null;
        PacketEntity root = vehicle;
        // CompensatedEntities bounds a riding chain by the entity map's size, as a riding cycle has no root.
        for (int hops = 0; root.getRiding() != null; hops++) {
            if (hops > entities.entityMap.size()) return null;
            root = root.getRiding();
        }
        return entities.entityMap.get(root.getEntityId()) == root ? root : null;
    }

    private static void tick(CompensatedEntities entities, PacketEntity riding, PacketEntity passenger) {
        if (riding == null || passenger == null) return;
        entities.positionPassenger(riding, passenger);
        for (PacketEntity passengerPassenger : passenger.passengers) tick(entities, passenger, passengerPassenger);
    }

    private static int trackedPassengers(CompensatedEntities entities) throws Exception {
        Field field = CompensatedEntities.class.getDeclaredField("trackedPassengers");
        field.setAccessible(true);
        return field.getInt(entities);
    }

    /** One player's entities driven by a shared operation stream. */
    private static final class World {
        private final CultPlayer player;
        private final CompensatedEntities entities;
        private final List<PacketEntity> everBuilt = new ArrayList<>();
        private int nextId = 100;

        World(CultPlayer player) {
            this.player = player;
            this.entities = player.compensatedEntities;
        }

        void apply(long op) {
            Random random = new Random(op);
            int kind = random.nextInt(100);
            if (kind < 18 || everBuilt.size() < 3) {
                int id = nextId++;
                double x = random.nextInt(9) - 4 + 0.5;
                double z = random.nextInt(9) - 4 + 0.5;
                entities.addEntity(id, TYPES[random.nextInt(TYPES.length)], new Vec3(x, 64, z), 0, 0, 0);
                everBuilt.add(entities.getEntity(id));
            } else if (kind < 55) {
                PacketEntity passenger = pick(random, true);
                PacketEntity vehicle = pick(random, true);
                if (passenger != vehicle && !ancestorOrSelf(passenger, vehicle)) passenger.mount(vehicle);
            } else if (kind < 70) {
                pick(random, true).eject();
            } else if (kind < 80) {
                PacketEntity entity = pick(random, false);
                entities.removeEntity(entity.getEntityId());
            } else if (kind < 95) {
                PacketEntity entity = pick(random, false);
                double x = random.nextInt(81) / 10.0 - 4;
                double z = random.nextInt(81) / 10.0 - 4;
                double y = 64 + random.nextInt(30) / 10.0;
                entity.setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(player, x, y, z, entity));
            } else if (kind < 98) {
                PacketEntity vehicle = pick(random, false);
                PacketEntity passenger = pick(random, false);
                if (!ancestorOrSelf(passenger, vehicle) && !ancestorOrSelf(entities.getSelf(), vehicle))
                    entities.vehicles.applyVehiclePassengers(
                            vehicle.getEntityId(), new int[] {passenger.getEntityId(), player.entityID});
            } else {
                entities.clearClientLevel();
            }
        }

        private PacketEntity pick(Random random, boolean includeSelf) {
            int index = random.nextInt(everBuilt.size() + (includeSelf ? 1 : 0));
            return index == everBuilt.size() ? entities.getSelf() : everBuilt.get(index);
        }

        // Mounting onto a descendant would make a riding cycle, which the client cannot reach.
        private static boolean ancestorOrSelf(PacketEntity candidate, PacketEntity of) {
            for (PacketEntity e = of; e != null; e = e.getRiding()) if (e == candidate) return true;
            return false;
        }

        boolean anyTrackedPassengers() {
            for (PacketEntity entity : entities.entityMap.values()) if (!entity.passengers.isEmpty()) return true;
            return false;
        }

        String digest() {
            StringBuilder out = new StringBuilder();
            for (PacketEntity entity : entities.entityMap.values()) describe(out, entity);
            for (PacketEntity entity : everBuilt) describe(out, entity);
            describe(out, entities.getSelf());
            out.append(" client=").append(player.packetStateData.clientSidePosition);
            return out.toString();
        }

        private static void describe(StringBuilder out, PacketEntity entity) {
            out.append('\n')
                    .append(entity.getEntityId())
                    .append(" riding=")
                    .append(
                            entity.getRiding() == null
                                    ? "-"
                                    : entity.getRiding().getEntityId())
                    .append(" desync=")
                    .append(entity.desyncClientPos)
                    .append(" physical=")
                    .append(entity.clientPhysicalPosition)
                    .append(" box=")
                    .append(entity.getPossibleCollisionBoxes());
        }
    }
}
