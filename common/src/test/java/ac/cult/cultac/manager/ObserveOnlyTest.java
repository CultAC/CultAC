package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsN;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.ReachCheck;
import ac.cult.cultac.checks.impl.crash.CrashH;
import ac.cult.cultac.checks.impl.misc.GhostBlockMitigation;
import ac.cult.cultac.events.packets.PacketPluginMessage;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.events.packets.patch.ResyncWorldUtil;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import ac.cult.cultac.utils.latency.CompensatedVehicleState;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.lists.CorrectingPlayerInventoryStorage;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

class ObserveOnlyTest {
    private final ConfigManager config = mock(ConfigManager.class);
    private final CultPlayer player = mock(CultPlayer.class);

    ObserveOnlyTest() {
        when(config.getBooleanElse(anyString(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getIntElse(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getDoubleElse(anyString(), anyDouble())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getStringElse(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    void checksNeitherCancelNorSetBack() throws ReflectiveOperationException {
        try (var ignored = installConfig(true)) {
            Check check = probe();
            when(player.getSetbackTeleportUtil()).thenReturn(new SetbackTeleportUtil(player));
            assertFalse(check.shouldModifyPackets());
            assertFalse(check.setbackIfAboveSetbackVL());
            assertFalse(check.executeViolationSetback());
        }
    }

    @Test
    void crashChecksDoNotCancelEither() throws ReflectiveOperationException {
        try (var ignored = installConfig(true)) {
            CrashH crash = new CrashH(player);
            crash.setEnabled(true);
            assertFalse(crash.shouldModifyPackets());
        }
    }

    @Test
    void setbacksReturnBeforeTouchingThePlayer() throws ReflectiveOperationException {
        try (var ignored = installConfig(true)) {
            SetbackTeleportUtil setbacks = new SetbackTeleportUtil(player);
            setbacks.lastKnownGoodPosition = new SetbackPosWithVector(Vec3.ZERO, Vec3.ZERO, 0);
            setbacks.executeForceResync("test");
            setbacks.executeNonSimulatingSetback();
            assertFalse(setbacks.executeViolationSetback());
            assertFalse(setbacks.executeTooHighLatencySetback("test"));
            verifyNoInteractions(player);
        }
    }

    @Test
    void punishmentCommandsDoNotRunButAlertsDo() throws ReflectiveOperationException {
        Check check = probe();
        doReturn(List.of(check)).when(player).getChecks();
        when(config.getBooleanElse("test-mode", false)).thenReturn(true);
        when(config.getStringElse(eq("alerts-format"), anyString())).thenReturn("alert");
        when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List) List.of(new LinkedHashMap<>(
                Map.of("checks", List.of("observeonlyprobe"), "commands", List.of("1:0 [alert]", "1:0 kick probe")))));
        PunishmentManager punishments = new PunishmentManager(player);
        punishments.reload(config);

        GrimPlugin plugin = mock(GrimPlugin.class);
        List<String> fired = new ArrayList<>();
        CultAPI.INSTANCE
                .getEventBus()
                .get(CommandExecuteEvent.class)
                .onCommandExecuteSupplier(plugin, (user, flagged, verbose, command, cancelled) -> {
                    fired.add(command);
                    return cancelled;
                });
        try (var ignored = installConfig(true)) {
            punishments.handleViolation(check);
            punishments.handleAlert(player, "verbose", check);
        } finally {
            CultAPI.INSTANCE.getEventBus().unregisterAllListeners(plugin);
        }

        assertEquals(List.of("alert"), fired);
        verify(player).sendMessage(any(Component.class));
    }

    /** The client confirms a server teleport (transaction 5, id 7) but reports another position past transaction 6. */
    @Test
    void anIgnoredServerTeleportLeavesNoSetback() throws ReflectiveOperationException {
        for (boolean mounted : new boolean[] {false, true}) {
            try (var ignored = installConfig(true)) {
                IgnoredTeleport teleport = ignoreServerTeleport(mounted);
                assertFalse(teleport.setbacks().isPendingSetback(), "mounted=" + mounted);
                assertTrue(teleport.setbacks().pendingTeleports.isEmpty(), "mounted=" + mounted);
                assertFalse(teleport.resent(), "mounted=" + mounted);
                assertEquals(
                        new Vec3(0.5, 64, 0.5),
                        teleport.setbacks().lastKnownGoodPosition.getPos(),
                        "mounted=" + mounted);
            }
            try (var ignored = installConfig(false)) {
                IgnoredTeleport teleport = ignoreServerTeleport(mounted);
                assertTrue(teleport.setbacks().isPendingSetback(), "mounted=" + mounted);
                assertEquals(!mounted, teleport.resent(), "mounted=" + mounted);
            }
        }
    }

    @Test
    void worldResyncIsLeftToTheServer() throws ReflectiveOperationException {
        for (boolean observeOnly : new boolean[] {true, false}) {
            CultPlayer player = mock(CultPlayer.class);
            try (var ignored = installConfig(observeOnly)) {
                ResyncWorldUtil.resyncPositions(player, 0, 64, 0, 1, 66, 1);
            }
            verify(player, times(observeOnly ? 0 : 1)).runSafely(any());
        }
    }

    @Test
    void healthIsNotSpoofed() throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        PacketType<EntityMetadata> type = (PacketType<EntityMetadata>) OfflineCultTestBootstrap.catalog().stream()
                .filter(packet -> packet.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
        for (boolean observeOnly : new boolean[] {true, false}) {
            CultPlayer player = mock(CultPlayer.class);
            player.spoofHealth = true;
            player.compensatedEntities = mock(CompensatedEntities.class);
            User user = user(new EmbeddedChannel());
            EntityMetadata packet = new EntityMetadata(
                    99,
                    List.of(EntityMetadata.Entry.health(
                            WatchableIndexUtil.LIVING_HEALTH,
                            user.getCultConnection().getObservedProtocol(),
                            20.0F)));
            var event = new PacketSendEvent<>(user, ConnectionPhase.PLAY, type, packet, false);
            try (var ignored = installConfig(observeOnly)) {
                new PacketSelfMetadataListener().onSetEntityData(event, player, packet);
            }
            assertEquals(!observeOnly, event.isReplaced());
        }
    }

    @Test
    void placesNextToGhostBlocksAreLeftToTheServer() throws ReflectiveOperationException {
        when(config.getBooleanElse("exploit.allow-building-on-ghostblocks", true))
                .thenReturn(false);
        for (boolean observeOnly : new boolean[] {true, false}) {
            CultPlayer player = mock(CultPlayer.class);
            player.compensatedWorld = mock(CompensatedWorld.class);
            when(player.compensatedWorld.getServerSentBlockStateIdAt(any()))
                    .thenReturn(CompensatedWorld.UNKNOWN_SERVER_STATE);
            BlockPlace place = mock(BlockPlace.class);
            when(place.getPlacedBlockPos()).thenReturn(new BlockPos(0, 64, 0));
            try (var ignored = installConfig(observeOnly)) {
                GhostBlockMitigation mitigation = new GhostBlockMitigation(player);
                mitigation.onReload(config);
                mitigation.onBlockPlace(place);
            }
            verify(place, times(observeOnly ? 0 : 1)).resync();
        }
    }

    @Test
    void anUnansweredPlayerIsLetGo() throws ReflectiveOperationException {
        for (boolean observeOnly : new boolean[] {true, false}) {
            CultPlayer player = mock(CultPlayer.class);
            User user = user(new EmbeddedChannel());
            set(player, "user", user);
            doCallRealMethod().when(player).timedOut();
            try (var ignored = installConfig(observeOnly)) {
                player.timedOut();
            }
            verify(player, times(observeOnly ? 0 : 1)).disconnect(any());
            assertEquals(observeOnly, CultAPI.INSTANCE.getPlayerDataManager().clearExemptions(user));
        }
    }

    @Test
    void aPacketFloodIsNotKicked() throws ReflectiveOperationException {
        for (boolean observeOnly : new boolean[] {true, false}) {
            CultPlayer player = mock(CultPlayer.class);
            set(player, "cancelledPackets", new AtomicInteger());
            set(player, "spamThreshold", 1);
            doCallRealMethod().when(player).onPacketCancel();
            try (var ignored = installConfig(observeOnly)) {
                for (int i = 0; i < 4; i++) player.onPacketCancel();
            }
            verify(player, times(observeOnly ? 0 : 2)).disconnect(any());
        }
    }

    @Test
    void aViaProxyClientIsNotKicked() throws ReflectiveOperationException {
        for (boolean observeOnly : new boolean[] {true, false}) {
            EmbeddedChannel channel = new EmbeddedChannel();
            ServerboundCustomPayload packet = new ServerboundCustomPayload("vv:proxy_details", new byte[0]);
            var event = new PacketReceiveEvent<>(
                    user(channel), ConnectionPhase.PLAY, ServerboundPackets.CUSTOM_PAYLOAD, packet);
            try (var ignored = installConfig(observeOnly)) {
                new PacketPluginMessage().handle(event, packet);
            }
            assertEquals(observeOnly, channel.isOpen());
        }
    }

    @Test
    void theInventoryIsLeftToTheServer() throws ReflectiveOperationException {
        Method sync = CorrectingPlayerInventoryStorage.class.getDeclaredMethod("checkThatServerIsSynced", int.class);
        sync.setAccessible(true);
        for (boolean observeOnly : new boolean[] {true, false}) {
            CultPlayer player = mock(CultPlayer.class);
            CompensatedInventory inventory = mock(CompensatedInventory.class);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.settledServerSlot(36)).thenReturn(OfflineCultTestBootstrap.item("minecraft:stone"));
            CorrectingPlayerInventoryStorage storage = new CorrectingPlayerInventoryStorage(player, 46);
            try (var ignored = installConfig(observeOnly)) {
                assertEquals(true, sync.invoke(storage, 36));
            }
            verify(inventory, times(observeOnly ? 0 : 1)).correctPlayerSlot(36);
        }
    }

    @Test
    void attacksPastTheQueueAreLeftToTheServer() throws ReflectiveOperationException {
        Field queue = ReachCheck.class.getDeclaredField("playerAttackQueue");
        queue.setAccessible(true);
        // {observe-only, cult.nomodifypacket}: the queue cap still cancels for players exempt from cancels.
        for (boolean[] mode : new boolean[][] {{true, false}, {false, false}, {false, true}}) {
            boolean observeOnly = mode[0];
            CultPlayer player = mock(CultPlayer.class);
            player.noModifyPacketPermission = mode[1];
            player.gamemode = GameMode.SURVIVAL;
            when(player.getSetbackTeleportUtil()).thenReturn(mock(SetbackTeleportUtil.class));
            when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_2);
            CompensatedEntities entities = mock(CompensatedEntities.class);
            PacketEntity target = mock(PacketEntity.class);
            set(target, "type", EntityTypeIds.PLAYER);
            set(entities, "entityMap", new Int2ObjectOpenHashMap<>(Map.of(99, target)));
            when(entities.getSelf()).thenReturn(mock(PacketEntitySelf.class));
            player.compensatedEntities = entities;
            player.compensatedWorld = mock(CompensatedWorld.class);
            player.checkManager = mock(CheckManager.class, RETURNS_MOCKS);
            when(player.getInventory()).thenReturn(mock(CompensatedInventory.class));
            ServerboundInteract attack =
                    new ServerboundInteract(99, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            var event = new PacketReceiveEvent<>(
                    user(new EmbeddedChannel()), ConnectionPhase.PLAY, ServerboundPackets.INTERACT, attack);
            try (var ignored = installConfig(observeOnly)) {
                PlayerReach reach = new PlayerReach(player, new ClientVisibilityProcessor(player));
                reach.setEnabled(true);
                @SuppressWarnings("unchecked")
                List<Object> queued = (List<Object>) queue.get(reach);
                for (int i = 0; i < 11; i++) queued.add(null);
                reach.onInteract(event, player, attack);
            }
            assertEquals(!observeOnly, event.isCancelled(), "noModifyPacket=" + mode[1]);
        }
    }

    /** {@code resent}: CultAC queued its own teleport on the connection. */
    private record IgnoredTeleport(SetbackTeleportUtil setbacks, boolean resent) {}

    private static IgnoredTeleport ignoreServerTeleport(boolean mounted) throws ReflectiveOperationException {
        CultPlayer player = mock(CultPlayer.class);
        EmbeddedChannel channel = new EmbeddedChannel();
        set(player, "user", user(channel));
        AtomicInteger received = new AtomicInteger(4);
        set(player, "lastTransactionReceived", received);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_2);
        when(player.canSendPlayPackets()).thenReturn(true);
        CompensatedEntities entities = mock(CompensatedEntities.class);
        CompensatedVehicleState vehicles = new ObjenesisStd().newInstance(CompensatedVehicleState.class);
        if (mounted) vehicles.serverPlayerVehicle = 3;
        set(entities, "vehicles", vehicles);
        when(entities.getSelf()).thenReturn(mock(PacketEntitySelf.class));
        player.compensatedEntities = entities;
        player.checkManager = mock(CheckManager.class);
        when(player.checkManager.getListener(BadPacketsN.class)).thenReturn(mock(BadPacketsN.class));
        SetbackTeleportUtil setbacks = new SetbackTeleportUtil(player);
        when(player.getSetbackTeleportUtil()).thenReturn(setbacks);
        setbacks.lastKnownGoodPosition = new SetbackPosWithVector(Vec3.ZERO, Vec3.ZERO, 0);
        setbacks.hasFullyLoaded = true;
        setbacks.addSentTeleport(new Vec3(10.5, 64, 10.5), 5, new RelativeFlag(0), true, 7);
        received.set(6);
        setbacks.checkTeleportQueue(0.5, 64, 0.5);
        channel.runPendingTasks();
        return new IgnoredTeleport(setbacks, !channel.outboundMessages().isEmpty());
    }

    private static User user(EmbeddedChannel channel) {
        return TestUsers.create(new User.Profile(UUID.randomUUID(), "observer"), channel);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // declared on a superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Check probe() {
        Check check = new Check(
                player,
                CheckInfo.builder()
                        .name("ObserveOnlyProbe")
                        .stableKey("cult.test.observe_only_probe")
                        .setback(1)
                        .build()) {};
        check.setEnabled(true);
        check.violations = 5;
        return check;
    }

    private Restore installConfig(boolean observeOnly) throws ReflectiveOperationException {
        BaseConfigManager manager = new BaseConfigManager() {
            @Override
            public ConfigManager getConfig() {
                return config;
            }

            @Override
            public boolean isObserveOnly() {
                return observeOnly;
            }

            @Override
            public String getDisconnectTimeout() {
                return "timed out";
            }

            @Override
            public String getDisconnectClosed() {
                return "closed";
            }
        };
        Field field = CultAPI.class.getDeclaredField("configManager");
        field.setAccessible(true);
        Object original = field.get(CultAPI.INSTANCE);
        field.set(CultAPI.INSTANCE, manager);
        return () -> {
            try {
                field.set(CultAPI.INSTANCE, original);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        };
    }

    private interface Restore extends AutoCloseable {
        @Override
        void close();
    }
}
