package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsG;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsM;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.Test;

public final class PacketPlayerRespawnLifecycleTest {
    @Test
    public void configurationReplacementWaitsForLoginReceiptAndPreservesAccounting() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            player.packetStateData.javaLocalPlayerPresent = true;
            player.onGround = player.lastOnGround = player.packetStateData.packetPlayerOnGround = true;
            player.isSneaking = player.isGliding = player.isInBed = true;
            player.clientItemUse.start(
                    ac.cult.cultac.protocol.value.Hand.MAIN_HAND,
                    OfflineCultTestBootstrap.item("minecraft:shield"),
                    72000,
                    true);
            player.packetStateData.setSlowedByUsingItem(true);
            var history = player.getItemUseDesync().history();
            history.add(true);
            history.add(false);
            Field balanceField = player.itemUseBurst.getClass().getDeclaredField("balance");
            balanceField.setAccessible(true);
            var balance = (ac.cult.cultac.utils.data.ItemUseBurstBalance) balanceField.get(player.itemUseBurst);
            assertFalse(balance.add(5));
            Field viewField = player.itemUseBurst.getClass().getDeclaredField("view");
            viewField.setAccessible(true);
            var view = (ac.cult.cultac.utils.data.ServerItemUseView) viewField.get(player.itemUseBurst);
            long start = System.nanoTime();
            view.use(false, 1, start, java.util.List.of(OfflineCultTestBootstrap.item("minecraft:shield")));
            view.serverFlags(true, false, 1, start);
            assertTrue(view.tracking());
            player.compensatedEntities.entityMap.put(
                    99, mock(ac.cult.cultac.utils.data.packetentity.PacketEntity.class));
            player.compensatedEntities.serverPositionsMap.put(
                    99,
                    new ac.cult.cultac.utils.data.TrackerData(
                            0, 64, 0, 0, 0, ac.cult.blocksim.entity.EntityTypeIds.PLAYER, 0));

            var type = ClientboundPackets.FINISH_CONFIGURATION;
            var terminal = new ac.cult.cultac.protocol.packet.Opaque(type);
            var finish = new PacketSendEvent<>(player.user, ConnectionPhase.CONFIGURATION, type, terminal, false);
            var registries = new ac.cult.cultac.events.packets.PacketServerRegistries();
            registries.onFinishConfiguration(finish, player, terminal);
            assertTrue(
                    "Unwritten server packets cannot authorize replacement",
                    player.packetStateData.javaLocalPlayerPresent);
            finish.tasksAfter().forEach(Runnable::run);
            assertFalse(player.packetStateData.javaLocalPlayerPresent);

