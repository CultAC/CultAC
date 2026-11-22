package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsR;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * LocalPlayer#handlePortalTransitionEffect closes the screen only for a nether PortalProcessor that the last tick's
 * Entity#setAsInsidePortal marked inside. A running portal cooldown keeps it from starting, and a spectator's
 * Entity#isAffectedByBlocks keeps NetherPortalBlock#entityInside from running at all.
 */
class MultiActionsRNetherPortalTest {
    private static final Vec3 IN_PORTAL = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);

    /** The capture: a player standing still in the arrival portal, clicking in the inventory for seconds. */
    @Test
    void clicksInTheArrivalPortalDuringThePortalCooldownDoNotFlag() throws Exception {
        try (var f = respawnedInPortal(GameMode.SURVIVAL, 10)) {
            f.tick(IN_PORTAL, true);
            for (int tick = 0; tick < 100; tick++) f.tickEnd();
            assertEquals(0, clickViolations(f));
        }
    }

    @Test
    void clicksInsideANetherPortalWithoutACooldownFlagFromTheSecondTick() throws Exception {
        try (var f = respawnedInPortal(GameMode.SURVIVAL, 0)) {
            f.tick(IN_PORTAL, true);
            assertEquals(0, clickViolations(f));
            f.tickEnd();
            assertEquals(1, clickViolations(f));
        }
    }

    @Test
    void aSpectatorClickingInsideANetherPortalDoesNotFlag() throws Exception {
        try (var f = respawnedInPortal(GameMode.SPECTATOR, 0)) {
            f.tick(IN_PORTAL, true);
            for (int tick = 0; tick < 3; tick++) f.tickEnd();
            assertEquals(0, clickViolations(f));
        }
    }

    /**
     * Entity#checkInsideBlocks visits the blocks of the player's box deflated by 1.0E-5, so a box whose face lies on
     * the portal column's face visits no portal block.
     */
    @Test
    void clicksWithTheBoxAgainstThePortalFaceDoNotFlag() throws Exception {
        try (var f = standingBesidePortalColumn(1.0 - 0.3F)) {
            assertEquals(0, clickViolations(f));
        }
    }

    /** NetherPortalBlock keeps the full-block entity-inside shape: a box inside the column's block meets it. */
    @Test
    void clicksWithTheBoxInsideThePortalBlockFlag() throws Exception {
        try (var f = standingBesidePortalColumn(1.01 - 0.3F)) {
            assertEquals(1, clickViolations(f));
        }
    }

    /** A survivor standing at {@code x} beside a nether portal column at block x 1, two client ticks later. */
    private static JavaTickFixture standingBesidePortalColumn(double x) throws Exception {
        var f = new JavaTickFixture();
        f.player.setExperimentalChecks(true);
        for (int y = JavaTickFixture.FLOOR_Y; y < JavaTickFixture.FLOOR_Y + 3; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(1, y, 0), BlockIds.NETHER_PORTAL.defaultState());
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.player.packetStateData.playerLoadedIntoLevel = true;
        Vec3 standing = new Vec3(x, JavaTickFixture.FLOOR_Y, 0.5);
        f.place(standing, Vec3.ZERO);
        f.tick(standing, true);
        f.tickEnd();
        f.player.openWindow.onClientOpenOrCloseWindow(true);
        return f;
    }

    /** ServerPlayer#teleport's same-level respawn carrying {@code portalCooldown}, placed inside a portal. */
    private static JavaTickFixture respawnedInPortal(GameMode mode, int portalCooldown) throws Exception {
        var f = new JavaTickFixture();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.player.setExperimentalChecks(true);
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld"),
                f.player.compensatedWorld.getLastClientboundDimension().dimension(),
                mode,
                63,
                0,
                portalCooldown);
        var packet = new ClientboundRespawn(spawnInfo, (byte) 3);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        JavaTickFixture.floor(f.player);
        for (int y = JavaTickFixture.FLOOR_Y; y < JavaTickFixture.FLOOR_Y + 3; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.NETHER_PORTAL.defaultState());
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.playerPosition(IN_PORTAL, Vec3.ZERO, Set.of());
        f.player.packetStateData.playerLoadedIntoLevel = true;
        f.place(IN_PORTAL, Vec3.ZERO);
        f.player.openWindow.onClientOpenOrCloseWindow(true);
        return f;
    }

    /** A window-0 click with the inventory open; the check's violations after it. */
    @SuppressWarnings("unchecked")
    private static double clickViolations(JavaTickFixture f) {
        var click = new InventoryClick(0, 0, 20, 0, WindowClickType.PICKUP, Map.of(), SimItemStack.EMPTY);
        var type = (PacketType<InventoryClick>)
                f.fixture.registrar.scanner().runtime().typeForRecord(InventoryClick.class);
        var check = f.player.checkManager.getListener(MultiActionsR.class);
        check.onAction(new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, click), f.player, click);
        f.player.openWindow.onClientOpenOrCloseWindow(true);
        return check.getViolations();
    }
}
