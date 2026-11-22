package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.vehicle.VehicleF;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * AbstractBoat#controlBoat sets the paddles the boat's tick sends from the keys the boat holds, which only
 * LocalPlayer#rideTick's AbstractBoat#setInput writes, after the boat's tick and only while the player controls it.
 * Sequences come from the issue 223 and 224 captures, moved next to the fixture's floor.
 */
class BoatHeldKeysPaddleTest {
    private static final int BOAT = 5666;
    private static final int DRIVER = 307;
    private static final int NETHER_BOAT = 4333;
    private static final Vec3 REST = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 SEAT = REST.add(0, -0.4125, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final int KEEP_ALL =
            ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA;

    /** Issue 224: the boat keeps the keys held as the player left it, and paddles by them once it drives again. */
    @Test
    void aPassengerBecomingTheDriverPaddlesByTheKeysOfItsLastDrive() throws Exception {
        try (var f = fixture()) {
            driveThenRideBehind(f, FORWARD);
            paddle(f, true);
            assertEquals(0, violations(f));
        }
    }

    @Test
    void aPassengerBecomingTheDriverCannotPaddleByKeysTheBoatDoesNotHold() throws Exception {
        try (var f = fixture()) {
            driveThenRideBehind(f, NONE);
            paddle(f, true);
            assertEquals(1, violations(f));
        }
    }

    /**
     * Issue 223: the player holds forward through a portal. The new LocalPlayer's KeyboardInput gives the boat's copy
     * no keys until the first loaded tick's aiStep, which reads the held forward again without a player_input.
     */
    @Test
    void aBoatRiddenThroughAPortalHoldsNoKeysUntilTheNewPlayerTicks() throws Exception {
        try (var f = fixture()) {
            f.place(REST, Vec3.ZERO);
            f.input(FORWARD);
            DeadPlayerSetbackTest.respawn(f, KEEP_ALL, "minecraft:the_nether");
            f.pongThrough(f.player.lastTransactionSent.get());
            f.playerPosition(SEAT, Vec3.ZERO, Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z));
            JavaTickFixture.floor(f.player);
            f.player.compensatedEntities.addEntity(NETHER_BOAT, EntityTypeIds.OAK_BOAT, REST, 0, 0, 0);
            f.setPassengers(NETHER_BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.pongThrough(f.player.sendTrailingTransaction());
            PacketEntity boat = f.player.compensatedEntities.getEntity(NETHER_BOAT);

            f.tickEnd();
            for (int tick = 0; tick < 6; tick++) {
                paddle(f, false);
                f.tickEnd();
            }
            assertEquals(KnownInput.DEFAULT, boat.boatInput);

            f.listener.onPlayerLoaded(
                    f.event(ServerboundPackets.PLAYER_LOADED, ServerboundPackets.PLAYER_LOADED.opaqueValue()),
                    f.player,
                    ServerboundPackets.PLAYER_LOADED.opaqueValue());
            paddle(f, false);
            assertVehicleExact(rideTick(f), 0);
            paddle(f, true);
            assertEquals(0, violations(f));
            assertEquals(new KnownInput(true, false, false, false, false, false, false), boat.boatInput);
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        var f = new JavaTickFixture();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.player.setExperimentalChecks(true);
        return f;
    }

    /**
     * The player drives with {@code held}, leaves still holding it and releases it; another player drives, the player
     * sits behind it, and the driver leaves.
     */
    private static void driveThenRideBehind(JavaTickFixture f, ServerboundPlayerInput held) {
        f.place(REST.add(1.5, 0, 1.5), Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, REST, 0, 0, 0);
        f.addRemotePlayer(DRIVER, REST, GameMode.SURVIVAL);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(held);
        for (int tick = 0; tick < 3; tick++) {
            paddle(f, tick > 0 && held.forward());
            rot(f);
            f.tickEnd();
        }

        f.setPassengers(BOAT, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(NONE);
        f.move(REST.add(1.5, 0, 1.5), true);
        f.tickEnd();

        f.setPassengers(BOAT, List.of(DRIVER));
        f.setPassengers(BOAT, List.of(DRIVER, f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        for (int tick = 0; tick < 3; tick++) f.rideTick();

        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void paddle(JavaTickFixture f, boolean paddling) {
        var paddle = new ServerboundPaddleBoat(paddling, paddling);
        CheckManagerListener.receiveGeneric(f.event(ServerboundPackets.PADDLE_BOAT, paddle), f.player);
    }

    private static void rot(JavaTickFixture f) {
        var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
    }

    /** LocalPlayer#sendChanges: the passenger's Rot, then the boat's MoveVehicle at rest, and the tick end. */
    private static PredictionResult rideTick(JavaTickFixture f) {
        rot(f);
        f.moveVehicle(REST, 0, 0, true);
        f.tickEnd();
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    private static int violations(JavaTickFixture f) {
        return (int) f.player.checkManager.getCheck(VehicleF.class).violations;
    }
}
