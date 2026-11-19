package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A passenger seated on a block display with its west face 0.002 inside a cobweb's cell, beside a Shulker attached to
 * the wall west of it. Each Shulker#onPeekAmountChange moves the passenger east with Entity#move(MoverType.SHULKER);
 * from 1.21.5 Entity#movementThisTick records it until the passenger's next LivingEntity#aiStep, whose
 * Entity#applyEffectsFromBlocks replays it before the travel's own move. A peek before the passenger's tick (the
 * shulker ticks first) or after its positionRider (the vehicle ticks first) carries the box out of the web, and from
 * 1.21.9 only that recorded movement still meets it: makeStuckInBlock leaves the cobweb multiplier, which scales the
 * first move after the dismount.
 */
class JavaPassengerPeekRecordedMoveTest {
    private static final int SHULKER = 100;
    private static final int VEHICLE = 101;
    private static final Vec3 SHULKER_AT = new Vec3(5.5, FLOOR_Y + 1, 0.5);
    private static final BlockPos WALL = new BlockPos(4, FLOOR_Y + 1, 0);
    private static final BlockPos WEB = new BlockPos(5, FLOOR_Y + 2, 0);
    /** Entity#positionRider puts the player 0.6 below the display: its west face at x 5.998. */
    private static final Vec3 DISPLAY = new Vec3(6.298, FLOOR_Y + 2.1, 0.5);
    private static final Vec3 COBWEB = new Vec3(0.25, 0.05F, 0.25);
    private static final double FALL = -0.08 * 0.98F;
    private static final double PUSH = 0.5;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    static Stream<Arguments> orders() {
        var arguments = new ArrayList<Arguments>();
        for (ClientVersion version :
                List.of(ClientVersion.V_1_21_5, ClientVersion.V_1_21_11, ClientVersion.V_26_2, ClientVersion.V_26_3)) {
            arguments.add(Arguments.of(version, true));
            arguments.add(Arguments.of(version, false));
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("orders")
    void theRecordedPeekMoveStillMeetsTheCobwebTheRiderLeft(ClientVersion version, boolean shulkerFirst)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.input(NONE);
            f.player.compensatedWorld.updateBlock(WALL, BlockIds.STONE.defaultState());
            f.player.compensatedWorld.updateBlock(WEB, BlockIds.COBWEB.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            Vec3 standing = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(standing, new Vec3(0, FALL, 0));
            PredictionResult stand = f.tick(standing, true);
            assertTrue(exact(stand), describe(stand));

            // ClientLevel#tickEntities runs the entities in the order they were added; the passenger rides in its
            // root's place.
            if (shulkerFirst) addShulker(f);
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.BLOCK_DISPLAY, DISPLAY, 0, 0, 0);
            if (!shulkerFirst) addShulker(f);
            // The server holds the passenger at its seat, where the dismount leaves it.
            Vec3 seat = new Vec3(DISPLAY.x, DISPLAY.y - 0.6, DISPLAY.z);
            f.place(seat, new Vec3(0, FALL, 0));
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
            for (int tick = 0; tick < 3; tick++) f.rideTick();

            // Six peek steps to 0.3; once the passenger rides after the shulker's last step, the peek after the
            // vehicle needs one more ride tick to replay the step that left it outside.
            f.pongThrough(peek(f, (byte) 30));
            for (int tick = 0; tick < (shulkerFirst ? 6 : 7); tick++) f.rideTick();
            dismount(f);

            boolean slowed = version.isNewerThanOrEquals(ClientVersion.V_1_21_9);
            f.pongThrough(f.velocity(new Vec3(PUSH, 0, 0)));
            Vec3 from = new Vec3(f.player.x, f.player.y, f.player.z);
            PredictionResult move = f.tick(from.add(slowed ? PUSH * COBWEB.x : PUSH, 0, 0), false);
            assertEquals(seat, from);
            assertTrue(exact(move), version + (shulkerFirst ? " shulker first: " : " vehicle first: ")
                    + describe(move));
        }
    }

    private static void addShulker(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, SHULKER_AT, 0, 0, 0);
        f.pongThrough(attachFace(f, Direction.WEST));
    }

    /** The dismount, and the resync teleport the passenger packet sends. */
    private static void dismount(JavaTickFixture f) {
        var before = new ArrayList<Integer>();
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports)
            before.add(teleport.getTeleportId());
        f.setPassengers(VEHICLE, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            if (before.contains(teleport.getTeleportId())) continue;
            Vec3 target = teleport.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    teleport.getTeleportId(),
                    new Vec3d(target.x, target.y, target.z),
                    teleport.getFinalYaw(),
                    teleport.getFinalPitch());
            var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
            new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
            assertFalse(event.isCancelled(), "the dismount teleport acceptance was rejected");
        }
        assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
    }

    /** The shulker's DATA_ATTACH_FACE_ID; its proof transaction. */
    private static int attachFace(JavaTickFixture f, Direction face) {
        var entry = new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_ATTACH_FACE, face, ByteBuffer.wrap(new byte[] {
            (byte) WatchableIndexUtil.SHULKER_ATTACH_FACE, 11, (byte) face.ordinal()
        }));
        return metadata(f, new EntityMetadata(SHULKER, List.of(entry)));
    }

    /** The shulker's DATA_PEEK_ID; its proof transaction. */
    private static int peek(JavaTickFixture f, byte peek) {
        return metadata(
                f,
                new EntityMetadata(
                        SHULKER, List.of(EntityMetadata.Entry.byteValue(WatchableIndexUtil.SHULKER_PEEK, peek))));
    }

    private static int metadata(JavaTickFixture f, EntityMetadata packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
