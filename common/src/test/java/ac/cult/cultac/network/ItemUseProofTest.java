package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.checks.impl.prediction.ItemUseTickResult;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.NoSlow;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.ItemUseInventory;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemUseProofTest {
    @Test
    void policyReloadRetainsHistoryAndUsesTheNewLimit() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var policy = fixture.player.getItemUseDesync();
            policy.history().add(false);
            policy.history().add(true);
            var config = mock(ac.grim.grimac.api.config.ConfigManager.class);
            when(config.getIntElse("cult.prediction.item-use-desync.history-ticks", 100))
                    .thenReturn(100);
            when(config.getDoubleElse("cult.prediction.item-use-desync.max-percent", 20))
                    .thenReturn(100D);
            fixture.player.reload(config);
            assertEquals(2, policy.history().size());
            assertEquals(1, policy.history().desynced());
            assertFalse(policy.history().wouldExceed(true));
        }
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void eachObservedProtocolAppliesUseMetadataOnlyAfterItsBundledProof() {
        for (var version : ProtocolVersion.values()) {
            try (var fixture = new RecordReceiveFixture(version)) {
                fixture.user.bind(mock(PlatformPlayer.class));
                var player = fixture.player;
                assertTrue(player.usesModernJavaItemUse());
                player.entityID = 7;
                player.getInventory()
                        .inventory
                        .getInventoryStorage()
                        .setItem(Inventory.SLOT_OFFHAND, ItemUtil.modelItems().stack("minecraft:golden_apple", 1));
                var packet = new EntityMetadata(
                        7,
                        List.of(new EntityMetadata.Entry(
                                WatchableIndexUtil.LIVING_ENTITY_FLAGS, (byte) 3, ByteBuffer.allocate(0))));
                var event = new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
                new PacketSelfMetadataListener().onSetEntityData(event, player, packet);
                assertFalse(player.clientItemUse.hasObligation());
                assertEquals(packet, event.getPacket()); // No corrective flag values.
                var ping = (ClientboundPing) event.writesAfter().getLast().packet();
                event.tasksAfter().forEach(Runnable::run);
                assertTrue(player.addTransactionResponse(ping.id()));
                assertTrue(player.clientItemUse.hasObligation());
                assertEquals(Hand.OFF_HAND, player.clientItemUse.hand());
            }
        }
    }

    @Test
    void completionCannotClearMovementUntilTheClientHasAcknowledgedIt() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            fixture.user.bind(mock(PlatformPlayer.class));
            var player = fixture.player;
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
            player.gamemode = GameMode.SURVIVAL;
            player.entityID = 7;
            player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
            var old = ItemUseInventory.prepare(player);
            var packet = new ClientboundEntityEvent(7, (byte) 9);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
            player.packetEntityReplication.onEntityEvent(event, player, packet);
            assertTrue(player.clientItemUse.hasObligation());
            var ping = (ClientboundPing) event.writesAfter().getLast().packet();
            event.tasksAfter().forEach(Runnable::run);
            assertFalse(player.addTransactionResponse(ping.id() + 999));
            assertTrue(player.clientItemUse.hasObligation());
            assertTrue(player.addTransactionResponse(ping.id()));
            assertFalse(player.clientItemUse.hasObligation());
            assertTrue(player.getInventory().getHandItem(Hand.OFF_HAND).isEmpty());
            assertTrue(old.required());
            assertSame(old, ItemUseInventory.prepare(player));
            player.packetStateData.acceptedClientTick++;
            assertFalse(ItemUseInventory.prepare(player).required());
        }
    }

    @Test
    void deterministicNoSlowDoesNotDependOnBackendAuthorityOrADesyncPercentage() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            fixture.user.bind(mock(PlatformPlayer.class));
            var player = fixture.player;
            var platform = mock(PlatformPlayer.class);
            player.platformPlayer = platform;
            when(platform.hasServerAuthority()).thenReturn(false);
            player.gamemode = GameMode.SURVIVAL;
            var snapshot = new ClientItemUseState.Snapshot(1, 0, 1, 0.2F, 0.2F, false, true, true);
            var prediction = evidence(player, new ItemUseTickResult(snapshot, 0.05, 0.05));
            var check = player.checkManager.getListener(NoSlow.class);
            double previous = check.violations;
            check.onPredictionComplete(prediction);
            assertEquals(previous + 1, check.violations);
            player.getItemUseDesync().onPredictionComplete(prediction);
            player.getItemUseDesync().onPlayerTickEnd(null);
            assertEquals(0, player.getItemUseDesync().history().size());
            verify(platform, never()).getItemUseState();
            verify(platform, never()).clearActiveItem();
        }
    }

    @Test
    void desyncAccountingSurvivesReleasesAndDoesNotProduceFlags() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            fixture.user.bind(mock(PlatformPlayer.class));
            var player = spy(fixture.player);
            var setbacks = mock(ac.cult.cultac.manager.player.SetbackTeleportUtil.class);
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            var policy = new ac.cult.cultac.checks.impl.prediction.checks.ItemUseDesync(player);
            policy.reload();
            var check = player.checkManager.getListener(NoSlow.class);
            double before = check.violations;
            for (int tick = 0; tick < 6; tick++) {
                player.packetStateData.acceptedClientTick = tick;
                boolean bad = tick >= 4;
                var state = new ClientItemUseState.Snapshot(tick + 1, tick, 1, 1, 0.2F, true, true, false);
                var prediction = evidence(player, new ItemUseTickResult(state, 0, bad ? 0.05 : 0));
                check.onPredictionComplete(prediction);
                policy.onPredictionComplete(prediction);
                // Extra predictions cannot turn a desynced tick into compliant credit.
                policy.onPredictionComplete(evidence(player, new ItemUseTickResult(state, 0, 0)));
                player.clientItemUse.release();
                policy.onPlayerTickEnd(null);
            }
            assertEquals(6, policy.history().size());
            assertEquals(2, policy.history().desynced());
            assertEquals(before, check.violations);
            verify(setbacks).executeNonSimulatingSetback();
        }
    }

    private static PredictionComplete evidence(ac.cult.cultac.player.CultPlayer player, ItemUseTickResult row) {
        var realities = new ArrayList<PredictionResult>();
        var result =
                new PredictionResult(player, new PredVector(Vec3.ZERO), null, Vec3.ZERO, null, realities, realities);
        result.setItemUseResult(row);
        realities.add(result);
        return new PredictionComplete(result);
    }
}
