package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.latency.ClientPlayerModes;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ClientEntityQueries#playerCollisionBoxes against the scan over every entityMap value, through random populations and
 * mutation sequences: the same boxes in the same order, the same failure, and the same PlayerInfo left cached on every
 * remote player.
 */
class PlayerCollisionBoxesParityTest {
    private static final double Y = JavaTickFixture.FLOOR_Y;
    private static final int STEPS = 300;

    private int boxes;
    private int emptyResults;
    private int profileFailures;
    private int boundsFailures;
    private int latches;

    @Test
    void matchesTheFullScanAfterEveryMutation() throws Exception {
        for (var version : new ClientVersion[] {ClientVersion.V_26_3, ClientVersion.V_1_21_11}) {
            for (int seed = 0; seed < 6; seed++) {
                try (var f = ClientEntityQueryCandidateTest.fixture(version)) {
                    ClientEntityQueryCandidateTest.populate(f, 30, seed);
                    run(f, new Random(seed * 7919L + version.ordinal()));
                }
            }
        }
        assertTrue(
                boxes > 0 && emptyResults > 0 && profileFailures > 0 && boundsFailures > 0 && latches > 0,
                boxes + " boxes, " + emptyResults + " empty, " + profileFailures + " profile failures, "
                        + boundsFailures + " bounds failures, " + latches + " latches");
    }

    private void run(JavaTickFixture f, Random random) {
        var queries = new ClientEntityQueries(f.player);
        List<UUID> profiles = new ArrayList<>();
        for (var entity : f.player.compensatedEntities.entityMap.values())
            if (entity instanceof PacketEntityPlayer remote && remote.profile != null) profiles.add(remote.profile);
        for (int step = 0; step < STEPS; step++) {
            mutate(f, random, profiles);
            for (int q = 0; q < 3; q++) {
                double x = random.nextDouble() * 6 - 3, z = random.nextDouble() * 6 - 2;
                double w = random.nextDouble() * 1.5, h = random.nextDouble() * 2;
                double y = Y + random.nextDouble() - .5;
                var query = q == 0 ? ClientEntityQueryCandidateTest.NEAR : new Box(x, y, z, x + w, y + h, z + w);
                compare(f.player, queries, query, "step " + step + " " + query);
            }
        }
    }

    private void compare(CultPlayer player, ClientEntityQueries queries, Box query, String where) {
        List<PacketEntityPlayer> remotes = new ArrayList<>();
        for (var entity : player.compensatedEntities.entityMap.values())
            if (entity instanceof PacketEntityPlayer remote) remotes.add(remote);
        ClientPlayerModes.Entry[] before = cached(remotes);
        Object expected = ClientEntityQueryCandidateTest.outcome(() -> fullScan(player, query));
        ClientPlayerModes.Entry[] afterScan = cached(remotes);
        for (int i = 0; i < remotes.size(); i++) remotes.get(i).cachedInfo = before[i];
        Object actual = ClientEntityQueryCandidateTest.outcome(() -> queries.playerCollisionBoxes(query));
        ClientPlayerModes.Entry[] afterQuery = cached(remotes);
        assertEquals(expected, actual, where);
        for (int i = 0; i < remotes.size(); i++) {
            assertSame(
                    afterScan[i],
                    afterQuery[i],
                    where + " cached info of " + remotes.get(i).getEntityId());
            if (afterScan[i] != before[i]) latches++;
        }
        if (expected instanceof List<?> list) {
            boxes += list.size();
            if (list.isEmpty()) emptyResults++;
        } else if (String.valueOf(expected).contains("profile UUID")) profileFailures++;
        else if (String.valueOf(expected).contains("Invalid box bounds")) boundsFailures++;
    }

    private static ClientPlayerModes.Entry[] cached(List<PacketEntityPlayer> remotes) {
        var out = new ClientPlayerModes.Entry[remotes.size()];
        for (int i = 0; i < out.length; i++) out[i] = remotes.get(i).cachedInfo;
        return out;
    }

    private static final int[] TYPES = {
        EntityTypeIds.OAK_BOAT,
        EntityTypeIds.SHULKER,
        EntityTypeIds.HAPPY_GHAST,
        EntityTypeIds.COW,
        EntityTypeIds.ARMOR_STAND,
        EntityTypeIds.ZOMBIE,
        EntityTypeIds.WIND_CHARGE,
        EntityTypeIds.ITEM,
        EntityTypes.defaults().byKey("minecraft:camel").id(),
        EntityTypes.defaults().byKey("minecraft:minecart").id(),
        EntityTypes.defaults().byKey("minecraft:arrow").id()
    };

