package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ClientPacketListener#handleRespawn gives the client a new LocalPlayer, and the server's absolute teleport places it.
 * A relative teleport sent before that placement moves the new LocalPlayer from wherever it starts, which no safe
 * position describes: Cult tracks its answer and takes no safe position from it until the placement.
 */
class RelativeTeleportBeforePlacementTest {
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final Vec3 SPAWN = new Vec3(3.5, FLOOR_Y, 3.5);
    private static final int RELATIVE_ID = 77;

    @Test
    void aRelativeTeleportBeforeTheRespawnPlacementGivesNoSafePosition() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(STANDING, GROUNDED);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            teleports.respawnSent();
            assertNull(teleports.safePosition());

            var relative = new ClientboundPlayerPosition(
                    RELATIVE_ID,
                    new Vec3d(0, 1, 0),
                    new Vec3d(0, 0, 0),
                    0,
                    0,
                    Set.of(Relative.X, Relative.Y, Relative.Z));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, relative, false);
            new PacketServerTeleport().onPlayerPosition(event, f.player, relative);
            event.getTasksAfterSend().forEach(Runnable::run);

            assertNull(
                    teleports.safePosition(), "a relative teleport with nothing to resolve against placed the player");
            assertTrue(
                    teleports.pendingTeleports.stream().anyMatch(teleport -> teleport.getTeleportId() == RELATIVE_ID),
                    "the relative teleport's answer is not tracked");

            // The respawn's placement positions the new LocalPlayer.
            f.playerPosition(SPAWN, Vec3.ZERO, Set.of());
            assertEquals(SPAWN, teleports.safePosition().getPos());
        }
    }
}
