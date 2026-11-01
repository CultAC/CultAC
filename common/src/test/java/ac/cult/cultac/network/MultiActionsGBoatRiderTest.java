package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsG;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#rideTick sets handsBusy from the raw movement keys only when Entity#getControlledVehicle is an
 * AbstractBoat, and AbstractBoat#getControllingPassenger is its first passenger.
 */
class MultiActionsGBoatRiderTest {
    private static final int BOAT = 100;
    private static final int OTHER_RIDER = 101;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_AND_BACK =
            new ServerboundPlayerInput(true, true, false, false, false, false, false);
    private static final ServerboundPlayerInput LEFT_AND_RIGHT =
            new ServerboundPlayerInput(false, false, true, true, false, false, false);

    @Test
    void theBoatsControllerHoldingAMovementKeyHasBusyHands() throws Exception {
        assertTrue(handsBusy(true, FORWARD));
    }

    @Test
    void aPassengerBehindTheControllerHoldingAMovementKeyHasFreeHands() throws Exception {
        assertFalse(handsBusy(false, FORWARD));
    }

    @Test
    void theBoatsControllerHoldingOpposingMovementKeysHasBusyHands() throws Exception {
        assertTrue(handsBusy(true, FORWARD_AND_BACK));
        assertTrue(handsBusy(true, LEFT_AND_RIGHT));
    }

    private static boolean handsBusy(boolean controls, ServerboundPlayerInput input) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(SEAT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
            if (controls) {
                f.setPassengers(BOAT, List.of(f.player.entityID));
            } else {
                f.player.compensatedEntities.addEntity(OTHER_RIDER, EntityTypeIds.ZOMBIE, SEAT, 0, 0, 0);
                f.setPassengers(BOAT, List.of(OTHER_RIDER, f.player.entityID));
            }
            f.pongThrough(f.player.lastTransactionSent.get());
            var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, f.player.onGround, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            f.tickEnd();
            f.input(input);
            return f.player.checkManager.getCheck(MultiActionsG.class).isCheckActive();
        }
    }
}