    private static void mutate(JavaTickFixture f, Random random, List<UUID> profiles) {
        CompensatedEntities entities = f.player.compensatedEntities;
        List<PacketEntity> live = new ArrayList<>(entities.entityMap.values());
        int id = 3000 + random.nextInt(60);
        boolean near = random.nextInt(3) > 0;
        var at = near
                ? new Vec3(random.nextDouble() * 4 - 1.5, Y + random.nextDouble() * .5, random.nextDouble() * 4 - 1)
                : new Vec3(30 + random.nextDouble() * 30, Y, 30 + random.nextDouble() * 30);
        PacketEntity some = live.isEmpty() ? null : live.get(random.nextInt(live.size()));
        int op = random.nextInt(100);
        if (op < 22) {
            spawn(f, id, TYPES[random.nextInt(TYPES.length)], at, random.nextInt(3));
        } else if (op < 30) {
            var mode = random.nextInt(4) == 0 ? GameMode.SPECTATOR : GameMode.SURVIVAL;
            f.addRemotePlayer(id, at, mode);
            profiles.add(((PacketEntityPlayer) entities.getEntity(id)).profile);
        } else if (op < 32) {
            // A player spawn without a profile, which the action queries refuse.
            entities.addEntity(id, EntityTypeIds.PLAYER, at, 0, 0, 0);
        } else if (op < 46) {
            entities.removeEntity(id);
        } else if (op < 50) {
            if (some != null) spawn(f, some.getEntityId(), TYPES[random.nextInt(TYPES.length)], at, random.nextInt(3));
        } else if (some == null) {
            return;
        } else if (op < 56) {
            some.actionAlive = !some.actionAlive;
        } else if (op < 60) {
            if (some instanceof PacketEntityHappyGhast ghast) {
                ghast.staysStill = !ghast.staysStill;
                if (random.nextInt(3) == 0) ghast.isBaby = !ghast.isBaby;
            }
        } else if (op < 66) {
            if (some.getRiding() != null) some.eject();
            else if (live.size() > 1) some.mount(live.get(random.nextInt(live.size())));
            entities.updatePassengerPositions();
        } else if (op < 70) {
            if (entities.getSelf().getRiding() != null) entities.getSelf().eject();
            else entities.getSelf().mount(some);
            entities.updatePassengerPositions();
        } else if (op < 78) {
            if (some instanceof PacketEntityPlayer remote) {
                int what = random.nextInt(4);
                if (what == 0) remote.actionPresent = !remote.actionPresent;
                else if (what == 1) remote.cachedInfo = null;
                else if (remote.profile == null && !profiles.isEmpty())
                    remote.profile = profiles.get(random.nextInt(profiles.size()));
            }
        } else if (op < 86) {
            if (!profiles.isEmpty()) {
                UUID profile = profiles.get(random.nextInt(profiles.size()));
                if (random.nextBoolean()) entities.clientPlayerModes.remove(List.of(profile));
                else {
                    var mode = random.nextBoolean() ? GameMode.SPECTATOR : GameMode.CREATIVE;
                    var info = new ClientboundPlayerInfoUpdate(
                            EnumSet.of(
                                    ClientboundPlayerInfoUpdate.Action.ADD_PLAYER,
                                    ClientboundPlayerInfoUpdate.Action.UPDATE_GAME_MODE),
                            List.of(new ClientboundPlayerInfoUpdate.Entry(profile, mode, Map.of())));
                    entities.clientPlayerModes.update(info, Set.of(profile), entities.entityMap.values());
                }
            }
        } else if (op < 97) {
            some.onBundleFirstTransaction(true, true, random.nextDouble() - .5, 0, random.nextDouble() - .5, f.player);
            some.onMovement(f.player, true);
        } else if (op < 98) {
            if (some.possibleMovementCollisionBoxCandidateCount() == 1)
                some.setPositionRaw(new SimpleCollisionBox().set(1, Y, 1, 0.5, Y + 0.5, 0.5));
        } else {
            entities.clearEntityMap();
        }
    }

    // An item spawn replaces nothing and adds nothing.
    private static void spawn(JavaTickFixture f, int id, int type, Vec3 at, int moves) {
        if (type == EntityTypeIds.ITEM) f.player.compensatedEntities.addEntity(id, type, at, 0, 0, 0);
        else ClientEntityQueryCandidateTest.moving(f, id, type, at, moves);
    }

    /** The playerCollisionBoxes scan over every entityMap value. */
    private static List<Box> fullScan(CultPlayer player, Box query) {
        var result = new ArrayList<Box>();
        var search = query.inflate(1.0E-7);
        SimpleCollisionBox candidate = null;
        for (var entity : player.compensatedEntities.entityMap.values()) {
            if (!present(player, entity) || spectator(player, entity) || !collidable(entity)) continue;
            if (candidate == null) candidate = new SimpleCollisionBox();
            for (int i = 0, count = entity.possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
                var bounds = entity.toBoundingBox(player, entity.possibleMovementCollisionBoxCandidate(i, candidate));
                Box.validateBounds(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
                var box = new Box(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
                if (!search.intersects(box)) continue;
                if (!result.contains(box)) result.add(box);
            }
        }
        return List.copyOf(result);
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
        return entity instanceof PacketEntityPlayer remote
                && remote.actionSpectator(player.compensatedEntities.clientPlayerModes);
    }
}
