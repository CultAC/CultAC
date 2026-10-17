package ac.cult.cultac.network;

import static ac.cult.cultac.network.ItemUseTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.ItemUseInventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.CompensatedCooldown;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemUseLifecycleTest {
    private static final List<String> BOUNDARIES = List.of("configuration", "login", "configuration+login");

    @Test
    void serverSwitchDropsTheOldUse() {
        for (String boundary : List.of("login", "configuration+login")) {
            try (var fixture = fixture()) {
                var player = survival(fixture);
                var shield = shield();
                setOffhand(player, shield);
                player.clientItemUse.start(Hand.OFF_HAND, shield, ClientItemUseState.duration(shield), true);
                assertTrue(ItemUseInventory.prepare(player).definitelyUsing(), boundary);
                startServerUse(player);
                player.packetStateData.acceptedClientTick++;

                cross(player, boundary);
                // A replacement backend sends the same kit; no RELEASE is ever sent for the previous use.
                setOffhand(player, shield());
                assertFalse(walkingFlagsNoSlow(player), boundary);
                assertFalse(player.clientItemUse.hasObligation(), boundary);
                assertFalse(player.itemUseBurst.tracking(), boundary);
            }
        }
    }

    @Test
    void configurationEndsTheServerUseButNotTheLocalUse() {
        try (var fixture = fixture()) {
            var player = survival(fixture);
            var shield = shield();
            setOffhand(player, shield);
            player.clientItemUse.start(Hand.OFF_HAND, shield, ClientItemUseState.duration(shield), true);
            startServerUse(player);

            cross(player, "configuration");
            assertTrue(player.clientItemUse.definitelyUsing());
            assertTrue(player.clientItemUse.hasObligation());
            assertFalse(player.itemUseBurst.tracking());

            cross(player, "login");
            assertFalse(player.clientItemUse.definitelyUsing());
            assertFalse(player.clientItemUse.hasObligation());
        }
    }

    @Test
    void proofsBeforeTheBoundaryCannotRestoreTheUse() {
        for (String boundary : BOUNDARIES) {
            try (var fixture = fixture()) {
                var player = fixture.player;
                player.entityID = 7;
                setOffhand(player, shield());
                int ping = sendActiveOffhandMetadata(player, 7);
                cross(player, boundary);
                assertTrue(player.addTransactionResponse(ping), boundary);
                // The previous LocalPlayer handles its queued proofs until the configuration acknowledgement.
                boolean newLocalPlayer = boundary.endsWith("login");
                assertEquals(!newLocalPlayer, player.clientItemUse.hasObligation(), boundary);
                assertEquals(!newLocalPlayer, player.clientItemUse.definitelyUsing(), boundary);
            }
        }
    }

    @Test
    void useProofsSentAfterTheBoundaryStillApply() {
        for (String boundary : BOUNDARIES) {
            try (var fixture = fixture()) {
                var player = fixture.player;
                player.entityID = 7;
                setOffhand(player, shield());
                cross(player, boundary);
                int ping = sendActiveOffhandMetadata(player, player.entityID);
                assertTrue(player.addTransactionResponse(ping), boundary);
                assertTrue(player.clientItemUse.hasObligation(), boundary);
                assertEquals(Hand.OFF_HAND, player.clientItemUse.hand(), boundary);
            }
        }
    }

    @Test
    void aUseProofSentBeforeTheLoginReceiptAppliesToTheNewPlayer() {
        for (boolean newUse : new boolean[] {false, true}) {
            try (var fixture = fixture()) {
                var player = survival(fixture);
                setOffhand(player, shield());
                sendActiveOffhandMetadata(player, 7);
                int old = player.lastTransactionSent.get();
                int receipt = login(player, GameMode.SURVIVAL);
                assertTrue(old < receipt);
                if (newUse) {
                    int ping = sendActiveOffhandMetadata(player, player.entityID);
                    assertTrue(receipt < player.lastTransactionSent.get());
                    // One pong answers the old proof, the LOGIN receipt and the new proof, in that order.
                    assertTrue(player.addTransactionResponse(ping));
                } else {
                    acknowledge(player, receipt);
                }
                assertEquals(newUse, player.clientItemUse.hasObligation(), "new use " + newUse);
                assertEquals(newUse, player.clientItemUse.definitelyUsing(), "new use " + newUse);
                if (newUse) assertEquals(Hand.OFF_HAND, player.clientItemUse.hand());
            }
        }
    }

    @Test
    void onlyALoginClearsPossibleCompletionCooldownsAndFullFood() {
        for (String boundary : BOUNDARIES) {
            try (var fixture = fixture()) {
                var player = fixture.player;
                player.entityID = 7;
                var shield = shield();
                setOffhand(player, shield);
                var cooldowns = player.checkManager.getCompensatedCooldown();
                cooldowns.addPossibleCooldown(CompensatedCooldown.group(shield), 100);
                player.clientItemUse.markPossiblyFull();
                assertTrue(ItemUseInventory.possiblyRefused(player, Hand.OFF_HAND), boundary);

                cross(player, boundary);
                boolean newLocalPlayer = boundary.endsWith("login");
                assertEquals(!newLocalPlayer, cooldowns.possiblyHasItem(shield), boundary);
                assertEquals(!newLocalPlayer, player.clientItemUse.possiblyFull(), boundary);
                assertEquals(!newLocalPlayer, ItemUseInventory.possiblyRefused(player, Hand.OFF_HAND), boundary);
            }
        }
    }

    @Test
    void oldPlayerUseInConfigurationKeepsPossibleCooldown() {
        try (var fixture = fixture()) {
            var player = survival(fixture);
            var shield = shield();
            setOffhand(player, shield);
            var cooldowns = player.checkManager.getCompensatedCooldown();
            cooldowns.addPossibleCooldown(CompensatedCooldown.group(shield), 100);

            cross(player, "configuration");
            useOffhand(player, 1);
            assertFalse(walkingFlagsNoSlow(player));

            cross(player, "login");
            assertFalse(cooldowns.possiblyHasItem(shield));
        }
    }

    @Test
    void aNewLocalPlayerStartsWithFullFood() {
        try (var fixture = fixture()) {
            var player = survival(fixture);
            player.food = 10;
            setOffhand(player, ItemUtil.modelItems().stack("minecraft:bread", 2));

            cross(player, "login");
            assertEquals(20, player.food);
            useOffhand(player, 1);
            assertFalse(player.isClientUsingItem());
            assertFalse(walkingFlagsNoSlow(player));
        }
    }

    private static CultPlayer survival(RecordReceiveFixture fixture) {
        var player = fixture.player;
        player.gamemode = GameMode.SURVIVAL;
        player.entityID = 7;
        return player;
    }

    private static void cross(CultPlayer player, String boundary) {
        if (boundary.startsWith("configuration")) {
            var packet = new Opaque(ClientboundPackets.START_CONFIGURATION);
            new PacketServerRegistries()
                    .onStartConfiguration(
                            sendEvent(player, ClientboundPackets.START_CONFIGURATION, packet), player, packet);
        }
        // The ping after LOGIN proves the client replaced its LocalPlayer.
        if (boundary.endsWith("login")) acknowledge(player, login(player, GameMode.SURVIVAL));
    }

    private static int sendActiveOffhandMetadata(CultPlayer player, int entityId) {
        var packet = new EntityMetadata(
                entityId,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.LIVING_ENTITY_FLAGS, (byte) 3, ByteBuffer.allocate(0))));
        var event = sendEvent(player, metadataType(), packet);
        new PacketSelfMetadataListener().onSetEntityData(event, player, packet);
        var ping = (ClientboundPing) event.writesAfter().getLast().packet();
        event.tasksAfter().forEach(Runnable::run);
        return ping.id();
    }

    private static void startServerUse(CultPlayer player) {
        player.itemUseBurst.onUseItem(null, player, new ServerboundUseItem(Hand.OFF_HAND, 1, 0, 0));
        player.itemUseBurst.serverFlags((byte) 3, null);
        assertTrue(player.itemUseBurst.tracking());
    }

    private static SimItemStack shield() {
        return ItemUtil.modelItems().stack("minecraft:shield", 1);
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
