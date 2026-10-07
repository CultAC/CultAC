package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.ItemUseState;
import ac.cult.cultac.utils.math.Vec3;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VelocityStandaloneTest {
    private final Player nativePlayer = mock(Player.class);
    private final CultConnection connection = mock(CultConnection.class);
    private final CultPlayer observed = mock(CultPlayer.class);
    private final ServerConnection server = mock(ServerConnection.class);
    private final VelocityPlayer player = new VelocityPlayer(nativePlayer, mock(VelocitySenders.class));

    @BeforeEach
    void attach() {
        when(nativePlayer.getCurrentServer()).thenReturn(Optional.of(server));
        when(connection.player()).thenReturn(observed);
        when(connection.phase(PacketDirection.CLIENTBOUND)).thenReturn(ConnectionPhase.PLAY);
        player.attach(connection);
        observed.world = "minecraft:overworld";
    }

    @Test
    void bindingDoesNotNeedServerSnapshotsDuringConfiguration() {
        var proxy = mock(ProxyServer.class);
        UUID id = UUID.randomUUID();
        when(nativePlayer.getUniqueId()).thenReturn(id);
        when(nativePlayer.getUsername()).thenReturn("TestPlayer");
        when(nativePlayer.isActive()).thenReturn(true);
        when(proxy.getPlayer(id)).thenReturn(Optional.of(nativePlayer));
        var adapter = new VelocityConnectionAdapter(proxy, nativePlayer, player, ImmediateEventExecutor.INSTANCE);
        var binding = adapter.playerBinding();
        assertSame(player, binding.player());
        assertTrue(binding.isCurrent());
        assertTrue(binding.matches(nativePlayer));
        assertFalse(binding.matches(mock(Player.class)));
        assertEquals(id, adapter.authenticatedProfile().getUUID());
        when(proxy.getPlayer(id)).thenReturn(Optional.of(mock(Player.class)));
        assertFalse(binding.isCurrent());
    }

    @Test
    void playerReadsCompensatedPacketStateAndKeepsYawAndPitchOrder() {
        observed.entityID = 42;
        observed.gamemode = GameMode.ADVENTURE;
        observed.isSneaking = true;
        observed.x = 1.5;
        observed.y = 64;
        observed.z = -3.5;
        observed.yRot = 90;
        observed.xRot = 12;
        when(observed.isDead()).thenReturn(true);
        assertEquals(42, player.getEntityId());
        assertEquals(GameMode.ADVENTURE, player.getGameMode());
        assertTrue(player.isSneaking());
        assertTrue(player.isDead());
        assertEquals(new Vec3(1.5, 64, -3.5), player.getPosition());
        assertEquals(90, player.getLocation().getYaw());
        assertEquals(12, player.getLocation().getPitch());
        assertEquals(0, player.distanceSquared(1.5, 64, -3.5));
    }

    @Test
    void worldsAreScopedToServerConnectionAndDimensionWithoutInventingWorldUUIDs() {
        var original = player.getWorld();
        assertSame(original, player.getWorld());
        assertTrue(original.isLoaded());
        assertEquals("minecraft:overworld", original.getName());
        assertNull(original.getUID());
        observed.world = "minecraft:the_nether";
        assertFalse(original.isLoaded());
        var nether = player.getWorld();
        assertNotSame(original, nether);
        assertTrue(nether.isLoaded());
        when(nativePlayer.getCurrentServer()).thenReturn(Optional.of(mock(ServerConnection.class)));
        assertFalse(nether.isLoaded());
        var switched = player.getWorld();
        assertTrue(switched.isLoaded());
        when(connection.phase(PacketDirection.CLIENTBOUND)).thenReturn(ConnectionPhase.CONFIGURATION);
        assertFalse(switched.isLoaded());
        assertThrows(IllegalStateException.class, () -> switched.getBlockAt(0, 64, 0));
    }

    @Test
    void unsupportedServerActionsNeverPretendToSucceedOrWritePackets() {
        assertFalse(player.hasServerAuthority());
        assertEquals(ItemUseState.NONE, player.getItemUseState());
        assertNull(player.getVehicle());
        assertFalse(player.eject());
        assertFalse(player.teleportAsync(player.getLocation()).join());
        clearInvocations(nativePlayer, connection);
        player.setSneaking(true);
        player.setGameMode(GameMode.SPECTATOR);
        player.updateInventory();
        player.closeInventory();
        player.clearActiveItem();
        player.resendBlocks(0, 0, 0, 16, 256, 16);
        verifyNoInteractions(nativePlayer, connection);
    }

    @Test
    void validationMutationReportsUnsupportedWithoutCallingSuccessCallback() {
        var callback = mock(Runnable.class);
        var platform = new VelocityServer(mock(ProxyServer.class), mock(VelocitySenders.class));
        assertThrows(
                UnsupportedOperationException.class,
                () -> platform.applyValidationBlock(player, 0, 64, 0, "minecraft:stone", false, callback));
        verifyNoInteractions(callback);
        assertTrue(Double.isNaN(platform.getTPS()));
    }
}
