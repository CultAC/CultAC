package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsP;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsWindow;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsH;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsS;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InventoryWindowChecksTest {
    @Test
    void acknowledgedServerOpenReplacesClosedAndPreviousWindowPossibilities() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var state = f.player.openWindow;
            assertFalse(state.mustBeOpen());
            serverOpen(f, 7);
            acknowledge(f, 1);
            assertFalse(state.mustBeOpen());
            assertTrue(state.getPossibilities().stream().anyMatch(w -> w != null && w.id() == 7));
            acknowledge(f, 2);
            assertTrue(state.mustBeOpen());
            serverOpen(f, 8);
            acknowledge(f, 3);
            assertEquals(2, state.getPossibilities().size());
            acknowledge(f, 4);
            assertEquals(8, state.getPossibilities().iterator().next().id());
        }
    }

    @Test
    void clientCloseCannotDiscardAServerWindowItHasNotProcessedYet() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            serverOpen(f, 7);
            acknowledge(f, 2);
            serverOpen(f, 8);
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            assertFalse(f.player.openWindow.mustBeOpen());
            acknowledge(f, 4);
            assertTrue(f.player.openWindow.mustBeOpen());
            assertEquals(
                    8, f.player.openWindow.getPossibilities().iterator().next().id());
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            assertFalse(f.player.openWindow.mustBeOpen());
        }
    }

    @Test
    void serverCloseIsPossibleBeforeItsProofAndCertainAfterward() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            serverOpen(f, 7);
            acknowledge(f, 2);
            var packet = new ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose(7);
            var event = new PacketSendEvent<>(
                    f.user, ConnectionPhase.PLAY, ClientboundPackets.CONTAINER_CLOSE, packet, true);
            f.player.openWindow.onServerWindow(event, 0, null);
            assertEquals(1, event.getWritesBeforeSend().size());
            assertEquals(1, event.getWritesAfterSend().size());
            assertTrue(f.player.openWindow.mustBeOpen());
            acknowledge(f, 3);
            assertFalse(f.player.openWindow.mustBeOpen());
            acknowledge(f, 4);
            assertEquals(1, f.player.openWindow.getPossibilities().size());
            assertTrue(f.player.openWindow.getPossibilities().contains(null));
        }
    }

    @Test
    void closedPlayerInventoryCanOpenThroughAFirstClickButAnotherWindowCannot() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            var state = f.player.openWindow;
            var click = click(0, 36, WindowClickType.PICKUP);
            var event = clickEvent(f, click);
            new PacketPlayerWindow().onContainerClick(event, f.player, click);
            assertTrue(state.mustBeOpen());
            var check = spy(new BadPacketsWindow(f.player));
            doReturn(true).when(check).alert(any(java.util.function.Supplier.class));
            doReturn(true).when(check).shouldModifyPackets();
            check.onContainerClick(event, f.player, click);
            assertFalse(event.isCancelled());
            var wrongClick = click(9, 0, WindowClickType.PICKUP);
            var wrongEvent = clickEvent(f, wrongClick);
            check.onContainerClick(wrongEvent, f.player, wrongClick);
            assertTrue(wrongEvent.isCancelled());
        }
    }

    @Test
    void inventoryInputChecksUseKeysRatherThanMomentum() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            var check = spy(new MultiActionsH(f.player));
            doReturn(true).when(check).flag();
            doReturn(true).when(check).shouldModifyPackets();
            f.player.packetStateData.knownInput = KnownInput.DEFAULT;
            check.onPlayerTickEnd(null);
            verify(check, never()).flag();
            f.player.packetStateData.knownInput = new KnownInput(true, false, false, false, false, false, false);
            check.onPlayerTickEnd(null);
            verify(check).flag();
        }
    }

    @Test
    void aVanillaInventoryDropSwingMustBeFollowedByADropClick() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            var check = spy(new MultiActionsS(f.player));
            doReturn(true).when(check).flag();
            var swing = new PacketReceiveEvent<>(
                    f.user, ConnectionPhase.PLAY, ServerboundPackets.SWING, ServerboundSwing.PUNCH);
            check.onDecodedPacketReceive(swing);
            check.onDecodedPacketReceive(clickEvent(f, click(0, 36, WindowClickType.THROW)));
            verify(check, never()).flag();
            check.onDecodedPacketReceive(swing);
            check.onDecodedPacketReceive(clickEvent(f, click(0, 36, WindowClickType.PICKUP)));
            verify(check).flag();
        }
    }

    @Test
    void rateLimitedClickStillCarriesValidatedClientPrediction() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var inventory = f.player.getInventory();
            var stone = ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item("minecraft:stone");
            inventory.inventory.getInventoryStorage().setItem(36, stone.copy());
            int[] slots = {36, 37, 37, 38};
            PacketReceiveEvent<InventoryClick> last = null;
            for (int i = 0; i < slots.length; i++) {
                boolean pickup = (i & 1) == 0;
                var packet = new InventoryClick(
                        0,
                        0,
                        slots[i],
                        0,
                        WindowClickType.PICKUP,
                        Map.of(slots[i], pickup ? SimItemStack.EMPTY : stone.copy()),
                        pickup ? stone.copy() : SimItemStack.EMPTY);
                last = clickEvent(f, packet);
                inventory.onContainerClick(last, f.player, packet);
                assertEquals(i == 3, last.isCancelled());
            }
            assertEquals(
                    "minecraft:stone",
                    inventory.inventory.getInventoryStorage().getItem(38).itemKey());
            assertTrue(inventory.menu.getCarried().isEmpty());
        }
    }

    @Test
    void cancelledWireClicksResendTheServerViewAndKeepPredictionUntilTheCorrectionProof() throws Exception {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            // No server authority: the correction is built only from packets the server sent.
            var platform = mock(PlatformPlayer.class);
            f.user.bind(platform);
            f.player.platformPlayer = platform;
            var inventory = f.player.getInventory();
            var stone = ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item("minecraft:stone");
            var authoritative = new ArrayList<SimItemStack>();
            for (int i = 0; i < inventory.menu.getSlots().size(); i++) authoritative.add(SimItemStack.EMPTY);
            authoritative.set(36, stone.copy());
            sendContent(f, new InventoryPackets.Content(0, 42, authoritative, SimItemStack.EMPTY));
            acknowledge(f, f.player.lastTransactionSent.get());
            assertEquals(
                    "minecraft:stone",
                    inventory.inventory.getInventoryStorage().getItem(36).itemKey());
            assertEquals(42, inventory.stateID);

            var type = clickEvent(f, click(0, 36, WindowClickType.PICKUP)).getPacketType();
            f.registrar.register(routes ->
                    routes.receive(type, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                        inventory.onContainerClick(event, player, packet);
                        if (event.isCancelled()) inventory.resyncCancelledClick(packet);
                        else inventory.onClickForwarded(packet);
                    }));
            int[] slots = {36, 37, 37, 38, 38, 39};
            for (int i = 0; i < slots.length; i++) {
                boolean pickup = (i & 1) == 0;
                var frame = f.id(type, "container_click");
                Wire.writeVarInt(frame, 0);
                Wire.writeVarInt(frame, 42);
                frame.writeShort(slots[i]).writeByte(0);
                Wire.writeVarInt(frame, WindowClickType.PICKUP.ordinal());
                Wire.writeVarInt(frame, 1);
                frame.writeShort(slots[i]);
                writeClickItem(frame, pickup ? SimItemStack.EMPTY : stone);
                writeClickItem(frame, pickup ? stone : SimItemStack.EMPTY);
                if (i < 3) f.forward(frame);
                else {
                    assertFalse(f.channel.writeInbound(frame));
                    assertNull(f.channel.readInbound());
                    assertEquals(0, frame.refCnt());
                }
            }
            // The client applied the cancelled clicks locally; keep that prediction until the proof.
            assertEquals(
                    "minecraft:stone",
                    inventory.inventory.getInventoryStorage().getItem(39).itemKey());
            assertTrue(inventory.menu.getCarried().isEmpty());

            // The server accepted only the first three clicks: the stone is on its cursor.
            acknowledge(f, f.player.lastTransactionSent.get());
            for (int slot = 36; slot <= 39; slot++)
                assertTrue(
                        inventory.inventory.getInventoryStorage().getItem(slot).isEmpty(), "slot " + slot);
            assertEquals("minecraft:stone", inventory.menu.getCarried().itemKey());
            assertEquals(42, inventory.stateID);
        }
    }

    @Test
    void aCancelledClickNeverResendsSlotsTheServerHasNotDescribed() throws Exception {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            var inventory = f.player.getInventory();
            var stone = ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item("minecraft:stone");
            inventory.inventory.getInventoryStorage().setItem(36, stone.copy());
            int sent = f.player.lastTransactionSent.get();
            inventory.resyncCancelledClick(new InventoryClick(
                    0, 0, 36, 0, WindowClickType.PICKUP, Map.of(36, SimItemStack.EMPTY), stone.copy()));
            assertEquals(sent, f.player.lastTransactionSent.get());
            assertEquals(
                    "minecraft:stone",
                    inventory.inventory.getInventoryStorage().getItem(36).itemKey());
        }
    }

    @Test
    void aRejectedLocalPredictionIsCorrectedFromTheSettledServerView() throws Exception {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            var inventory = f.player.getInventory();
            var stone = ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item("minecraft:stone");
            var authoritative = new ArrayList<SimItemStack>();
            for (int i = 0; i < inventory.menu.getSlots().size(); i++) authoritative.add(SimItemStack.EMPTY);
            var two = stone.copy();
            two.setCount(2);
            authoritative.set(36, two);
            sendContent(f, new InventoryPackets.Content(0, 7, authoritative, SimItemStack.EMPTY));
            acknowledge(f, f.player.lastTransactionSent.get());

            // A local placement consumed one; the server rejected it and never echoes its unchanged slot.
            var one = stone.copy();
            one.setCount(1);
            var storage = (ac.cult.cultac.utils.lists.CorrectingPlayerInventoryStorage)
                    inventory.inventory.getInventoryStorage();
            storage.setItem(36, one);
            var ticks = ac.cult.cultac.CultAPI.INSTANCE.getTickManager();
            int start = ticks.currentTick;
            try {
                storage.tickServerView();
                assertEquals(1, storage.getItem(36).getCount());
                ticks.currentTick = start + 5;
                storage.tickServerView();
                assertEquals(1, storage.getItem(36).getCount());
                acknowledge(f, f.player.lastTransactionSent.get());
                assertEquals(2, storage.getItem(36).getCount());
            } finally {
                ticks.currentTick = start;
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = PlayerAction.class,
            names = {"DROP_ITEM", "DROP_ALL_ITEMS"})
    void aHotbarDropIsNeverResentFromTheServerView(PlayerAction action) throws Exception {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            var inventory = f.player.getInventory();
            var stone = ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item("minecraft:stone");
            var authoritative = new ArrayList<SimItemStack>();
            for (int i = 0; i < inventory.menu.getSlots().size(); i++) authoritative.add(SimItemStack.EMPTY);
            var two = stone.copy();
            two.setCount(2);
            authoritative.set(36, two);
            sendContent(f, new InventoryPackets.Content(0, 7, authoritative, SimItemStack.EMPTY));
            acknowledge(f, f.player.lastTransactionSent.get());

            // ServerPlayer#drop(boolean) records the slot it emptied with setRemoteSlot and sends nothing.
            var drop = new ServerboundPlayerAction(action, new BlockPos(0, 0, 0), Direction.DOWN, 0);
            inventory.onPlayerAction(
                    new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, ServerboundPackets.PLAYER_ACTION, drop),
                    f.player,
                    drop);
            int remaining = action == PlayerAction.DROP_ALL_ITEMS ? 0 : 1;
            var storage = (ac.cult.cultac.utils.lists.CorrectingPlayerInventoryStorage)
                    inventory.inventory.getInventoryStorage();
            var ticks = ac.cult.cultac.CultAPI.INSTANCE.getTickManager();
            int start = ticks.currentTick;
            int sent = f.player.lastTransactionSent.get();
            try {
                ticks.currentTick = start + 5;
                storage.tickServerView();
                assertEquals(sent, f.player.lastTransactionSent.get());
                assertEquals(remaining, storage.getItem(36).getCount());
                assertEquals(remaining, inventory.serverViewSlot(36).getCount());
            } finally {
                ticks.currentTick = start;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void sendContent(RecordReceiveFixture f, InventoryPackets.Content content) {
        var contentType = (PacketType<InventoryPackets.Content>)
                f.registrar.scanner().runtime().typeForRecord(InventoryPackets.Content.class);
        var event = new PacketSendEvent<>(f.user, ConnectionPhase.PLAY, contentType, content, false);
        f.player.getInventory().onContainerSetContent(event, f.player, content);
    }

    private static void writeClickItem(ByteBuf output, SimItemStack item) {
        output.writeBoolean(!item.isEmpty());
        if (item.isEmpty()) return;
        Wire.writeVarInt(
                output,
                ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults().id("minecraft:item", item.itemKey()));
        Wire.writeVarInt(output, item.count());
        Wire.writeVarInt(output, 0); // Added component hashes.
        Wire.writeVarInt(output, 0); // Removed components.
    }

    private static void serverOpen(RecordReceiveFixture f, int id) {
        if (f.user.getPlayer() == null) f.user.bind(mock(PlatformPlayer.class));
        var packet = new ClientboundOpenScreen(id, "minecraft:generic_9x1");
        var event = new PacketSendEvent<>(f.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        f.player.openWindow.onServerWindow(event, id, MenuType.GENERIC_9x1);
    }

    @Test
    void aCarriedItemReleasedWithAGlfwMouseButtonIsAValidClick() throws Exception {
        assertCarriedClickFlags(ProtocolVersion.V26_1, 3, false);
        assertCarriedClickFlags(ProtocolVersion.V26_1, 4, false);
        assertCarriedClickFlags(ProtocolVersion.V26_1, 7, false);
        assertCarriedClickFlags(ProtocolVersion.V26_1, 8, true);
    }

    @Test
    void aCarriedItemReleasedWithAnSdlMouseButtonIsAValidClick() throws Exception {
        assertCarriedClickFlags(ProtocolVersion.V26_3, 4, false);
        assertCarriedClickFlags(ProtocolVersion.V26_3, 5, false);
        assertCarriedClickFlags(ProtocolVersion.V26_3, -1, false);
        assertCarriedClickFlags(ProtocolVersion.V26_3, 127, false);
        assertCarriedClickFlags(ProtocolVersion.V26_3, -128, false);
        assertCarriedClickFlags(ProtocolVersion.V26_3, 3, true);
    }

    private static void assertCarriedClickFlags(ProtocolVersion version, int button, boolean flags) throws Exception {
        VerboseCodecs.ensureRegistered();
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(version)) {
            when(f.connection.platform().getClientProtocol()).thenReturn(version);
            f.player.setExperimentalChecks(true);
            var check = spy(new BadPacketsP(f.player));
            doReturn(true).when(check).alert(any(Supplier.class));
            doReturn(true).when(check).shouldModifyPackets();
            var click = new InventoryClick(0, 0, 36, button, WindowClickType.PICKUP, Map.of(), SimItemStack.EMPTY);
            var event = clickEvent(f, click);
            check.onContainerClick(event, f.player, click);
            assertEquals(flags, event.isCancelled(), version + " button " + button);
        }
    }

    private static void acknowledge(RecordReceiveFixture f, int transaction) {
        f.player.lastTransactionReceived.set(transaction);
        f.player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private static InventoryClick click(int window, int slot, WindowClickType type) {
        return new InventoryClick(window, 0, slot, 0, type, Map.of(), SimItemStack.EMPTY);
    }

    @SuppressWarnings("unchecked")
    private static PacketReceiveEvent<InventoryClick> clickEvent(RecordReceiveFixture f, InventoryClick click) {
        PacketType<InventoryClick> type =
                (PacketType<InventoryClick>) f.registrar.scanner().runtime().typeForRecord(InventoryClick.class);
        return new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, type, click);
    }
}
