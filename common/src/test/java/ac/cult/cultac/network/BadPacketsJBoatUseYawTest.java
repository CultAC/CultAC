package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Entity#turn ends every mouse turn of a boat rider with AbstractBoat#clampRotation against the boat's yaw, which the
 * boat's own tick changes only after Minecraft#tick sent the use_item, so a use_item carries a yaw that clampRotation
 * returns for the boat's yaw at the last tick end, or the yaw the client last sent.
 */
class BadPacketsJBoatUseYawTest {
    private static final int BOAT = 100;
    private static final int DRIVER = 101;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final float PITCH = 19.679043f;
    private static final int FAR_X = 88;
    private static final Vec3 DRIFT = new Vec3(0.01, 0.0, 0.0);

    @Test
    void useYawsPastTheClampOfADrivenBoatFlag() throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f, 0.0F, false);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            rot(f, 105.0F);
            moveBoat(f, 0.0F);
            f.tickEnd();
            int uses = 0;
            for (float yaw : new float[] {120.0F, 150.0F, 179.0F, -150.0F}) {
                assertNotEquals(yaw, f.player.xRot);
                use(f, yaw);
                uses++;
                assertEquals(uses, check.getViolations(), "use yaw " + yaw);
                rot(f, clampRotation(yaw, 0.0F));
                moveBoat(f, 0.0F);
                f.tickEnd();
            }
            assertEquals(uses, check.getViolations());
        }
    }

    @Test
    void useYawsTheClampReturnsAcrossItsWholeArcMatch() throws Exception {
        float[][] boats = {
            {-66.75605F, 0},
            {-66.75605F, 1},
            {400.3F, 0},
            {130950.37F, 0},
            {-130950.37F, 0},
            {-66.75605F, 278},
            {99950.5F, 0}
        };
        int pastComparison = 0;
        for (float[] boat : boats) {
            float boatYaw = boat[0];
            Set<Float> yaws = clampOutputs(boatYaw, (int) boat[1]);
            for (float yaw : yaws) {
                if (!withinComparison(yaw, boatYaw)) pastComparison++;
            }
            try (var f = new JavaTickFixture()) {
                ride(f, boatYaw, false);
                BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
                for (float yaw : yaws) {
                    // A look at the boat first, so the use_item comes after a mouse turn.
                    rot(f, boatYaw);
                    moveBoat(f, boatYaw);
                    f.tickEnd();
                    use(f, yaw);
                    rot(f, clampRotation(yaw, boatYaw));
                    moveBoat(f, boatYaw);
                    f.tickEnd();
                }
                assertEquals(0, check.getViolations(), "boat yaw " + boatYaw + ", use yaws " + yaws);
            }
        }
        assertTrue(pastComparison >= 5, "too few use yaws past clampRotation's comparison: " + pastComparison);
    }

    @Test
    void aPassengersUseYawPastTheClampOfTheInterpolatedBoatFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f, 0.0F, true);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            rot(f, 105.0F);
            f.tickEnd();
            use(f, 150.0F);
            assertEquals(1, check.getViolations());
            rot(f, clampRotation(150.0F, 0.0F));
            f.tickEnd();
            assertEquals(1, check.getViolations());
        }
    }

    /** ClientPacketListener#handleMoveEntity leaves the rotation of a boat the local player drives to its driver. */
    @Test
    void aDrivenBoatKeepsItsYawThroughAServerMove() throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f, 0.0F, false);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            rot(f, 105.0F);
            moveBoat(f, 0.0F);
            f.tickEnd();
            turnBoat(f, 90.0F);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(0.0F, riding(f).clientPhysicalYaw);
            use(f, 150.0F);
            assertEquals(1, check.getViolations());
            rot(f, clampRotation(150.0F, 0.0F));
            moveBoat(f, 0.0F);
            f.tickEnd();
            float yaw = clampRotation(-150.0F, 0.0F);
            use(f, yaw);
            rot(f, yaw);
            moveBoat(f, 0.0F);
            f.tickEnd();
            assertEquals(1, check.getViolations());
        }
    }

    /** AbstractBoat#controlBoat turns a driven boat by its deltaRotation, which positionRider turns the rider by. */
    @Test
    void aDrivenBoatTurnsByItsDeltaRotation() throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f, 0.0F, false);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            rot(f, 0.0F);
            moveBoat(f, 0.0F);
            f.tickEnd();
            float[] uses = {100.0F, -40.0F, 170.0F, 10.0F, -120.0F, 60.0F};
            for (int i = 0; i < uses.length; i++) {
                float yaw = uses[i];
                float boatYaw = yaw - (i % 2 == 0 ? 180.0F : -180.0F);
                float sent = clampRotation(yaw, boatYaw);
                use(f, yaw);
                rot(f, sent);
                moveBoat(f, boatYaw);
                f.tickEnd();
                assertEquals(i + 1, check.getViolations(), "use yaw " + yaw);
                if (i + 1 < uses.length) {
                    rot(f, sent);
                    moveBoat(f, uses[i + 1]);
                    f.tickEnd();
                }
            }
        }
    }

    /** AbstractBoat#controlBoat's deltaRotation while a turn key is held and as friction slows it after release. */
    @Test
    void aDriverUsesItemsThroughATurn() throws Exception {
        var left = new ServerboundPlayerInput(false, false, true, false, false, false, false);
        var released = new ServerboundPlayerInput(false, false, false, false, false, false, false);
        try (var f = new JavaTickFixture()) {
            float boatYaw = -66.75605F;
            ride(f, boatYaw, false);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            float friction = 0.0F;
            for (int i = 0; i < 9; i++) friction += 0.6F;
            friction /= 9;
            float deltaRotation = 0.0F;
            float yaw = -71.855606F;
            int clamped = 0;
            for (int tick = 0; tick < 16; tick++) {
                if (tick == 0) f.input(left);
                if (tick == 4) f.input(released);
                if (tick > 0) {
                    // A mouse turn ends in Entity#turn's clampRotation against the boat's yaw before this tick.
                    float looked = clampRotation(yaw + 20.0F, boatYaw);
                    if (looked != yaw + 20.0F) clamped++;
                    yaw = looked;
                    use(f, yaw);
                }
                deltaRotation *= friction;
                if (tick > 0 && tick <= 4) deltaRotation--;
                boatYaw += deltaRotation;
                yaw = clampRotation(yaw + deltaRotation, boatYaw);
                rot(f, yaw);
                moveBoat(f, boatYaw);
                f.tickEnd();
            }
            assertTrue(clamped > 0);
            assertEquals(0, check.getViolations());
        }
    }

    /** ClientPacketListener#handleMoveVehicle snaps the boat, whose next positionRider clamps the rider against it. */
    @Test
    void aServerVehicleMoveTurnsTheRiderAgainstTheSnappedBoat() throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f, 0.0F, false);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            rot(f, 0.0F);
            moveBoat(f, 0.0F);
            f.tickEnd();
            Vec3d snapped = new Vec3d(SEAT.x, SEAT.y, SEAT.z + 1.0);
            var correction = new ClientboundMoveVehicle(snapped, 150.0F, 0.0F);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_VEHICLE, correction, false);
            new PacketServerTeleport().onMoveVehicle(event, f.player, correction);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            moveBoat(f, snapped, 150.0F);
            float sent = clampRotation(0.0F, 150.0F);
            assertNotEquals(0.0F, sent);
            use(f, 0.0F);
            rot(f, sent);
            moveBoat(f, snapped, 150.0F);
            f.tickEnd();
            float yaw = clampRotation(-100.0F, 150.0F);
            use(f, yaw);
            rot(f, yaw);
            moveBoat(f, snapped, 150.0F);
            f.tickEnd();
            assertEquals(0, check.getViolations());
        }
    }

    /**
     * A boat another entity drives turns by LinearInterpolationHandler#interpolate before its passenger's
     * AbstractBoat#positionRider clamps the passenger against the boat's new yaw.
     */
    @Test
    void aPassengersTickRotationIsClampedAgainstTheInterpolatedBoat() throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f, 0.0F, true);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            rot(f, -104.0F);
            f.tickEnd();
            turnBoat(f, 90.0F);
            f.pongThrough(f.player.lastTransactionSent.get());
            float boatYaw = 0.0F;
            float yaw = -104.0F;
            int clamped = 0;
            for (int remaining = 3; remaining > 0; remaining--) {
                yaw = clampRotation(boatYaw - 100.0F, boatYaw);
                use(f, yaw);
                boatYaw = rotLerp(1.0 / remaining, boatYaw, 90.0F);
                float turned = clampRotation(yaw, boatYaw);
                if (turned != yaw) clamped++;
                rot(f, turned);
                f.tickEnd();
            }
            assertEquals(3, clamped);
            assertEquals(boatYaw, riding(f).clientPhysicalYaw);
            assertEquals(0, check.getViolations());
        }
    }

    /**
     * ClientPacketListener#handleTeleportEntity snaps a boat more than 64 blocks away, runs the rider's
     * AbstractBoat#positionRider against the boat's new yaw and echoes the boat's move; AbstractBoat#controlBoat turns
     * that yaw in the next tick.
     */
    @Test
    void aDriverUsesItemsAfterItsBoatIsTeleportedOutOfInterpolationRange() throws Exception {
        assertEquals(0, useItemsThroughATeleport(true));
    }

    /**
     * ClientPacketListener#handleTeleportEntity interpolates a ticking boat within 64 blocks: for three ticks
     * Entity#commonTick runs LinearInterpolationHandler#interpolate before AbstractBoat#controlBoat turns the boat.
     */
    @Test
    void aDriverUsesItemsWhileItsTeleportedBoatInterpolates() throws Exception {
        assertEquals(0, useItemsThroughATeleport(false));
    }

    /**
     * The third LinearInterpolationHandler step puts a boat that does not move itself exactly on the teleport target.
     * LocalPlayer#sendChanges sends that tick's ServerboundMoveVehiclePacket after the rider's Rot.
     */
    @Test
    void aDriverUsesItemsAfterItsStillBoatInterpolatesOntoTheTarget() throws Exception {
        assertEquals(0, useItemsAfterAStillBoatIsTeleported(false));
    }

    /**
     * A ticking boat interpolates onto a teleport_entity target and ClientPacketListener#handleTeleportEntity sends no
     * response. ClientPacketListener#handleMoveVehicle answers a later server vehicle move from the packet handler.
     */
    @Test
    void aServerVehicleMoveAfterTheStillBoatInterpolatesOntoTheTargetIsAnswered() throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f, 0.0F, false);
            BadPacketsVehicle check = f.player.checkManager.getListener(BadPacketsVehicle.class);
            for (int tick = 0; tick < 3; tick++) {
                rot(f, 0.0F);
                moveBoat(f, 0.0F);
                f.tickEnd();
            }
            f.pongThrough(f.player.lastTransactionSent.get());
            Vec3 target = new Vec3(SEAT.x, SEAT.y, SEAT.z + 5.0);
            teleportBoat(f, target, 0.0F);
            f.pongThrough(f.player.lastTransactionSent.get());
            Vec3 boat = SEAT;
            for (int steps = 3; steps > 0; steps--) {
                boat = boat.lerp(target, 1.0 / steps);
                rot(f, 0.0F);
                moveBoat(f, new Vec3d(boat.x, boat.y, boat.z), 0.0F);
                f.tickEnd();
            }
            assertEquals(target, boat);
            Vec3d moved = new Vec3d(SEAT.x + 1.0, SEAT.y, SEAT.z + 5.0);
            var correction = new ClientboundMoveVehicle(moved, 0.0F, 0.0F);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_VEHICLE, correction, false);
            new PacketServerTeleport().onMoveVehicle(event, f.player, correction);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get() - 1);
            var echo = new ServerboundMoveVehicle(moved, 0.0F, 0, true, true);
            var echoEvent = f.event(ServerboundPackets.MOVE_VEHICLE, echo);
            f.listener.onMoveVehicle(echoEvent, f.player, echo);
            assertFalse(echoEvent.isCancelled());
            assertEquals(0, check.getViolations());
        }
    }

    /** The ping after a teleport bundle can reach the client after its next tick. */
    @Test
    void aDriverUsesItemsAfterItsStillBoatInterpolatesOntoTheTargetBeforeTheNextPing() throws Exception {
        assertEquals(0, useItemsAfterAStillBoatIsTeleported(true));
    }

    /**
     * Steers left on stone, releases the keys and has the server teleport the turning boat 5 blocks and 150 degrees
     * round; the boat does not move itself, and the rider uses an item every tick for a second. The ping the server
     * sends after the teleport is answered after the first tick when {@code trailingPong}. Returns BadPacketsJ's
     * violations.
     */
    private static double useItemsAfterAStillBoatIsTeleported(boolean trailingPong) throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f, 0.0F, false);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            float friction = stoneFriction();
            float deltaRotation = 0.0F;
            float boatYaw = 0.0F;
            float yaw = 0.0F;
            for (int tick = 0; tick < 6; tick++) {
                if (tick == 0) f.input(new ServerboundPlayerInput(false, false, true, false, false, false, false));
                if (tick == 4) f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
                deltaRotation *= friction;
                if (tick > 0 && tick <= 4) deltaRotation--;
                boatYaw += deltaRotation;
                yaw = clampRotation(yaw + deltaRotation, boatYaw);
                rot(f, yaw);
                moveBoat(f, boatYaw);
                f.tickEnd();
            }
            Vec3 target = new Vec3(SEAT.x, SEAT.y, SEAT.z + 5.0);
            float targetYaw = boatYaw + 150.0F;
            teleportBoat(f, target, targetYaw);
            int bundlePing = f.player.lastTransactionSent.get();
            int nextPing = f.player.sendTransactionAndGetId();
            f.pongThrough(trailingPong ? bundlePing : nextPing);
            Vec3 boat = SEAT;
            Vec3 lastStep = boat;
            float lastStepYaw = boatYaw;
            int steps = 3;
            int clamped = 0;
            for (int tick = 0; tick < 20; tick++) {
                if (steps > 0) {
                    // AbstractInterpolationHandler#interpolate carries the boat's own turn into the target.
                    targetYaw += boatYaw - lastStepYaw;
                    boat = boat.lerp(target, 1.0 / steps);
                    boatYaw = rotLerp(1.0 / steps, boatYaw, targetYaw);
                    lastStep = boat;
                    lastStepYaw = boatYaw;
                    steps--;
                }
                use(f, yaw);
                deltaRotation *= friction;
                boatYaw += deltaRotation;
                float turned = yaw + deltaRotation;
                yaw = clampRotation(turned, boatYaw);
                if (yaw != turned) clamped++;
                rot(f, yaw);
                moveBoat(f, new Vec3d(boat.x, boat.y, boat.z), boatYaw);
                f.tickEnd();
                if (tick == 0) f.pongThrough(nextPing);
            }
            assertEquals(target, lastStep);
            assertEquals(deltaRotation, f.player.boatData.deltaRotation);
            assertEquals(boatYaw, riding(f).clientPhysicalYaw);
            assertTrue(clamped > 0);
            return check.getViolations();
        }
    }

    /**
     * Steers left on stone, has the server teleport the boat 150 degrees round, past 64 blocks when {@code far}, and
     * uses an item every tick after, the rider's yaw held 105 degrees from the boat by clampRotation. The boat drifts
     * {@link #DRIFT} a tick from the teleport on. Returns BadPacketsJ's violations.
     */
    private static double useItemsThroughATeleport(boolean far) throws Exception {
        try (var f = new JavaTickFixture()) {
            layFarFloor(f);
            ride(f, 0.0F, false);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            float friction = stoneFriction();
            float deltaRotation = 0.0F;
            float boatYaw = 0.0F;
            float yaw = 0.0F;
            for (int tick = 0; tick < 6; tick++) {
                if (tick == 0) f.input(new ServerboundPlayerInput(false, false, true, false, false, false, false));
                deltaRotation *= friction;
                if (tick > 0) deltaRotation--;
                boatYaw += deltaRotation;
                yaw = clampRotation(yaw + deltaRotation, boatYaw);
                rot(f, yaw);
                moveBoat(f, boatYaw);
                f.tickEnd();
            }
            Vec3 target = far ? new Vec3(FAR_X + 0.5, SEAT.y, SEAT.z) : new Vec3(SEAT.x, SEAT.y, SEAT.z + 5.0);
            float targetYaw = boatYaw + 150.0F;
            teleportBoat(f, target, targetYaw);
            Vec3 boat = far ? target : SEAT;
            if (far) {
                boatYaw = targetYaw;
                yaw = clampRotation(yaw + deltaRotation, boatYaw);
                moveBoat(f, new Vec3d(boat.x, boat.y, boat.z), boatYaw);
            }
            f.pongThrough(f.player.lastTransactionSent.get());
            int steps = far ? 0 : 3;
            Vec3 lastStep = boat;
            float lastStepYaw = boatYaw;
            int clamped = 0;
            for (int tick = 0; tick < 5; tick++) {
                if (steps > 0) {
                    // AbstractInterpolationHandler#interpolate carries the boat's own move and turn into the target.
                    Vec3 moved = boat.subtract(lastStep);
                    if (moved.lengthSqr() > 1.0E-5F) target = target.add(moved);
                    targetYaw += boatYaw - lastStepYaw;
                    boat = boat.lerp(target, 1.0 / steps);
                    boatYaw = rotLerp(1.0 / steps, boatYaw, targetYaw);
                    lastStep = boat;
                    lastStepYaw = boatYaw;
                    steps--;
                }
                use(f, yaw);
                deltaRotation *= friction;
                deltaRotation--;
                boatYaw += deltaRotation;
                float turned = yaw + deltaRotation;
                yaw = clampRotation(turned, boatYaw);
                if (yaw != turned) clamped++;
                boat = boat.add(DRIFT);
                rot(f, yaw);
                moveBoat(f, new Vec3d(boat.x, boat.y, boat.z), boatYaw);
                f.tickEnd();
            }
            assertTrue(clamped > 0);
            return check.getViolations();
        }
    }

    // Stone around FAR_X, more than 64 blocks from SEAT, in the chunks that hold it.
    private static void layFarFloor(JavaTickFixture f) {
        for (int chunkZ = -1; chunkZ <= 0; chunkZ++) {
            f.player.compensatedWorld.ensureValidationChunkLoaded(FAR_X >> 4, chunkZ);
        }
        for (int x = FAR_X - 6; x <= FAR_X + 6; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    // AbstractBoat#getGroundFriction averages the friction of the nine stone blocks under the boat.
    private static float stoneFriction() {
        float friction = 0.0F;
        for (int i = 0; i < 9; i++) friction += 0.6F;
        return friction / 9;
    }

    private static void teleportBoat(JavaTickFixture f, Vec3 target, float yaw) {
        var packet = new ClientboundTeleportEntity(
                BOAT, new Vec3d(target.x, target.y, target.z), Vec3d.ZERO, yaw, 0, Set.of(), true);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void ride(JavaTickFixture f, float boatYaw, boolean passenger) {
        f.place(SEAT, Vec3.ZERO);
        Vec3d seat = new Vec3d(SEAT.x, SEAT.y, SEAT.z);
        send(
                f,
                ClientboundPackets.ADD_ENTITY,
                new ClientboundAddEntity(BOAT, "minecraft:oak_boat", seat, boatYaw, 0, 0));
        if (passenger) {
            send(f, ClientboundPackets.ADD_ENTITY, new ClientboundAddEntity(DRIVER, "minecraft:zombie", seat, 0, 0, 0));
            f.setPassengers(BOAT, List.of(DRIVER, f.player.entityID));
        } else {
            f.setPassengers(BOAT, List.of(f.player.entityID));
        }
        f.pongThrough(f.player.lastTransactionSent.get());
        PacketEntity boat = riding(f);
        assertEquals(BOAT, boat.getEntityId());
        assertEquals(passenger, boat.passengers.get(0) != f.player.compensatedEntities.getSelf());
    }

    private static PacketEntity riding(JavaTickFixture f) {
        return f.player.compensatedEntities.getSelf().getRiding();
    }

    private static void use(JavaTickFixture f, float yaw) {
        var packet = new ServerboundUseItem(Hand.MAIN_HAND, 0, yaw, PITCH);
        f.listener.onUseItem(f.event(ServerboundPackets.USE_ITEM, packet), f.player, packet);
    }

    private static void rot(JavaTickFixture f, float yaw) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, PITCH, f.player.onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    private static void moveBoat(JavaTickFixture f, float boatYaw) {
        moveBoat(f, new Vec3d(SEAT.x, SEAT.y, SEAT.z), boatYaw);
    }

    private static void moveBoat(JavaTickFixture f, Vec3d position, float boatYaw) {
        var packet = new ServerboundMoveVehicle(position, boatYaw, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, packet), f.player, packet);
    }

    // A move_entity carrying only the boat's new yaw.
    private static void turnBoat(JavaTickFixture f, float boatYaw) {
        send(
                f,
                ClientboundPackets.MOVE_ENTITY,
                new ClientboundMoveEntity(
                        BOAT, new EntityDelta.Linear((short) 0, (short) 0, (short) 0), boatYaw, 0, true, false, true));
    }

    private static <T extends ClientboundPacket> void send(JavaTickFixture f, PacketType<T> type, T packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        var replication = f.player.packetEntityReplication;
        if (packet instanceof ClientboundAddEntity add) {
            replication.onAddEntity((PacketSendEvent<ClientboundAddEntity>) event, f.player, add);
        } else {
            replication.onMoveEntity(
                    (PacketSendEvent<ClientboundMoveEntity>) event, f.player, (ClientboundMoveEntity) packet);
        }
        event.tasksAfter().forEach(Runnable::run);
    }

    // Every yaw clampRotation returns for the turned yaws across both clamped arcs, winding times round from the boat.
    private static Set<Float> clampOutputs(float boatYaw, int winding) {
        Set<Float> yaws = new TreeSet<>();
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 4000; i++) {
                float turned = (float) (boatYaw + 360.0 * winding + side * (105.0 + 75.0 * (i + 0.5) / 4000));
                yaws.add(clampRotation(turned, boatYaw));
            }
        }
        return yaws;
    }

    // LinearInterpolationHandler#doInterpolate's yaw: Mth#rotLerp in doubles, cast to float.
    private static float rotLerp(double alpha, float from, float to) {
        double delta = ((double) to - from) % 360.0;
        if (delta >= 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return (float) (from + alpha * delta);
    }

    // AbstractBoat#clampRotation's yaw for a player passenger, in Mth's float operations.
    private static float clampRotation(float yaw, float boatYaw) {
        float delta = wrapDegrees(yaw - boatYaw);
        float target = delta < -105.0F ? -105.0F : Math.min(delta, 105.0F);
        return yaw + target - delta;
    }

    private static boolean withinComparison(float yaw, float boatYaw) {
        float delta = wrapDegrees(yaw - boatYaw);
        return delta >= -105.0F && delta <= 105.0F;
    }

    private static float wrapDegrees(float degrees) {
        float delta = degrees % 360.0F;
        if (delta >= 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        return delta;
    }
}
