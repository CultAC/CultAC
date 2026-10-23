package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveMinecart;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A relative move decodes against the client's VecDeltaCodec base, which only a spawn, a relative move and a position
 * sync set (ClientPacketListener#handleMoveEntity, #handleEntityPositionSync, Entity#recreateFromPacket); a teleport,
 * riding and the client's own vehicle physics leave it.
 */
class EntityCodecBaseTest {
    private static final int ENTITY = 50;
    private static final int VEHICLE = 51;
    private static final double OFF_GRID_X = 2.3;
    private static final double OFF_GRID_Z = 2.7;
    // VecDeltaCodec: round(2.3 * 4096) = 9421, plus one block of 4096, over 4096.
    private static final double ONE_BLOCK_FROM_OFF_GRID_X = 13517 / 4096.0;
    // Boat#controlBoat adds 0.04F per tick of forward input; Boat#floatBoat keeps stone's 0.6F of it on land.
    private static final double PADDLE = 0.04F;
    private static final double LAND_FRICTION = 0.6F;

    enum Version {
        V_1_21_2(ClientVersion.V_1_21_2),
        V_1_21_4(ClientVersion.V_1_21_4),
        V_1_21_5(ClientVersion.V_1_21_5),
        V_1_21_6(ClientVersion.V_1_21_6),
        V_1_21_7(ClientVersion.V_1_21_7),
        V_1_21_9(ClientVersion.V_1_21_9),
        V_1_21_11(ClientVersion.V_1_21_11),
        V_26_1(ClientVersion.V_26_1),
        V_26_2(ClientVersion.V_26_2),
        V_26_3(ClientVersion.V_26_3);

        final ClientVersion version;

        Version(ClientVersion version) {
            this.version = version;
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aRelativeMoveDecodesAgainstTheSpawnAndKeepsItsZeroAxes(Version version) throws Exception {
        try (var f = fixture(version)) {
            spawn(f, "minecraft:zombie", new Vec3d(OFF_GRID_X, FLOOR_Y, OFF_GRID_Z));
            move(f, ENTITY, 4096, 0, 0);
            assertAt(f, ONE_BLOCK_FROM_OFF_GRID_X, FLOOR_Y, OFF_GRID_Z);
            move(f, ENTITY, 0, 0, -4096);
            assertAt(f, ONE_BLOCK_FROM_OFF_GRID_X, FLOOR_Y, (Math.round(OFF_GRID_Z * 4096) - 4096) / 4096.0);
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aPositionSyncSetsTheBase(Version version) throws Exception {
        try (var f = fixture(version)) {
            spawn(f, "minecraft:zombie", new Vec3d(0.5, FLOOR_Y, 0.5));
            send(
                    f,
                    ClientboundPackets.ENTITY_POSITION_SYNC,
                    new ClientboundEntityPositionSync(
                            ENTITY, new PositionPath.Linear(new Vec3d(OFF_GRID_X, FLOOR_Y, OFF_GRID_Z)), 0, 0, true),
                    (r, e) -> r.onEntityPositionSync(e, f.player, e.getPacket()));
            move(f, ENTITY, 4096, 0, 0);
            assertAt(f, ONE_BLOCK_FROM_OFF_GRID_X, FLOOR_Y, OFF_GRID_Z);
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aTeleportLeavesTheBase(Version version) throws Exception {
        try (var f = fixture(version)) {
            spawn(f, "minecraft:zombie", new Vec3d(OFF_GRID_X, FLOOR_Y, OFF_GRID_Z));
            // Entity#teleport sends the target; ServerEntity keeps its base and encodes the next move against it.
            send(
                    f,
                    ClientboundPackets.TELEPORT_ENTITY,
                    new ClientboundTeleportEntity(
                            ENTITY, new Vec3d(4.0, FLOOR_Y, 1.0), Vec3d.ZERO, 0, 0, Set.of(), true),
                    (r, e) -> r.onTeleportEntity(e, f.player, e.getPacket()));
            move(f, ENTITY, 4096, 0, 0);
            assertAt(f, ONE_BLOCK_FROM_OFF_GRID_X, FLOOR_Y, OFF_GRID_Z);
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void theRiddenVehiclesMovesAdvanceTheBase(Version version) throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.5);
        try (var f = fixture(version, seat)) {
            spawn(f, "minecraft:oak_boat", new Vec3d(seat.x, seat.y, seat.z));
            send(
                    f,
                    ClientboundPackets.SET_PASSENGERS,
                    new ClientboundSetPassengers(ENTITY, List.of(f.player.entityID)),
                    (r, e) -> r.onSetPassengers(e, f.player, e.getPacket()));
            assertSame(f.player.compensatedEntities.getEntity(ENTITY), f.player.compensatedEntities.getSelf().riding);
            // The client drives its boat and ignores the server's moves for it, but still decodes them.
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            // Boat#tick runs before LocalPlayer#rideTick hands it this tick's keys.
            f.rideTick(seat, true);
            drive(f, PADDLE);
            move(f, ENTITY, 0, 0, 1024);
            drive(f, PADDLE * LAND_FRICTION + PADDLE);
            move(f, ENTITY, 0, 0, 1024);
            send(
                    f,
                    ClientboundPackets.SET_PASSENGERS,
                    new ClientboundSetPassengers(ENTITY, List.of()),
                    (r, e) -> r.onSetPassengers(e, f.player, e.getPacket()));
            move(f, ENTITY, 0, 0, 1024);
            assertAt(f, seat.x, seat.y, seat.z + 0.75);
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aRotationAfterATeleportKeepsTheTeleportedPosition(Version version) throws Exception {
        try (var f = fixture(version)) {
            Vec3d spawnAt = new Vec3d(20.5, FLOOR_Y, 0.5);
            spawn(f, "minecraft:player", spawnAt);
            FairReach fairReach = f.player.checkManager.getListener(FairReach.class);
            var spawn = new ClientboundAddEntity(ENTITY, "minecraft:player", spawnAt, 0, 0, 0);
            fairReach.onAddEntity(
                    new PacketSendEvent<>(
                            f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, spawn, false),
                    f.player,
                    spawn);
            send(
                    f,
                    ClientboundPackets.TELEPORT_ENTITY,
                    new ClientboundTeleportEntity(
                            ENTITY, new Vec3d(0.5, FLOOR_Y, 0.5), Vec3d.ZERO, 0, 0, Set.of(), true),
                    (r, e) -> r.onTeleportEntity(e, f.player, e.getPacket()));
            f.player.sendTransaction();
            f.pongThrough(f.player.lastTransactionSent.get());
            Object target = fairReachEntity(fairReach);
            for (int i = 0; i < 3; i++) tick(target);
            // ClientPacketListener#handleMoveEntity: a rotation-only move keeps the position, not the codec base.
            send(
                    f,
                    ClientboundPackets.MOVE_ENTITY,
                    new ClientboundMoveEntity(
                            ENTITY, new EntityDelta.Linear((short) 0, (short) 0, (short) 0), 90, 0, true, false, true),
                    (r, e) -> r.onMoveEntity(e, f.player, e.getPacket()));
            f.player.sendTransaction();
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(
                    0.5, f.player.compensatedEntities.getTrackedEntity(ENTITY).getX(), "tracked x");
            assertEquals(0.5, ((Vec3) field(target, "targetPosition")).x, 1e-12, "fair reach target x");
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aChangedRotationMidInterpolationRetimesItLikeTheClient(Version version) throws Exception {
        assertRotationMidInterpolation(version, 90, 30, true);
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void anUnchangedRotationMidInterpolationRetimesItLikeTheClient(Version version) throws Exception {
        assertRotationMidInterpolation(version, 0, 0, false);
    }

    /**
     * ClientPacketListener#handleMoveEntity, rotation only: 1.21.2-1.21.4 LivingEntity#lerpTo(lerpTarget, rot, 3);
     * 1.21.5-1.21.8 moveOrInterpolateTo(position(), rot); 1.21.9-26.2 moveOrInterpolateTo(rot), which
     * InterpolationHandler#interpolateTo ignores for the active target; 26.3 SteppedInterpolationHandler appends a
     * stationary step.
     */
    private static void assertRotationMidInterpolation(Version version, float yaw, float pitch, boolean changed)
            throws Exception {
        try (var f = fixture(version, new Vec3(0.5, FLOOR_Y, 0.5))) {
            Vec3d spawnAt = new Vec3d(0.5, FLOOR_Y, 1.5);
            spawn(f, "minecraft:player", spawnAt);
            FairReach fairReach = f.player.checkManager.getListener(FairReach.class);
            var spawn = new ClientboundAddEntity(ENTITY, "minecraft:player", spawnAt, 0, 0, 0);
            fairReach.onAddEntity(
                    new PacketSendEvent<>(
                            f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, spawn, false),
                    f.player,
                    spawn);
            Object target = fairReachEntity(fairReach);
            move(f, ENTITY, 22528, 0, 0);
            f.player.sendTransaction();
            f.pongThrough(f.player.lastTransactionSent.get());
            tick(target);
            double shown = 0.5 + 5.5 / 3;
            assertEquals(shown, ((Vec3) field(target, "currentPosition")).x, 1e-12);

            send(
                    f,
                    ClientboundPackets.MOVE_ENTITY,
                    new ClientboundMoveEntity(
                            ENTITY,
                            new EntityDelta.Linear((short) 0, (short) 0, (short) 0),
                            yaw,
                            pitch,
                            false,
                            false,
                            true),
                    (r, e) -> r.onMoveEntity(e, f.player, e.getPacket()));
            f.player.sendTransaction();
            f.pongThrough(f.player.lastTransactionSent.get());
            tick(target);

            double restarted = shown + (6 - shown) / 3;
            double kept = shown + (6 - shown) / 2;
            double expected = switch (version) {
                case V_1_21_2, V_1_21_4 -> restarted;
                case V_1_21_5, V_1_21_6, V_1_21_7 -> shown;
                case V_1_21_9, V_1_21_11, V_26_1, V_26_2 -> changed ? restarted : kept;
                case V_26_3 -> kept;
            };
            assertEquals(expected, ((Vec3) field(target, "currentPosition")).x, 1e-12, "shown x");
            if (version == Version.V_26_3) return;

            var attack =
                    new ServerboundInteract(ENTITY, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            var event =
                    new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, attack);
            fairReach.onInteract(event, f.player, attack);
            assertEquals(expected == kept, event.isCancelled(), "attack cancelled at x " + expected);
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aRotationDuringATeleportLerpRetimesItLikeTheClient(Version version) throws Exception {
        for (boolean delimiter : new boolean[] {false, true}) {
            for (int before = 0; before <= 2; before++) {
                for (boolean changed : new boolean[] {false, true}) {
                    try (var f = fixture(version, new Vec3(0.5, FLOOR_Y, 0.5))) {
                        JavaTickFixture.setField(
                                PacketEntityReplication.class,
                                f.player.packetEntityReplication,
                                "useReachBundleDelimiter",
                                delimiter);
                        FairReach fairReach = spawnPlayer(f, new Vec3d(0.5, FLOOR_Y, 1.5));
                        Object target = fairReachEntity(fairReach);
                        teleport(f, new Vec3d(6, FLOOR_Y, 1.5));
                        for (int i = 0; i < before; i++) tick(target);
                        double shown = 0.5 + 5.5 * before / 3.0;
                        assertEquals(shown, ((Vec3) field(target, "currentPosition")).x, 1e-12);

                        rotate(f, changed ? 90 : 0, changed ? 30 : 0);
                        TrackerData tracked = f.player.compensatedEntities.getTrackedEntity(ENTITY);
                        assertEquals(6, tracked.getX(), 1e-12, "tracked x");
                        assertEquals(0.5, tracked.getCodecBaseX(), 1e-12, "codec base x");
                        boolean holds = switch (version) {
                            case V_1_21_5, V_1_21_6, V_1_21_7 -> true;
                            default -> false;
                        };
                        boolean restarts = switch (version) {
                            case V_1_21_2, V_1_21_4 -> true;
                            case V_1_21_9, V_1_21_11, V_26_1, V_26_2 -> changed;
                            default -> false;
                        };
                        assertEquals(holds ? shown : 6, ((Vec3) field(target, "targetPosition")).x, 1e-12);
                        tick(target);
                        double expected = holds ? shown : shown + (6 - shown) / (restarts ? 3 : 3 - before);
                        assertEquals(expected, ((Vec3) field(target, "currentPosition")).x, 1e-12, "shown x");
                        if (version != Version.V_26_3) {
                            assertEquals(expected > 3.8, attackCancelled(f, fairReach), "attack at x " + expected);
                        }
                    }
                }
            }
        }
    }

    // ClientPacketListener#setValuesFromPositionPacket: a teleport past 64 blocks sets the position outright.
    @ParameterizedTest
    @EnumSource(Version.class)
    void aRotationAfterASnappingTeleportHoldsTheSnappedPosition(Version version) throws Exception {
        try (var f = fixture(version, new Vec3(0.5, FLOOR_Y, 0.5))) {
            FairReach fairReach = spawnPlayer(f, new Vec3d(100.5, FLOOR_Y, 1.5));
            Object target = fairReachEntity(fairReach);
            teleport(f, new Vec3d(0.5, FLOOR_Y, 1.5));
            tick(target);
            rotate(f, 90, 30);
            for (int i = 0; i < 4; i++) tick(target);
            assertEquals(0.5, ((Vec3) field(target, "currentPosition")).x, 1e-12, "shown x");
            assertFalse(attackCancelled(f, fairReach), "attack on the snapped player");
        }
    }

    // ClientPacketListener#handleEntityPositionSync: a sync past 64 blocks from the shown position snaps there.
    @ParameterizedTest
    @EnumSource(Version.class)
    void rotationsAfterASnappingPositionSyncHoldTheSnappedPosition(Version version) throws Exception {
        try (var f = fixture(version, new Vec3(0.5, FLOOR_Y, 0.5))) {
            FairReach fairReach = spawnPlayer(f, new Vec3d(100.5, FLOOR_Y, 1.5));
            Object target = fairReachEntity(fairReach);
            positionSync(f, new Vec3d(0.5, FLOOR_Y, 1.5));
            tick(target);
            rotate(f, 90, 30);
            for (int i = 0; i < 20; i++) {
                tick(target);
                rotate(f, 90 + i, 30);
            }
            assertEquals(0.5, ((Vec3) field(target, "currentPosition")).x, 1e-12, "shown x");
            assertFalse(attackCancelled(f, fairReach), "attack on the snapped player");
        }
    }

    // ServerEntity#sendChanges follows a long teleport with a position sync from its stale codec base.
    @ParameterizedTest
    @EnumSource(Version.class)
    void aRotationAfterASnappingTeleportAndItsSyncHoldsTheSnappedPosition(Version version) throws Exception {
        try (var f = fixture(version, new Vec3(0.5, FLOOR_Y, 0.5))) {
            FairReach fairReach = spawnPlayer(f, new Vec3d(100.5, FLOOR_Y, 1.5));
            Object target = fairReachEntity(fairReach);
            teleport(f, new Vec3d(0.5, FLOOR_Y, 1.5));
            tick(target);
            tick(target);
            positionSync(f, new Vec3d(0.5, FLOOR_Y, 1.5));
            tick(target);
            tick(target);
            rotate(f, 90, 30);
            for (int i = 0; i < 6; i++) tick(target);
            assertEquals(0.5, ((Vec3) field(target, "currentPosition")).x, 1e-12, "shown x");
            assertFalse(attackCancelled(f, fairReach), "attack on the snapped player");
        }
    }

    // PositionMoveRotation#of and #ofEntityUsingLerpTarget: a teleport is measured from the active lerp target.
    @ParameterizedTest
    @EnumSource(Version.class)
    void aTeleportFarFromTheLerpTargetSnaps(Version version) throws Exception {
        try (var f = fixture(version, new Vec3(0.5, FLOOR_Y, 0.5))) {
            FairReach fairReach = spawnPlayer(f, new Vec3d(64, FLOOR_Y, 1.5));
            walkThenTeleport(f, fairReach);
            assertEquals(0.5, ((Vec3) field(fairReachEntity(fairReach), "currentPosition")).x, 1e-9, "shown x");
            assertFalse(attackCancelled(f, fairReach), "attack on the teleported player");
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aRotationAfterATeleportFarFromTheLerpTargetHoldsTheSnappedPosition(Version version) throws Exception {
        try (var f = fixture(version, new Vec3(0.5, FLOOR_Y, 0.5))) {
            FairReach fairReach = spawnPlayer(f, new Vec3d(64, FLOOR_Y, 1.5));
            Object target = walkThenTeleport(f, fairReach);
            tick(target);
            rotate(f, 90, 30);
            for (int i = 0; i < 6; i++) tick(target);
            double x = ((Vec3) field(target, "currentPosition")).x;
            assertTrue(Math.abs(x - 0.5) < 1.0, "shown x " + x);
            assertFalse(attackCancelled(f, fairReach), "attack at x " + x);
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aRotationAfterATeleportFarFromTheLerpTargetAndItsSyncHoldsTheSnappedPosition(Version version)
            throws Exception {
        try (var f = fixture(version, new Vec3(0.5, FLOOR_Y, 0.5))) {
            FairReach fairReach = spawnPlayer(f, new Vec3d(64, FLOOR_Y, 1.5));
            Object target = walkThenTeleport(f, fairReach);
            tick(target);
            positionSync(f, new Vec3d(0.5, FLOOR_Y, 1.5));
            tick(target);
            rotate(f, 90, 30);
            for (int i = 0; i < 6; i++) tick(target);
            double x = ((Vec3) field(target, "currentPosition")).x;
            assertTrue(Math.abs(x - 0.5) < 1.0, "shown x " + x);
            assertFalse(attackCancelled(f, fairReach), "attack at x " + x);
        }
    }

    /**
     * Walks 0.75 along x from x=64 (target 64.75, shown 64.25 after one tick), then teleports to x=0.5: 64.25 blocks
     * from the lerp target and 63.75 from the shown position.
     */
    private static Object walkThenTeleport(JavaTickFixture f, FairReach fairReach) throws Exception {
        Object target = fairReachEntity(fairReach);
        sendAcknowledged(
                f,
                ClientboundPackets.MOVE_ENTITY,
                new ClientboundMoveEntity(
                        ENTITY, new EntityDelta.Linear((short) 3072, (short) 0, (short) 0), 0, 0, true, true, false),
                (r, e) -> r.onMoveEntity(e, f.player, e.getPacket()));
        assertEquals(64.75, ((Vec3) field(target, "targetPosition")).x, 1e-12, "walk target");
        tick(target);
        assertEquals(64.25, ((Vec3) field(target, "currentPosition")).x, 1e-12, "walk shown");
        teleport(f, new Vec3d(0.5, FLOOR_Y, 1.5));
        return target;
    }

    private static FairReach spawnPlayer(JavaTickFixture f, Vec3d at) {
        spawn(f, "minecraft:player", at);
        FairReach fairReach = f.player.checkManager.getListener(FairReach.class);
        var spawn = new ClientboundAddEntity(ENTITY, "minecraft:player", at, 0, 0, 0);
        fairReach.onAddEntity(
                new PacketSendEvent<>(
                        f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, spawn, false),
                f.player,
                spawn);
        return fairReach;
    }

    /** Sends a packet through replication and answers the transaction that carries FairReach's update. */
    private static <T extends ClientboundPacket> void sendAcknowledged(
            JavaTickFixture f,
            PacketType<T> type,
            T packet,
            BiConsumer<PacketEntityReplication, PacketSendEvent<T>> handler) {
        send(f, type, packet, handler);
        f.player.sendTransaction();
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void teleport(JavaTickFixture f, Vec3d to) {
        sendAcknowledged(
                f,
                ClientboundPackets.TELEPORT_ENTITY,
                new ClientboundTeleportEntity(ENTITY, to, Vec3d.ZERO, 0, 0, Set.of(), true),
                (r, e) -> r.onTeleportEntity(e, f.player, e.getPacket()));
    }

    private static void positionSync(JavaTickFixture f, Vec3d to) {
        sendAcknowledged(
                f,
                ClientboundPackets.ENTITY_POSITION_SYNC,
                new ClientboundEntityPositionSync(ENTITY, new PositionPath.Linear(to), 0, 0, true),
                (r, e) -> r.onEntityPositionSync(e, f.player, e.getPacket()));
    }

    private static void rotate(JavaTickFixture f, float yaw, float pitch) {
        sendAcknowledged(
                f,
                ClientboundPackets.MOVE_ENTITY,
                new ClientboundMoveEntity(
                        ENTITY, new EntityDelta.Linear((short) 0, (short) 0, (short) 0), yaw, pitch, true, false, true),
                (r, e) -> r.onMoveEntity(e, f.player, e.getPacket()));
    }

    private static boolean attackCancelled(JavaTickFixture f, FairReach fairReach) {
        var attack = new ServerboundInteract(ENTITY, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        var event = new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, attack);
        fairReach.onInteract(event, f.player, attack);
        return event.isCancelled();
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aMinecartStepLeavesTheBase(Version version) throws Exception {
        try (var f = fixture(version)) {
            spawn(f, "minecraft:minecart", new Vec3d(OFF_GRID_X, FLOOR_Y, OFF_GRID_Z));
            send(
                    f,
                    ClientboundPackets.MOVE_MINECART,
                    new ClientboundMoveMinecart(
                            ENTITY,
                            List.of(new ClientboundMoveMinecart.Step(new Vec3d(8, FLOOR_Y, 8), Vec3d.ZERO, 0, 0, 1))),
                    (r, e) -> r.onMoveMinecart(e, f.player, e.getPacket()));
            move(f, ENTITY, 4096, 0, 0);
            assertAt(f, ONE_BLOCK_FROM_OFF_GRID_X, FLOOR_Y, OFF_GRID_Z);
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void eachMoveAppliesAtItsOwnProof(Version version) throws Exception {
        try (var f = fixture(version)) {
            spawn(f, "minecraft:zombie", new Vec3d(OFF_GRID_X, FLOOR_Y, OFF_GRID_Z));
            int first = queueMove(f, 4096);
            int second = queueMove(f, 4096);
            PacketEntity entity = f.player.compensatedEntities.getEntity(ENTITY);
            assertEquals(OFF_GRID_X, entity.desyncClientPos.x, "before either proof");
            f.pongThrough(first);
            assertEquals(ONE_BLOCK_FROM_OFF_GRID_X, entity.desyncClientPos.x, "at the first proof");
            f.pongThrough(second);
            assertAt(f, ONE_BLOCK_FROM_OFF_GRID_X + 1, FLOOR_Y, OFF_GRID_Z);
        }
    }

    @ParameterizedTest
    @EnumSource(Version.class)
    void aRemovedVehicleLeavesItsPassengersBase(Version version) throws Exception {
        try (var f = fixture(version)) {
            spawn(f, "minecraft:zombie", new Vec3d(OFF_GRID_X, FLOOR_Y, OFF_GRID_Z));
            send(
                    f,
                    ClientboundPackets.ADD_ENTITY,
                    new ClientboundAddEntity(VEHICLE, "minecraft:oak_boat", new Vec3d(12.5, FLOOR_Y, 12.5), 0, 0, 0),
                    (r, e) -> r.onAddEntity(e, f.player, e.getPacket()));
            send(
                    f,
                    ClientboundPackets.SET_PASSENGERS,
                    new ClientboundSetPassengers(VEHICLE, List.of(ENTITY)),
                    (r, e) -> r.onSetPassengers(e, f.player, e.getPacket()));
            // No position sync follows: Entity#positionRider moved the zombie, but not its codec.
            send(
                    f,
                    ClientboundPackets.REMOVE_ENTITIES,
                    new ClientboundRemoveEntities(List.of(VEHICLE)),
                    (r, e) -> r.onRemoveEntities(e, f.player, e.getPacket()));
            move(f, ENTITY, 4096, 0, 0);
            assertAt(f, ONE_BLOCK_FROM_OFF_GRID_X, FLOOR_Y, OFF_GRID_Z);
        }
    }

    private static Object fairReachEntity(FairReach fairReach) throws Exception {
        Object target = ((Map<?, ?>) field(fairReach, "targetPlayers")).get(ENTITY);
        assertNotNull(target);
        return target;
    }

    private static void tick(Object fairReachEntity) throws Exception {
        Method tick = fairReachEntity.getClass().getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(fairReachEntity);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    /** Sends a relative move without answering its proof; the transaction it is bound to. */
    private static int queueMove(JavaTickFixture f, int xa) {
        var packet = new ClientboundMoveEntity(
                ENTITY, new EntityDelta.Linear((short) xa, (short) 0, (short) 0), 0, 0, true, true, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    /** A ridden tick in which the client paddles its boat {@code dz} forward, along +z. */
    private static void drive(JavaTickFixture f, double dz) {
        PacketEntity boat = f.player.compensatedEntities.getEntity(ENTITY);
        assertNotNull(boat);
        Vec3 before = boat.clientPhysicalPosition;
        PredictionResult paddled = f.rideTick(new Vec3(f.player.x, f.player.y, f.player.z + dz), true);
        assertTrue(paddled.getOffset() < 1.0E-5, "the paddle stroke is predicted, offset " + paddled.getOffset());
        assertEquals(before.z + dz, boat.clientPhysicalPosition.z, 1.0E-9, "the boat moved");
    }

    private static JavaTickFixture fixture(Version version) throws Exception {
        return fixture(version, new Vec3(0.5, FLOOR_Y, -4.5));
    }

    private static JavaTickFixture fixture(Version version, Vec3 at) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version.version);
        f.place(at, Vec3.ZERO);
        return f;
    }

    private static void spawn(JavaTickFixture f, String type, Vec3d at) {
        send(
                f,
                ClientboundPackets.ADD_ENTITY,
                new ClientboundAddEntity(ENTITY, type, at, 0, 0, 0),
                (r, e) -> r.onAddEntity(e, f.player, e.getPacket()));
    }

    private static void move(JavaTickFixture f, int id, int xa, int ya, int za) {
        send(
                f,
                ClientboundPackets.MOVE_ENTITY,
                new ClientboundMoveEntity(
                        id, new EntityDelta.Linear((short) xa, (short) ya, (short) za), 0, 0, true, true, false),
                (r, e) -> r.onMoveEntity(e, f.player, e.getPacket()));
    }

    /** Sends a clientbound packet through replication and answers every transaction it queued. */
    private static <T extends ClientboundPacket> void send(
            JavaTickFixture f,
            PacketType<T> type,
            T packet,
            BiConsumer<PacketEntityReplication, PacketSendEvent<T>> handler) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        handler.accept(f.player.packetEntityReplication, event);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** The packet position the client holds for the entity: the server tracker's and the applied one. */
    private static void assertAt(JavaTickFixture f, double x, double y, double z) {
        TrackerData tracked = f.player.compensatedEntities.getTrackedEntity(ENTITY);
        assertNotNull(tracked);
        assertEquals(x, tracked.getCodecBaseX(), "codec base x");
        assertEquals(y, tracked.getCodecBaseY(), "codec base y");
        assertEquals(z, tracked.getCodecBaseZ(), "codec base z");
        PacketEntity entity = f.player.compensatedEntities.getEntity(ENTITY);
        assertNotNull(entity);
        assertEquals(x, entity.desyncClientPos.x, "applied x");
        assertEquals(y, entity.desyncClientPos.y, "applied y");
        assertEquals(z, entity.desyncClientPos.z, "applied z");
    }
}
