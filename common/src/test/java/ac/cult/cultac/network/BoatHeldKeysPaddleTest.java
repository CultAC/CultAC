package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.vehicle.VehicleF;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * AbstractBoat#controlBoat sets the paddles the boat's tick sends from the keys the boat holds, which only
 * LocalPlayer#rideTick's AbstractBoat#setInput writes, after the boat's tick and only while the player controls it.
 * Sequences come from the issue 224 capture, moved next to the fixture's floor.
 */
class BoatHeldKeysPaddleTest {
    private static final int BOAT = 5666;
    private static final int DRIVER = 307;
    private static final Vec3 REST = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

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

    private static int violations(JavaTickFixture f) {
        return (int) f.player.checkManager.getCheck(VehicleF.class).violations;
    }
}
