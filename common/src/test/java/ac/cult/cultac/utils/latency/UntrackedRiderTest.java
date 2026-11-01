package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.combat.EntityHitboxes;
import ac.cult.cultac.checks.impl.combat.EntityReach;
import ac.cult.cultac.checks.impl.combat.PlayerHitboxes;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.ReachCheck;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UnknownCollisionProvider;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * Riders outside the local player's own riding trees are untracked: never positioned, never ticked, skipped by
 * checks and collision, and anchored at their current seat when they dismount so their normal tracking resumes from
 * the position the client leaves the vehicle from.
 */
class UntrackedRiderTest {
    private static final int SELF = 7;
    private static final int VEHICLE = 2000;
    private static final int MOUNTED = 2001;
    private static final int OWN_VEHICLE = 2100;
    private static final int CO_RIDER = 2101;
    private static final int BOAT_VEHICLE = 2200;
    private static final int RIDING_BOAT = 2201;

    enum Client {
        LEGACY(ClientVersion.V_1_21_11),
        STEPPED(ClientVersion.V_26_3);

        final ClientVersion version;

        Client(ClientVersion version) {
            this.version = version;
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void ridersOutsideTheOwnTreeAreUntrackedAndOwnTreeRidersAreNot(Client client) {
        CultPlayer player = world(client.version);
        try {
            CompensatedEntities entities = player.compensatedEntities;
            assertTrue(entities.isUntrackedRider(entity(player, MOUNTED)));
            assertFalse(entities.isUntrackedRider(entity(player, VEHICLE)), "a root is never a rider");
            mount(player, OWN_VEHICLE, CO_RIDER);
            assertTrue(entities.isUntrackedRider(entity(player, CO_RIDER)), "no own tree while the player walks");
            assertNull(entities.selfRidingRoot());

            mount(player, OWN_VEHICLE, CO_RIDER, SELF);
            assertSame(entity(player, OWN_VEHICLE), entities.selfRidingRoot());
            assertFalse(entities.isUntrackedRider(entity(player, CO_RIDER)));
            assertFalse(entities.isUntrackedRider(entities.playerEntity));
            assertTrue(entities.isUntrackedRider(entity(player, MOUNTED)));
        } finally {
            close(player);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void untrackedRidersAreNeitherPositionedNorTicked(Client client) {
        CultPlayer player = world(client.version);
        try {
            PacketEntity mounted = entity(player, MOUNTED);
            Vec3 before = mounted.clientPhysicalPosition;
            for (int tick = 1; tick <= 3; tick++) step(player, tick, VEHICLE);
            assertSame(before, mounted.clientPhysicalPosition, "an untracked rider was positioned");

            List<PacketEntity> ticked = new ArrayList<>();
            player.compensatedEntities.forEachTrackedEntity(ticked::add);
            player.compensatedEntities.tickClientEntities(ticked::add);
            assertFalse(ticked.contains(mounted), "an untracked rider was ticked");
            assertTrue(ticked.contains(entity(player, VEHICLE)));
        } finally {
            close(player);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void mountedTargetsAreExemptFromReach(Client client) {
        CultPlayer player = world(client.version);
        try {
            // Control: the same far attack on an unmounted target flags.
            attack(player, CO_RIDER);
            double flagged = reachViolations(player);
            assertTrue(flagged > 0, "the harness cannot flag reach");

            attack(player, MOUNTED);
            assertEquals(flagged, reachViolations(player), "flagged an attack on an untracked rider");
        } finally {
            close(player);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void untrackedRidersAreSkippedByEntityCollision(Client client) {
        CultPlayer player = world(client.version);
        try {
            PacketEntity boat = entity(player, RIDING_BOAT);
            assertTrue(player.compensatedEntities.isUntrackedRider(boat));
            Vec3 at = boat.clientPhysicalPosition;
            List<SimpleCollisionBox> unknown = unknownCollisions(player, at);
            SimpleCollisionBox vehicleBox = entity(player, BOAT_VEHICLE).getPossibleCollisionBoxes();
            assertEquals(1, unknown.size(), "only the boat's vehicle, which is a root, is an unknown collision");
            assertTrue(unknown.getFirst().isIntersected(vehicleBox));

            mount(player, BOAT_VEHICLE);
            assertFalse(player.compensatedEntities.isUntrackedRider(boat));
            assertEquals(2, unknownCollisions(player, at).size());
        } finally {
            close(player);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void dismountResumesTrackingFromTheCurrentSeat(Client client) {
        CultPlayer player = world(client.version);
        try {
            PacketEntity vehicle = entity(player, VEHICLE);
            PacketEntity mounted = entity(player, MOUNTED);
            Vec3 frozen = mounted.clientPhysicalPosition;
            for (int tick = 1; tick <= 4; tick++) step(player, tick, VEHICLE);
            Vec3 seat = BoundingBoxSize.getRidingOffsetFromVehicle(vehicle, mounted, player);
            assertNotEquals(frozen.x, seat.x, 1.0E-9, "the vehicle never moved away from the frozen position");

            mount(player, VEHICLE);
            assertNull(mounted.getRiding());
            assertFalse(player.compensatedEntities.isUntrackedRider(mounted));
            SimpleCollisionBox seatBox =
                    GetBoundingBox.getPacketEntityBoundingBox(player, seat.x, seat.y, seat.z, mounted);
            assertEquals(seat, mounted.clientPhysicalPosition);
            assertBoxEquals(seatBox, mounted.getPossibleCollisionBoxes());
            assertBoxEquals(seatBox, mounted.getPossibleMovementCollisionBoxes());
            assertNull(mounted.oldPacketLocation);

            // Its own movements take over through the normal interpolation path, starting from the seat.
            step(player, 5, MOUNTED);
            SimpleCollisionBox first = mounted.getPossibleCollisionBoxes();
            assertTrue(first.minX > frozen.x, "tracking resumed from the frozen position: " + first);
            for (int tick = 6; tick <= 12; tick++) step(player, tick, MOUNTED);
            SimpleCollisionBox last = mounted.getPossibleCollisionBoxes();
            assertTrue(last.minX > first.minX + 1.0, "the dismounted rider stopped following its moves: " + last);
        } finally {
            close(player);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void removingTheVehicleSettlesItsUntrackedRiders(Client client) {
        CultPlayer player = world(client.version);
        try {
            PacketEntity vehicle = entity(player, VEHICLE);
            PacketEntity mounted = entity(player, MOUNTED);
            for (int tick = 1; tick <= 3; tick++) step(player, tick, VEHICLE);
            Vec3 seat = BoundingBoxSize.getRidingOffsetFromVehicle(vehicle, mounted, player);
            player.compensatedEntities.removeEntity(VEHICLE);
            assertNull(mounted.getRiding());
            assertEquals(seat, mounted.clientPhysicalPosition);
        } finally {
            close(player);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void ownTreeCoRidersAreNotSettledWhenTheyDismount(Client client) {
        CultPlayer player = world(client.version);
        try {
            mount(player, OWN_VEHICLE, CO_RIDER, SELF);
            for (int tick = 1; tick <= 3; tick++) step(player, tick, OWN_VEHICLE);
            PacketEntity coRider = entity(player, CO_RIDER);
            var location = coRider.newPacketLocation;
            Vec3 desync = coRider.desyncClientPos;
            mount(player, OWN_VEHICLE, SELF);
            assertSame(location, coRider.newPacketLocation, "a tracked co-rider was re-anchored on dismount");
            assertSame(desync, coRider.desyncClientPos);
        } finally {
            close(player);
        }
    }

    private static void assertBoxEquals(SimpleCollisionBox expected, SimpleCollisionBox actual) {
        assertEquals(expected.minX, actual.minX, 0.0);
        assertEquals(expected.minY, actual.minY, 0.0);
        assertEquals(expected.minZ, actual.minZ, 0.0);
        assertEquals(expected.maxX, actual.maxX, 0.0);
        assertEquals(expected.maxY, actual.maxY, 0.0);
        assertEquals(expected.maxZ, actual.maxZ, 0.0);
    }

    private static CultPlayer world(ClientVersion version) {
        CultPlayer player = trackingPlayer(version);
        player.entityID = SELF;
        player.x = 0;
        player.y = 64;
        player.z = 0;
        // Players tick on every client, loaded chunk or not; the test world has no chunks.
        add(player, VEHICLE, EntityTypeIds.PLAYER, 12, 0);
        add(player, MOUNTED, EntityTypeIds.PLAYER, 12, 0);
        add(player, OWN_VEHICLE, EntityTypeIds.ZOMBIE, 10, 4);
        add(player, CO_RIDER, EntityTypeIds.ZOMBIE, 10, 4);
        add(player, BOAT_VEHICLE, EntityTypeIds.OAK_BOAT, -12, 0);
        add(player, RIDING_BOAT, EntityTypeIds.OAK_BOAT, -12, 0);
        mount(player, VEHICLE, MOUNTED);
        mount(player, BOAT_VEHICLE, RIDING_BOAT);
        return player;
    }

    private static final List<Class<? extends ReachCheck>> REACH = List.of(PlayerReach.class, EntityReach.class);
    private static final List<Class<? extends Check>> REACH_FLAGS =
            List.of(PlayerReach.class, EntityReach.class, PlayerHitboxes.class, EntityHitboxes.class);

    private static double reachViolations(CultPlayer player) {
        double violations = 0;
        for (Class<? extends Check> check : REACH_FLAGS) violations += player.checkManager.getCheck(check).violations;
        return violations;
    }

    private static void attack(CultPlayer player, int target) {
        var packet = new ServerboundInteract(target, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        var event = new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
        for (Class<? extends ReachCheck> check : REACH)
            player.checkManager.getCheck(check).onInteract(event, player, packet);
        for (Class<? extends ReachCheck> check : REACH)
            player.checkManager.getCheck(check).onPlayerTickEnd(null);
    }

    // The entity part of the unknown collisions around at, outside any vehicle.
    private static List<SimpleCollisionBox> unknownCollisions(CultPlayer player, Vec3 at) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        SimpleCollisionBox query = new SimpleCollisionBox(at.x - 4, at.y - 4, at.z - 4, at.x + 4, at.y + 4, at.z + 4);
        UnknownCollisionProvider.addMovementUnknownCollisions(
                player,
                Mockito.mock(SimulationContext.class),
                at,
                query,
                boxes,
                new SimpleCollisionBox(),
                new SimpleCollisionBox());
        return boxes;
    }

    private static void step(CultPlayer player, int tick, int mover) {
        var replication = player.packetEntityReplication;
        replication.tickStartTick();
        PacketSendEvent<?> event = move(player, mover, 4096, 0);
        event.getTasksAfterSend().forEach(Runnable::run);
        ((EmbeddedChannel) player.user.getChannel()).runPendingTasks();
        player.onEndOfTickEvent(tick);
        acknowledgeThrough(player, player.lastTransactionSent.get());
        replication.onPlayerTickEnd(null);
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
                new User.Profile(UUID.randomUUID(), ".Rider_Untracked"),
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
