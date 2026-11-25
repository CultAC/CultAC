package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsD;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Entity#checkInsideBlocks sweeps the box of the player's pose, so a nether portal block that touches only the head
 * starts the PortalProcessor, and LocalPlayer#handlePortalTransitionEffect closes the inventory with a container_close
 * while the movement keys are held.
 */
class MultiActionsDNetherPortalTest {
    private static final Vec3 STANDING = new Vec3(0.4425, JavaTickFixture.FLOOR_Y, 0.61);

    /** The capture: forward and backward held, standing still under a portal block set one block above the feet. */
    @Test
    void theInventoryClosedByAPortalAtHeadHeightDoesNotFlag() throws Exception {
        try (var f = standingUnderPortalAt(JavaTickFixture.FLOOR_Y + 1)) {
            assertEquals(0, closeViolations(f));
        }
    }

    @Test
    void closingWhileMovingUnderAPortalOutOfReachFlags() throws Exception {
        try (var f = standingUnderPortalAt(JavaTickFixture.FLOOR_Y + 2)) {
            assertEquals(1, closeViolations(f));
        }
    }

    private static JavaTickFixture standingUnderPortalAt(int portalY) throws Exception {
        var f = new JavaTickFixture();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, portalY, 0), BlockIds.NETHER_PORTAL.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.player.packetStateData.playerLoadedIntoLevel = true;
        f.place(STANDING, Vec3.ZERO);
        f.tick(STANDING, true);
        f.input(new ServerboundPlayerInput(true, true, false, false, false, false, true));
        for (int tick = 0; tick < 3; tick++) f.tickEnd();
        f.player.openWindow.onClientOpenOrCloseWindow(true);
        return f;
    }

    private static double closeViolations(JavaTickFixture f) {
        var close = new ServerboundContainerClose(0);
        var check = f.player.checkManager.getListener(MultiActionsD.class);
        check.onContainerClose(
                new PacketReceiveEvent<>(
                        f.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.CONTAINER_CLOSE, close),
                f.player,
                close);
        return check.getViolations();
    }
}
