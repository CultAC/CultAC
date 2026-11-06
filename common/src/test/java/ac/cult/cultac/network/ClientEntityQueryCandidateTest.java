package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.SimWorldView;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityArmorStand;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHanging;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityInteraction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * ClientEntityQueries reads each entity's movement candidates exactly as getPossibleMovementCollisionBoxCandidates
 * lists them, and a candidate that cannot reach a query allocates nothing.
 */
class ClientEntityQueryCandidateTest {
    static final double Y = JavaTickFixture.FLOOR_Y;
    static final Box NEAR = new Box(0.2, Y, 0.7, 0.8, Y + 1.8, 1.3);
    /** Clear of the local player and of every entity the allocation and malformed-bound cases add. */
    private static final Box CLEAR = new Box(3.0, Y, 3.0, 3.5, Y + 1.0, 3.5);

    private static final int ROUNDS = 4_000;
    private static volatile Object allocationSink;

    @Test
    void emptyEntityQueriesAllocateNothing() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_11)) {
            var queries = new ClientEntityQueries(f.player);
            for (int i = 0; i < 20; i++) moving(f, 7000 + i, EntityTypeIds.COW, new Vec3(40 + i, Y, 40), 1);
            for (int i = 0; i < ROUNDS * 5; i++) allocationSink = queries.hasEntityIn(CLEAR);
            f.player.compensatedEntities.entityMap.clear();
            assertFalse(queries.hasEntityIn(CLEAR));
            double bytes = bytesPerCall(() -> allocationSink = queries.hasEntityIn(CLEAR));
            assertTrue(bytes < 1.0, "empty action query bytes/call " + bytes);
        }
    }

    @Test
    void emptyEntityQueryPreservesLocalPlayerAndValidation() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_11)) {
            var queries = new ClientEntityQueries(f.player);
            assertTrue(queries.hasEntityIn(NEAR));
            assertThrows(NullPointerException.class, () -> queries.hasEntityIn(null));
            f.player.gamemode = GameMode.SPECTATOR;
            assertFalse(queries.hasEntityIn(NEAR));
            assertThrows(NullPointerException.class, () -> queries.hasEntityIn(null));
            f.player.boundingBox.set(2, Y, 0, 1, Y + 1, 1);
            assertFalse(queries.hasEntityIn(CLEAR));
            f.player.gamemode = GameMode.SURVIVAL;
            assertThrows(IllegalArgumentException.class, () -> queries.hasEntityIn(CLEAR));
            assertThrows(IllegalArgumentException.class, () -> queries.hasEntityIn(null));
        }
    }

    @Test
    void farCollisionCandidatesAllocateNothing() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_11)) {
            var queries = new ClientEntityQueries(f.player);
            double empty = bytesPerCall(() -> queries.playerCollisionBoxes(NEAR));
            int candidates = 0;
            for (int i = 0; i < 200; i++) {
                var boat = moving(f, 5000 + i, EntityTypeIds.OAK_BOAT, new Vec3(40 + i % 20, Y, 40 + i / 20), 2);
                candidates += boat.possibleMovementCollisionBoxCandidateCount();
            }
            assertTrue(candidates >= 400, "every far boat holds old and new interpolation candidates");
            assertEquals(List.of(), queries.playerCollisionBoxes(NEAR));
            double far = bytesPerCall(() -> queries.playerCollisionBoxes(NEAR));
            double perCandidate = (far - empty) / candidates;
            assertTrue(perCandidate < 1.0, "bytes per far candidate " + perCandidate + " (empty " + empty + ")");
        }
    }

    @Test
    void actionCandidatesAllocateOnlyTheirQueriedBox() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_11)) {
            var queries = new ClientEntityQueries(f.player);
            double empty = bytesPerCall(() -> queries.hasEntityIn(CLEAR));
            int candidates = 0;
            for (int i = 0; i < 200; i++) {
                var cow = moving(f, 5000 + i, EntityTypeIds.COW, new Vec3(40 + i % 20, Y, 40 + i / 20), 2);
                candidates += cow.possibleMovementCollisionBoxCandidateCount();
            }
            assertFalse(queries.hasEntityIn(CLEAR));
            double far = bytesPerCall(() -> queries.hasEntityIn(CLEAR));
            double perCandidate = (far - empty) / candidates;
            // One retained Box (header + six doubles) per candidate; no list or scratch box per candidate.
            assertTrue(perCandidate <= 72.0, "bytes per action candidate " + perCandidate + " (empty " + empty + ")");
        }
    }

    @Test
    void indexedCandidatesMatchTheListedCandidates() throws Exception {
        for (var version : new ClientVersion[] {ClientVersion.V_26_3, ClientVersion.V_1_21_11}) {
            for (int seed = 0; seed < 8; seed++) {
                try (var f = fixture(version)) {
                    populate(f, 40, seed);
                    var scratch = new SimpleCollisionBox(-9, -9, -9, 9, 9, 9);
                    for (var entity : f.player.compensatedEntities.entityMap.values()) {
                        var listed = entity.getPossibleMovementCollisionBoxCandidates();
                        assertEquals(listed.size(), entity.possibleMovementCollisionBoxCandidateCount());
                        for (int i = 0; i < listed.size(); i++)
                            assertEquals(
                                    bits(listed.get(i)),
                                    bits(entity.possibleMovementCollisionBoxCandidate(i, scratch)));
                    }
                }
            }
        }
    }

    @Test
    void queriesMatchTheListedCandidateReference() throws Exception {
        int boxes = 0, undecided = 0, hits = 0;
        for (var version : new ClientVersion[] {ClientVersion.V_26_3, ClientVersion.V_1_21_11}) {
            for (int count : new int[] {0, 20, 200}) {
                for (int seed = 0; seed < 6; seed++) {
                    try (var f = fixture(version)) {
                        populate(f, count, seed);
                        var queries = new ClientEntityQueries(f.player);
                        var random = new Random(seed * 31L + count);
                        for (int q = 0; q < 40; q++) {
                            double x = random.nextDouble() * 6 - 3, z = random.nextDouble() * 6 - 2;
                            double w = random.nextDouble() * 1.5, h = random.nextDouble() * 2;
                            double y = Y + random.nextDouble() - .5;
                            var query = q == 0 ? NEAR : new Box(x, y, z, x + w, y + h, z + w);
                            var expected = outcome(() -> reference(f.player, query));
                            assertEquals(
                                    expected, outcome(() -> queries.playerCollisionBoxes(query)), query.toString());
                            var any = outcome(() -> referenceAny(f.player, query));
                            assertEquals(any, outcome(() -> queries.hasEntityIn(query)), query.toString());
                            if (expected instanceof List<?> list) boxes += list.size();
                            if (any instanceof String s && s.contains("UndecidedQueryException")) undecided++;
                            if (Boolean.TRUE.equals(any)) hits++;
                        }
                    }
                }
            }
        }
        assertTrue(boxes > 0 && undecided > 0 && hits > 0, boxes + " boxes, " + undecided + " undecided, " + hits);
    }

    @Test
    void aFarMalformedCollisionCandidateStillFails() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_11)) {
            moving(f, 600, EntityTypeIds.OAK_BOAT, new Vec3(0.5, Y, 1.0), 1);
            var boat = moving(f, 601, EntityTypeIds.OAK_BOAT, new Vec3(60, Y, 60), 0);
            boat.setPositionRaw(new SimpleCollisionBox().set(61, Y, 61, 60, Y + 0.5, 60));
            var queries = new ClientEntityQueries(f.player);
            var expected = outcome(() -> reference(f.player, NEAR));
            assertTrue(expected instanceof String s && s.contains("Invalid box bounds"), String.valueOf(expected));
            assertEquals(expected, outcome(() -> queries.playerCollisionBoxes(NEAR)));
            var any = outcome(() -> referenceAny(f.player, CLEAR));
            assertTrue(any instanceof String s && s.contains("Invalid box bounds"), String.valueOf(any));
            assertEquals(any, outcome(() -> queries.hasEntityIn(CLEAR)));
        }
    }

    @Test
    void returnedBoxesAreSnapshots() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_11)) {
            var boat = moving(f, 600, EntityTypeIds.OAK_BOAT, new Vec3(0.5, Y, 1.0), 2);
            moving(f, 601, EntityTypeIds.SHULKER, new Vec3(0.9, Y, 1.2), 1);
            var queries = new ClientEntityQueries(f.player);
            var first = queries.playerCollisionBoxes(NEAR);
            var copy = List.copyOf(first);
            assertTrue(first.size() >= 3, first.toString());
            assertEquals(new HashSet<>(first).size(), first.size(), "no duplicate boxes");
            queries.playerCollisionBoxes(new Box(0.6, Y, 1.1, 1.4, Y + 1, 1.9));
            boat.onFirstTransaction(true, true, 0.4, 0, 0.3, f.player);
            boat.onMovement(f.player, true);
            queries.playerCollisionBoxes(NEAR);
            assertEquals(copy, first);
            assertThrows(UnsupportedOperationException.class, () -> first.add(NEAR));
        }
    }

    static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        var start = new Vec3(0.5, Y, 0.5);
        f.place(start, Vec3.ZERO);
        return f;
    }

    static Vec3 position(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    /** Adds an entity and applies {@code moves} relative moves whose old locations stay possible. */
    static PacketEntity moving(JavaTickFixture f, int id, int type, Vec3 at, int moves) {
        var entities = f.player.compensatedEntities;
        entities.addEntity(id, type, at, 0, 0, 0);
        var entity = entities.getEntity(id);
        for (int i = 0; i < moves; i++) {
            entity.onFirstTransaction(true, true, 0.15 * (i + 1), 0.0, -0.1 * (i + 1), f.player);
            entity.onMovement(f.player, true);
        }
        return entity;
    }

    private static int typeId(String key) {
        return EntityTypes.defaults().byKey(key).id();
    }

    /** Mixed entities around the player: interpolations, poses, scales, riders, spectators and absent players. */
    static void populate(JavaTickFixture f, int count, long seed) {
        var random = new Random(seed);
        var entities = f.player.compensatedEntities;
        int[] types = {
            EntityTypeIds.OAK_BOAT,
            EntityTypeIds.SHULKER,
            EntityTypeIds.HAPPY_GHAST,
            EntityTypeIds.COW,
            EntityTypeIds.ARMOR_STAND,
            typeId("minecraft:interaction"),
            EntityTypeIds.PLAYER,
            typeId("minecraft:villager"),
            EntityTypeIds.WIND_CHARGE,
            EntityTypeIds.SLIME,
            EntityTypeIds.ZOMBIE,
            typeId("minecraft:minecart"),
            typeId("minecraft:item_frame")
        };
        PacketEntity previous = null;
        for (int i = 0; i < count; i++) {
            int id = 3000 + i, type = types[i % types.length];
            boolean near = random.nextInt(3) > 0;
            var at = near
                    ? new Vec3(random.nextDouble() * 4 - 1.5, Y + random.nextDouble() * .5, random.nextDouble() * 4 - 1)
                    : new Vec3(30 + random.nextDouble() * 30, Y, 30 + random.nextDouble() * 30);
            PacketEntity entity;
            if (type == EntityTypeIds.PLAYER) {
                f.addRemotePlayer(id, at, random.nextInt(4) == 0 ? GameMode.SPECTATOR : GameMode.SURVIVAL);
                entity = entities.getEntity(id);
                ((PacketEntityPlayer) entity).actionPresent = random.nextInt(5) > 0;
                int moves = random.nextInt(3);
                for (int m = 0; m < moves; m++) {
                    entity.onFirstTransaction(
                            true, true, random.nextDouble() - .5, 0, random.nextDouble() - .5, f.player);
                    entity.onMovement(f.player, true);
                }
            } else {
                entity = moving(f, id, type, at, random.nextInt(4));
            }
            if (random.nextInt(4) == 0) entity.onSecondTransaction();
            if (random.nextBoolean()) entity.onMovement(f.player, true);
            entity.actionPose = random.nextInt(4) == 0 ? EntityPose.SLEEPING : EntityPose.STANDING;
            if (random.nextInt(5) == 0) entity.scale = random.nextFloat() * 2;
            entity.actionAlive = random.nextInt(5) > 0;
            if (entity instanceof PacketEntityHappyGhast ghast) {
                ghast.staysStill = random.nextBoolean();
                ghast.isBaby = random.nextInt(4) == 0;
            }
            if (entity instanceof PacketEntityInteraction interaction) {
                interaction.actionWidth = random.nextInt(4) == 0 ? Float.NaN : random.nextFloat() * 3 - 1;
                interaction.actionHeight = random.nextFloat() * 3 - 1;
            }
            if (previous != null && random.nextInt(8) == 0) entity.mount(previous);
            previous = entity;
        }
        if (count > 0 && seed % 2 == 0) {
            var vehicle = entities.getEntity(3000);
            entities.getSelf().mount(vehicle);
            var seated = entities.getEntity(3000 + 1);
            if (seated != null && seated.getRiding() == null) seated.mount(vehicle);
            if (seated != null)
                seated.setPassengerPosition(
                        f.player,
                        vehicle.getPossibleMovementCollisionBoxCandidates().getFirst());
            entities.updatePassengerPositions();
        }
    }

    /** ClientEntityQueries#playerCollisionBoxes over the listed candidates. */
    private static List<Box> reference(CultPlayer player, Box query) {
        var result = new ArrayList<Box>();
        var search = query.inflate(1.0E-7);
        for (var entity : player.compensatedEntities.entityMap.values()) {
            if (!present(player, entity) || spectator(player, entity) || !collidable(entity)) continue;
            for (var candidate : entity.getPossibleMovementCollisionBoxCandidates()) {
                var bounds = entity.toBoundingBox(player, candidate);
                var box = new Box(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
                if (search.intersects(box) && !result.contains(box)) result.add(box);
            }
        }
        return List.copyOf(result);
    }

    /** ClientEntityQueries#hasEntityIn over the listed candidates. */
    private static boolean referenceAny(CultPlayer player, Box query) {
        var local = player.boundingBox;
        if (player.gamemode != GameMode.SPECTATOR
                && query.intersects(new Box(local.minX, local.minY, local.minZ, local.maxX, local.maxY, local.maxZ)))
            return true;
        boolean unresolved = false;
        for (var entity : player.compensatedEntities.entityMap.values()) {
            if (!present(player, entity) || spectator(player, entity)) continue;
            var result = referenceTest(player, entity, query::intersects);
            if (Boolean.TRUE.equals(result)) return true;
            unresolved |= result == null;
        }
        if (unresolved) throw new SimWorldView.UndecidedQueryException("entity candidates disagree");
        return false;
    }

    private static Boolean referenceTest(CultPlayer player, PacketEntity entity, Predicate<Box> query) {
        if (entity instanceof PacketEntityHanging hanging) return query.test(hanging.actionBounds());
        Boolean result = null;
        for (var position : entity.getPossibleMovementCollisionBoxCandidates()) {
            Box box;
            if (entity.isLivingEntity() && entity.actionPose == EntityPose.SLEEPING) {
                double x = position.minX + .5 * (position.maxX - position.minX),
                        z = position.minZ + .5 * (position.maxZ - position.minZ);
                double radius = .2F / 2.0F;
                box = new Box(x - radius, position.minY, z - radius, x + radius, position.minY + .2F, z + radius);
            } else if (entity instanceof PacketEntityPlayer remote) box = remote.actionBounds(position);
            else if (entity instanceof PacketEntityArmorStand stand) box = stand.actionBounds(position);
            else if (entity instanceof PacketEntityInteraction interaction) box = interaction.actionBounds(position);
            else {
                var bounds = entity.toBoundingBox(player, position);
                box = new Box(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
            }
            boolean matched = box != null && query.test(box);
            if (result != null && result != matched) return null;
            result = matched;
        }
        return result;
    }

    private static boolean collidable(PacketEntity entity) {
        if (entity.isBoat()) return true;
        if (entity.type == EntityTypeIds.SHULKER) return entity.actionAlive;
        return entity instanceof PacketEntityHappyGhast ghast && !ghast.isBaby && ghast.actionAlive && ghast.staysStill;
    }

    private static boolean present(CultPlayer player, PacketEntity entity) {
        return (!(entity instanceof PacketEntityPlayer remote) || remote.actionPresent)
                && !player.compensatedEntities.isUntrackedRider(entity);
    }

    private static boolean spectator(CultPlayer player, PacketEntity entity) {
        CompensatedEntities entities = player.compensatedEntities;
        return entity instanceof PacketEntityPlayer remote && remote.actionSpectator(entities.clientPlayerModes);
    }

    /** The value, or the exception's class and message. */
    static Object outcome(Supplier<?> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static String bits(SimpleCollisionBox box) {
        return Double.doubleToRawLongBits(box.minX) + "," + Double.doubleToRawLongBits(box.minY) + ","
                + Double.doubleToRawLongBits(box.minZ) + "," + Double.doubleToRawLongBits(box.maxX) + ","
                + Double.doubleToRawLongBits(box.maxY) + "," + Double.doubleToRawLongBits(box.maxZ);
    }

    private static double bytesPerCall(Runnable call) {
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int i = 0; i < ROUNDS * 5; i++) call.run();
        double best = Double.MAX_VALUE;
        for (int sample = 0; sample < 5; sample++) {
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < ROUNDS; i++) call.run();
            best = Math.min(best, (threads.getCurrentThreadAllocatedBytes() - before) / (double) ROUNDS);
        }
        return best;
    }
}
