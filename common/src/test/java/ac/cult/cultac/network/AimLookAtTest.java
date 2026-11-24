package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.aim.AimDuplicateLook;
import ac.cult.cultac.checks.impl.aim.AimModulo360;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerLookAt;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleLookAt turns the player with Entity#lookAt and sends nothing, leaving LocalPlayer#yRotLast
 * and xRotLast at the last rotation sendPosition sent, so the next tick's sendPosition reports the new look. Positions
 * and looks come from 26.3 captures of {@code rotate @s facing ~5 ~5 ~} and of {@code rotate @s facing entity} at an
 * armor stand after a full turn.
 */
class AimLookAtTest {
    private static final Vec3 FACING_AT = new Vec3(894.4738786097156, 72.0, 1067.1178436671285);
    private static final ClientboundPlayerLookAt FACING =
            new ClientboundPlayerLookAt(false, 899.4738786097156, 77.0, 1067.1178436671285, false, 0, false);
    private static final float FACING_YAW = -90.0f;
    private static final float FACING_PITCH = -44.999996f;
    private static final int STAND = 1949;
    private static final Vec3 TURNED_AT = new Vec3(891.4039993046988, 72.0, 1069.408159350373);
    private static final Vec3 STAND_AT = new Vec3(891.5, 72.0, 1071.5);
    private static final float[][] FULL_TURN = {
        {8.810714f, -0.6728447f},
        {20.720072f, -0.40370685f},
        {34.0424f, -0.13456896f},
        {49.046852f, 0.06728448f},
        {64.99326f, 0.20185344f},
        {83.16004f, 0.13456896f},
        {101.99969f, 0.06728448f},
        {121.310326f, 0.26913792f},
        {141.09196f, 0.80741364f},
        {161.68105f, 2.0858188f},
        {181.05902f, 3.4315104f},
        {200.908f, 4.9117713f},
        {217.39273f, 6.257463f},
        {218.33473f, 6.4593167f},
        {236.03056f, 4.508064f},
        {261.19492f, 5.0463405f},
        {289.65625f, 6.1901784f},
        {316.23355f, 5.584617f},
        {336.21698f, 3.7006488f},
        {351.2885f, 2.2876725f},
        {360.3719f, 1.6148274f},
        {361.98685f, 1.3456895f}
    };
    private static final int CAMEL = 1950;
    private static final Vec3 CAMEL_AT = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 NORTH_OF_CAMEL = new Vec3(0.75, JavaTickFixture.FLOOR_Y, -5.5);
    private static final Vec3 VIEWER = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 10.5);
    private static final int INTERACTION = 1951;
    private static final Vec3 INTERACTION_FROM = new Vec3(0.375, JavaTickFixture.FLOOR_Y, 5.5);
    private static final Vec3 INTERACTION_TO = new Vec3(0.625, JavaTickFixture.FLOOR_Y, 5.5);
    private static final int SNOWBALL = 1952;
    private static final Vec3 SNOWBALL_FROM = new Vec3(1.0, 66.0, 5.5);
    private static final Vec3 SNOWBALL_VELOCITY = new Vec3(-0.8, 0.0, 0.0);
    // One client ThrowableProjectile#tick: applyGravity (0.03), applyInertia (0.99F), setPos(position + delta).
    private static final Vec3 SNOWBALL_TO = new Vec3(0.2079999923706054, 65.9702999997139, 5.5);
    private static final int UNLISTED_PLAYER = 1953;
    private static final Vec3 UNLISTED_FROM = new Vec3(1.0, JavaTickFixture.FLOOR_Y, 5.5);
    private static final Vec3 UNLISTED_TO = new Vec3(0.2080078125, JavaTickFixture.FLOOR_Y, 5.5);

    @Test
    void theRotReportingTheLookOfALookAtIsNoDuplicateLook() throws Exception {
        try (var f = fixture(AimDuplicateLook.class, FACING_AT)) {
            look(f, 12.5f, 20.0f);
            lookAt(f, FACING);
            look(f, FACING_YAW, FACING_PITCH);
            assertEquals(0, violations(f, AimDuplicateLook.class));
        }
    }

    @Test
    void aRotRepeatingTheLookOfALookAtFlags() throws Exception {
        try (var f = fixture(AimDuplicateLook.class, FACING_AT)) {
            look(f, 12.5f, 20.0f);
            lookAt(f, FACING);
            look(f, FACING_YAW, FACING_PITCH);
            look(f, FACING_YAW, FACING_PITCH);
            assertEquals(1, violations(f, AimDuplicateLook.class));
        }
    }

    /** Entity#lookAt wraps the yaw with Mth#wrapDegrees, a turn back from past a full turn. */
    @Test
    void theLookAtAnEntityAfterAFullTurnIsNoModulo360Snap() throws Exception {
        try (var f = fixture(AimModulo360.class, TURNED_AT)) {
            turnAround(f);
            lookAt(f, new ClientboundPlayerLookAt(false, STAND_AT.x, STAND_AT.y, STAND_AT.z, true, STAND, false));
            look(f, -2.6276474f, 0.0f);
            assertEquals(0, violations(f, AimModulo360.class));
        }
    }

    @Test
    void aTurnBackByAFullTurnWithoutALookAtFlags() throws Exception {
        try (var f = fixture(AimModulo360.class, TURNED_AT)) {
            turnAround(f);
            look(f, -2.6276474f, 0.0f);
            assertEquals(1, violations(f, AimModulo360.class));
        }
    }

    /** A camel driver's Entity#lookAt measures from its seat, 0.5 ahead of the camel along the camel's yaw. */
    @Test
    void aCamelDriversLookAtAnEntityIsNoModulo360Snap() throws Exception {
        try (var f = new JavaTickFixture()) {
            Check check = f.player.checkManager.getCheck(AimModulo360.class);
            check.setEnabled(true);
            check.reload();
            f.place(CAMEL_AT.add(0, 1.4, 0), Vec3.ZERO);
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, CAMEL_AT, -90.0f, 0, 0);
            f.setPassengers(CAMEL, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 60; tick++) drive(f, -90.0f, 0.0f);
            f.addEntity(STAND, "minecraft:armor_stand", NORTH_OF_CAMEL);
            f.pongThrough(f.player.lastTransactionSent.get());
            drive(f, -90.0f, 0.0f);
            drive(f, -90.0f, 0.0f);
            check.violations = 0;
            lookAt(
                    f,
                    new ClientboundPlayerLookAt(
                            false, NORTH_OF_CAMEL.x, NORTH_OF_CAMEL.y, NORTH_OF_CAMEL.z, true, STAND, false));
            drive(f, 177.61398f, 13.122987f);
            assertEquals(0, violations(f, AimModulo360.class));
        }
    }

    /**
     * A 26.2 client's Entity#moveOrInterpolateTo sets an entity without an InterpolationHandler on its move at once, so
     * a look-at handled before the next tick aims at the moved interaction, just east of due north.
     */
    @Test
    void aLookAtAnEntityMovedBeforeTheClientTicksIsNoModulo360Snap() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_2, ClientVersion.V_26_2)) {
            Check check = f.player.checkManager.getCheck(AimModulo360.class);
            check.setEnabled(true);
            check.reload();
            f.place(VIEWER, Vec3.ZERO);
            f.tickEnd();
            look(f, -170.0f, 0.0f);
            look(f, -171.0f, 0.0f);
            f.addEntity(INTERACTION, "minecraft:interaction", INTERACTION_FROM);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();
            moveX(f, INTERACTION, (short) 1024);
            check.violations = 0;
            lookAt(
                    f,
                    new ClientboundPlayerLookAt(
                            false, INTERACTION_TO.x, INTERACTION_TO.y, INTERACTION_TO.z, true, INTERACTION, false));
            look(f, -178.56804f, -0.0f);
            assertEquals(0, violations(f, AimModulo360.class));
        }
    }

    /**
     * ThrowableProjectile#tick moves the client's snowball by its own delta movement, so a look-at after that tick aims
     * at the moved snowball, just west of due north.
     */
    @Test
    void aLookAtASnowballCrossingDueNorthIsNoModulo360Snap() throws Exception {
        try (var f = new JavaTickFixture()) {
            Check check = f.player.checkManager.getCheck(AimModulo360.class);
            check.setEnabled(true);
            check.reload();
            f.place(VIEWER, Vec3.ZERO);
            f.tickEnd();
            look(f, 170.0f, 0.0f);
            look(f, 171.0f, 0.0f);
            var spawn = new ClientboundAddEntity(
                    SNOWBALL,
                    "minecraft:snowball",
                    new Vec3d(SNOWBALL_FROM.x, SNOWBALL_FROM.y, SNOWBALL_FROM.z),
                    0,
                    0,
                    0,
                    new UUID(0, 0),
                    new Vec3d(SNOWBALL_VELOCITY.x, SNOWBALL_VELOCITY.y, SNOWBALL_VELOCITY.z));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, spawn, false);
            f.player.packetEntityReplication.onAddEntity(event, f.player, spawn);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.statusTick(true, false);
            check.violations = 0;
            lookAt(
                    f,
                    new ClientboundPlayerLookAt(
                            false, SNOWBALL_TO.x, SNOWBALL_TO.y, SNOWBALL_TO.z, true, SNOWBALL, false));
            look(f, 176.6577f, -21.474243f);
            assertEquals(0, violations(f, AimModulo360.class));
        }
    }

    /**
     * ClientPacketListener#createEntityFromPacket spawns no player that has no PlayerInfo entry, so a look-at at one aims
     * at the sent point, just west of due north.
     */
    @Test
    void aLookAtAPlayerSpawnedWithoutPlayerInfoIsNoModulo360Snap() throws Exception {
        try (var f = new JavaTickFixture()) {
            Check check = f.player.checkManager.getCheck(AimModulo360.class);
            check.setEnabled(true);
            check.reload();
            f.place(VIEWER, Vec3.ZERO);
            f.tickEnd();
            look(f, 170.0f, 0.0f);
            look(f, 171.0f, 0.0f);
            f.addEntity(UNLISTED_PLAYER, "minecraft:player", UNLISTED_FROM);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.statusTick(true, false);
            moveX(f, UNLISTED_PLAYER, (short) -3244);
            check.violations = 0;
            lookAt(
                    f,
                    new ClientboundPlayerLookAt(
                            false, UNLISTED_TO.x, UNLISTED_TO.y, UNLISTED_TO.z, true, UNLISTED_PLAYER, false));
            look(f, 176.65779f, -0.0f);
            assertEquals(0, violations(f, AimModulo360.class));
        }
    }

    /** ClientPacketListener#handleMoveEntity moving {@code entity} {@code dx} 4096ths of a block along x. */
    private static void moveX(JavaTickFixture f, int entity, short dx) {
        var move = new ClientboundMoveEntity(
                entity, new EntityDelta.Linear(dx, (short) 0, (short) 0), 0, 0, true, true, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, move);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** One ridden tick: the passenger Rot, then the camel's move. */
    private static void drive(JavaTickFixture f, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(CAMEL_AT.x, CAMEL_AT.y, CAMEL_AT.z), -90.0f, 0.0f, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
    }

    private static void turnAround(JavaTickFixture f) {
        f.addEntity(STAND, "minecraft:armor_stand", STAND_AT);
        f.pongThrough(f.player.lastTransactionSent.get());
        for (float[] look : FULL_TURN) look(f, look[0], look[1]);
    }

    private static JavaTickFixture fixture(Class<? extends Check> checked, Vec3 at) throws Exception {
        var f = new JavaTickFixture();
        Check check = f.player.checkManager.getCheck(checked);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        f.player.x = f.player.lastX = at.x;
        f.player.y = f.player.lastY = at.y;
        f.player.z = f.player.lastZ = at.z;
        f.tickEnd();
        return f;
    }

    private static void lookAt(JavaTickFixture f, ClientboundPlayerLookAt packet) {
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_LOOK_AT, packet, false);
        new PacketServerTeleport().onPlayerLookAt(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** One client tick whose sendPosition sends only a Rot. */
    private static void look(JavaTickFixture f, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.tickEnd();
    }

    private static double violations(JavaTickFixture f, Class<? extends Check> checked) {
        return f.player.checkManager.getCheck(checked).getViolations();
    }
}