            var login = loginPacket(true);
            var loginEvent = sendEvent(player, ClientboundPackets.LOGIN, login);
            new PacketPlayerRespawn().onLogin(loginEvent, player, login);
            assertTrue(player.compensatedEntities.serverPositionsMap.isEmpty());
            assertTrue(
                    "Old client entities remain until receipt", player.compensatedEntities.entityMap.containsKey(99));
            assertTrue("LOGIN send is not proof of receipt", player.onGround);
            assertTrue(player.clientItemUse.definitelyUsing());
            assertTrue("The old server use remains until LOGIN is written", view.tracking());
            loginEvent.tasksAfter().forEach(Runnable::run);
            assertFalse("LOGIN send replaces the server-view player", view.tracking());
            player.lastTransactionReceived.set(1);
            player.latencyUtils.handleNettySyncTransaction(1);
            assertFalse(player.onGround);
            assertFalse(player.lastOnGround);
            assertFalse(player.packetStateData.packetPlayerOnGround);
            assertFalse(player.isSneaking);
            assertFalse(player.isGliding);
            assertFalse(player.isInBed);
            assertFalse(player.clientItemUse.definitelyUsing());
            assertFalse(player.clientItemUse.hasObligation());
            assertFalse("The new player cannot inherit the old server use", view.tracking());
            assertFalse(player.packetStateData.isSlowedByUsingItem());
            assertTrue(player.compensatedEntities.entityMap.isEmpty());
            assertEquals(2, history.size());
            assertEquals(1, history.desynced());
            assertEquals(5, balance.balance(), 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void newServerUseSentAfterLoginSurvivesLoginReceipt() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            Field viewField = player.itemUseBurst.getClass().getDeclaredField("view");
            viewField.setAccessible(true);
            var view = (ac.cult.cultac.utils.data.ServerItemUseView) viewField.get(player.itemUseBurst);
            var login = loginPacket(true);
            var event = sendEvent(player, ClientboundPackets.LOGIN, login);
            new PacketPlayerRespawn().onLogin(event, player, login);
            event.tasksAfter().forEach(Runnable::run);
            long start = System.nanoTime();
            view.use(false, 1, start, java.util.List.of(OfflineCultTestBootstrap.item("minecraft:shield")));
            view.serverFlags(true, false, 2, start);
            assertTrue(view.tracking());
            player.lastTransactionReceived.set(1);
            player.latencyUtils.handleNettySyncTransaction(1);
            assertTrue("A new server use is ordered after LOGIN", view.tracking());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void clientConfigurationAcknowledgementsCannotAuthorizeReplacement() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            player.packetStateData.javaLocalPlayerPresent = true;
            var connection = player.user.getCultConnection();
            for (int i = 0; i < 3; i++) {
                connection.forwarded(
                        ac.cult.cultac.protocol.packet.ServerboundPackets.CONFIGURATION_ACKNOWLEDGED, null);
                connection.forwarded(ac.cult.cultac.protocol.packet.ServerboundPackets.FINISH_CONFIGURATION, null);
            }
            assertTrue(player.packetStateData.javaLocalPlayerPresent);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void loginSeedsClientVisibleDeathScreenOption() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            PacketPlayerRespawn listener = new PacketPlayerRespawn();
            BadPacketsM badPacketsM = player.checkManager.getListener(BadPacketsM.class);

            ClientboundLogin hiddenDeathScreen = loginPacket(false);
            listener.onLogin(sendEvent(player, ClientboundPackets.LOGIN, hiddenDeathScreen), player, hiddenDeathScreen);
            assertFalse(player.packetStateData.showsDeathScreen);
            badPacketsM.onDeath();
            assertFalse(booleanField(badPacketsM, "menu"));

            ClientboundLogin visibleDeathScreen = loginPacket(true);
            listener.onLogin(
                    sendEvent(player, ClientboundPackets.LOGIN, visibleDeathScreen), player, visibleDeathScreen);
            assertTrue(player.packetStateData.showsDeathScreen);
            badPacketsM.onDeath();
            assertTrue(booleanField(badPacketsM, "menu"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void repeatedLethalHealthPacketsReopenBadPacketsMDeathState() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            PacketPlayerRespawn listener = new PacketPlayerRespawn();
            ClientboundSetHealth lethal = new ClientboundSetHealth(0.0F, 20, 5.0F);

            listener.onSetHealth(sendEvent(player, ClientboundPackets.SET_HEALTH, lethal), player, lethal);
            assertTrue(player.compensatedEntities.getSelf().isDead);
            assertTrue(booleanField(player.checkManager.getListener(BadPacketsM.class), "menu"));

            player.checkManager.getListener(BadPacketsM.class).onRespawn();
            assertFalse(booleanField(player.checkManager.getListener(BadPacketsM.class), "menu"));

            // The native server is newer than 1.9, so the old PacketEvents listener did not
            // suppress an identical health packet. It must reapply death-screen state.
            listener.onSetHealth(sendEvent(player, ClientboundPackets.SET_HEALTH, lethal), player, lethal);
            assertTrue(booleanField(player.checkManager.getListener(BadPacketsM.class), "menu"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void respawnCallbacksRunOnlyWhenTheRespawnTransactionIsAcknowledged() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            PacketPlayerRespawn listener = new PacketPlayerRespawn();
            BadPacketsM badPacketsM = player.checkManager.getListener(BadPacketsM.class);
            BadPacketsG badPacketsG = player.checkManager.getListener(BadPacketsG.class);
            badPacketsM.onDeath();

            ClientboundRespawn respawn = respawnPacket();
            listener.onRespawn(sendEvent(player, ClientboundPackets.RESPAWN, respawn), player, respawn);

            assertTrue(booleanField(badPacketsM, "menu"));
            assertFalse(booleanField(badPacketsG, "respawn"));

            player.lastTransactionReceived.set(1);
            player.latencyUtils.handleNettySyncTransaction(1);

            assertFalse(booleanField(badPacketsM, "menu"));
            assertTrue(booleanField(badPacketsG, "respawn"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void seaLevelAndBiomeSeedChangeOnlyWhenAnAcknowledgedRespawnCreatesANewClientLevel() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        for (boolean worldChange : new boolean[] {false, true}) {
            CultPlayer player = offlineJavaPlayer();
            try {
                var listener = new PacketPlayerRespawn();
                var initial = respawnPacket().spawnInfo();
                var login = new ClientboundLogin(
                        1,
                        true,
                        new PlayerSpawnInfo(initial.dimensionTypeId(), initial.dimension(), GameMode.SURVIVAL, 81, 55));
                listener.onLogin(sendEvent(player, ClientboundPackets.LOGIN, login), player, login);
                assertEquals(81, player.compensatedWorld.clientSeaLevel());
                assertEquals(55L, player.compensatedWorld.clientBiomeZoomSeed());
                int dimensionType = worldChange
                        ? ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_nether")
                        : initial.dimensionTypeId();
                var respawn = new ClientboundRespawn(
                        new PlayerSpawnInfo(
                                dimensionType,
                                worldChange ? "minecraft:the_nether" : initial.dimension(),
                                GameMode.SURVIVAL,
                                19,
                                -99),
                        (byte) 0);
                listener.onRespawn(sendEvent(player, ClientboundPackets.RESPAWN, respawn), player, respawn);
                assertEquals(81, player.compensatedWorld.clientSeaLevel());
                assertEquals(55L, player.compensatedWorld.clientBiomeZoomSeed());
                player.lastTransactionReceived.set(1);
                player.latencyUtils.handleNettySyncTransaction(1);
                assertEquals(worldChange ? 19 : 81, player.compensatedWorld.clientSeaLevel());
                assertEquals(worldChange ? -99L : 55L, player.compensatedWorld.clientBiomeZoomSeed());
            } finally {
                OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
            }
        }
    }

    private static ClientboundRespawn respawnPacket() {
        int id = ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld");
        return new ClientboundRespawn(new PlayerSpawnInfo(id, "minecraft:overworld", GameMode.SURVIVAL), (byte) 0);
    }

    private static ClientboundLogin loginPacket(boolean showDeathScreen) {
        return new ClientboundLogin(1, showDeathScreen, respawnPacket().spawnInfo());
    }

    private static <R extends ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket>
            PacketSendEvent<R> sendEvent(CultPlayer player, PacketType<R> type, R packet) {
        return new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, type, packet, false);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static CultPlayer offlineJavaPlayer() {
        UUID playerId = UUID.fromString("9c5e440b-265d-435f-98f1-1f539659c002");
        var platform = mock(ac.cult.cultac.network.PlatformConnection.class);
        when(platform.initialWorldData())
                .thenReturn(ac.cult.cultac.utils.latency.ClientWorldRegistries.modelDefaults());
        var connection = new ac.cult.cultac.network.CultConnection(
                platform,
                new EmbeddedChannel(),
                ac.cult.cultac.CultAPI.INSTANCE.getNetworkManager().dispatcher(),
                ignored -> null);
        for (var direction : ac.cult.cultac.protocol.PacketDirection.values())
            connection.phase(direction, ConnectionPhase.PLAY);
        User user = new User(new User.Profile(playerId, ".Respawn_Test"), connection);
        return new CultPlayer(user);
    }
}
