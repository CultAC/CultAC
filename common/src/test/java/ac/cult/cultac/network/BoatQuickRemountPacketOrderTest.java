package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsX;
import ac.cult.cultac.checks.impl.combat.MultiInteractA;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderF;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderH;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoSneakSlow;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A server can mount and dismount a 1.21.4 client before the client processes either. Until
 * ClientPacketListener#handleSetEntityPassengersPacket mounts it, LocalPlayer#tick sends ordinary walking ticks:
 * sendShiftKeyState first, then sendPosition's sprint command and move. Packets come from the capture, moved next to
 * the fixture's floor, with the server's echo of the player's sprint: ServerEntity#sendDirtyEntityData sends the
 * player its own shared flags and movement_speed.
 */
class BoatQuickRemountPacketOrderTest {
    private static final int BOAT = 981;
    private static final float BOAT_YAW = 86.325645F;
    private static final Vec3 SPAWN = at(233.71218727359295, 62.0, 208.3870184482027);
    private static final Vec3 BOAT_AT = at(231.4961717543816, 62.0, 209.6078162730024);
    private static final Vec3 SEAT = at(231.4961517543816, 61.5875, 209.6078162730024);
    private static final Vec3 DISMOUNT = at(230.2238749424196, 62.0, 210.37787003961307);
    // The capture's client stood on the floor, holding LivingEntity#travel's grounded deltaMovement, before it walked
    // off at ground speed. A spawn teleport that zeroed it would leave Entity#move's first move off the ground.
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final AttributeModifier SPRINTING = new AttributeModifier(
            "minecraft:sprinting", 0.30000001192092896, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final Set<Relative> KEEPS_DELTA =
            Set.of(Relative.Y_ROT, Relative.X_ROT, Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);
    private static final List<Class<? extends Check>> CHECKS = List.of(
            PacketOrderH.class,
            PacketOrderF.class,
            BadPacketsX.class,
            BadPacketsVehicle.class,
            MultiInteractA.class,
            NoSneakSlow.class,
            OffsetHandler.class);

    @Test
    void walkingTicksBeforeTheClientMountsAndTheMountedTicksAfterKeepTheirOrder() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            int[] proofs = mountAndDismountUnprocessed(f);

            command(f, PlayerCommandAction.STOP_SPRINTING);
            move(f, at(233.27029238562304, 62.0, 208.44420322402846), 54.544075F, 32.457165F, true);
            f.tickEnd();

            interact(f, Hand.MAIN_HAND, new Vec3d(0.6875, 0.39680513739585876, -0.34383624792099), true);
            interact(f, Hand.OFF_HAND, new Vec3d(0.6875, 0.39680513739585876, -0.34383624792099), true);
            f.input(new ServerboundPlayerInput(true, false, false, false, false, true, true));
            move(f, at(233.16864339785786, 62.0, 208.45814779301995), 52.969063F, 32.682167F, true);
            f.tickEnd();

            command(f, PlayerCommandAction.RELEASE_SHIFT_KEY);
            f.input(new ServerboundPlayerInput(true, false, false, true, false, false, true));
            move(f, at(233.07180122394203, 62.0, 208.46133723287585), 51.112797F, 34.819683F, true);
            f.tickEnd();

            f.input(new ServerboundPlayerInput(false, false, false, true, false, false, true));
            move(f, at(232.95437545328323, 62.0, 208.38759914078045), 48.806526F, 37.46345F, true);
            f.tickEnd();

            // The client takes the server's sprint flag before this tick and its sprinting modifier after it, so
            // LocalPlayer#aiStep starts no sprint, sendIsSprintingIfNeeded sends the flag's START_SPRINTING, and
            // Player#getSpeed reads the movement_speed the client's own STOP_SPRINTING left without the modifier.
            f.pongThrough(proofs[3]);
            interact(f, Hand.MAIN_HAND, new Vec3d(0.479976087808609, 0.5625, -0.25473612546920776), false);
            f.input(new ServerboundPlayerInput(true, false, false, true, false, false, true));
            command(f, PlayerCommandAction.START_SPRINTING);
            move(f, at(232.79026309947898, 62.0, 208.3466862019281), 45.375244F, 37.57596F, true);
            f.tickEnd();

            f.pongThrough(proofs[0] - 1);
            f.acceptTeleport(198);
            move(f, SEAT, 43.068977F, 38.86972F, false);
            f.pongThrough(proofs[0]);
            f.moveVehicle(BOAT_AT, BOAT_YAW, 0, true);
            f.pongThrough(proofs[1]);
            assertTrue(f.player.inVehicle());

            var paddle = new ServerboundPaddleBoat(false, false);
            f.listener.onPaddleBoat(f.event(ServerboundPackets.PADDLE_BOAT, paddle), f.player, paddle);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, true));
            var rot = new ServerboundMovePlayer(0, 0, 0, 85.59438F, 39.600975F, false, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            f.moveVehicle(BOAT_AT, BOAT_YAW, 0, true);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.tickEnd();

            f.pongThrough(proofs[2] - 1);
            f.acceptTeleport(199);
            move(f, DISMOUNT, 58.819115F, 32.175915F, false);
            f.pongThrough(proofs[2]);
            assertFalse(f.player.inVehicle());

            command(f, PlayerCommandAction.PRESS_SHIFT_KEY);
            f.input(new ServerboundPlayerInput(true, false, false, true, false, true, true));
            command(f, PlayerCommandAction.START_SPRINTING);
            move(f, at(230.19852151955087, 62.0, 210.37210775843482), 57.806606F, 32.119663F, false);
            f.tickEnd();

            for (Class<? extends Check> check : CHECKS) {
                assertEquals(0, f.player.checkManager.getCheck(check).violations, check.getSimpleName());
            }
        }
    }

    @Test
    void shiftAfterSprintInOneWalkingTickBeforeTheClientMountsIsFlagged() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            mountAndDismountUnprocessed(f);

            command(f, PlayerCommandAction.STOP_SPRINTING);
            command(f, PlayerCommandAction.RELEASE_SHIFT_KEY);
            move(f, at(233.27029238562304, 62.0, 208.44420322402846), 54.544075F, 32.457165F, true);
            f.tickEnd();

            assertTrue(f.player.checkManager.getCheck(PacketOrderH.class).violations > 0);
        }
    }

    /**
     * The capture's sprinting walk up to the boat, then the server's mount and dismount, which the client processes
     * later. Returns the mount's proof, its move_vehicle's proof, the dismount teleport's proof and the proof of the
     * server's sprint flag.
     */
    private static int[] mountAndDismountUnprocessed(JavaTickFixture f) {
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.player.setExperimentalChecks(true);
        f.place(SPAWN, GROUNDED);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, BOAT_YAW, 0);
        int spawn = playerPosition(f, 197, SPAWN, KEEPS_DELTA);
        f.pongThrough(spawn - 1);
        f.acceptTeleport(197);
        move(f, SPAWN, 60.45037F, 53.213566F, true);
        f.pongThrough(spawn);

        f.input(new ServerboundPlayerInput(true, false, false, false, false, false, true));
        command(f, PlayerCommandAction.START_SPRINTING);
        move(f, at(233.60062898336702, 62.0, 208.4485449479657), 61.12538F, 32.850914F, true);
        f.tickEnd();
        // ServerEntity#sendDirtyEntityData echoes the sprint the server took: the shared flag, then the modifier.
        int sprintFlag = sharedFlags(f, (byte) 0x08);
        movementSpeed(f, List.of(SPRINTING));

        interact(f, Hand.MAIN_HAND, new Vec3d(0.48, 0.5625, -0.25), false);
        command(f, PlayerCommandAction.PRESS_SHIFT_KEY);
        f.input(new ServerboundPlayerInput(true, false, false, true, false, true, true));
        move(f, at(233.41347897336297, 62.0, 208.4510953446014), 58.819115F, 32.175915F, true);
        int mount = f.startRiding(BOAT, 198, SEAT, BOAT_AT, BOAT_YAW, 0.0F);
        int vehicleMove = f.player.lastTransactionSent.get();
        f.tickEnd();

        f.setPassengers(BOAT, List.of());
        int dismount = playerPosition(f, 199, DISMOUNT, Set.of(Relative.Y_ROT, Relative.X_ROT));
        return new int[] {mount, vehicleMove, dismount, sprintFlag};
    }

    /** The player's own shared flags, which the client takes once it answers the returned ping. */
    private static int sharedFlags(JavaTickFixture f, byte flags) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
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

    /** The player's own movement_speed, which the client takes once it answers the ping after it. */
    private static void movementSpeed(JavaTickFixture f, List<AttributeModifier> modifiers) {
        var attributes = new ClientboundUpdateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot("minecraft:movement_speed", 0.10000000149011612, modifiers)));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, attributes, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, attributes);
        event.tasksAfter().forEach(Runnable::run);
    }

    /** A player_position the client accepts once it answers the returned ping. */
    private static int playerPosition(JavaTickFixture f, int id, Vec3 to, Set<Relative> relatives) {
        var teleport =
                new ClientboundPlayerPosition(id, JavaTickFixture.vec3d(to), new Vec3d(0, 0, 0), 0, 0, relatives);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, teleport, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, teleport);
        event.getTasksAfterSend().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static void move(JavaTickFixture f, Vec3 at, float yaw, float pitch, boolean onGround) {
        var move = new ServerboundMovePlayer(at.x, at.y, at.z, yaw, pitch, onGround, false, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, move), f.player, move);
    }

    private static void interact(JavaTickFixture f, Hand hand, Vec3d target, boolean sneaking) {
        var at = new ServerboundInteract(BOAT, InteractAction.INTERACT_AT, hand, Optional.of(target), sneaking);
        f.listener.onInteract(f.event(ServerboundPackets.INTERACT, at), f.player, at);
        var interact = new ServerboundInteract(BOAT, InteractAction.INTERACT, hand, Optional.empty(), sneaking);
        f.listener.onInteract(f.event(ServerboundPackets.INTERACT, interact), f.player, interact);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        var event = f.event(ServerboundPackets.PLAYER_COMMAND, command);
        new PacketEntityAction().onPlayerCommand(event, f.player, command);
        f.listener.onPlayerCommand(event, f.player, command);
    }

    private static Vec3 at(double x, double y, double z) {
        return new Vec3(x - 231, y + 2, z - 209);
    }
}
