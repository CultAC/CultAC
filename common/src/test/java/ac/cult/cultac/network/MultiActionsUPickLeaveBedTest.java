package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsU;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerCombatKill;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundShowDialog;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Minecraft#tick runs handleKeybinds, the only caller of the pick key, while no screen is open, and sets
 * InBedChatScreen, the only sender of STOP_SLEEPING, while no screen is open. Through 1.21.3 the pick sends pick_item.
 * A screen set in place of a container screen leaves it through AbstractContainerScreen#removed, which sends no
 * container_close: the sign editor, book, demo screen, credits, death screen, reconfiguration screen, and a dialog a
 * nether portal then dismisses.
 */
class MultiActionsUPickLeaveBedTest {
    private static final String RELEASES = "ac.cult.cultac.network.JavaPistonReleaseCases#releases";
    private static final int CHEST = 51;
    private static final int LECTERN = 52;

    private final PacketPlayerWindow window = new PacketPlayerWindow();

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void eachActionWithTheInventoryOpenFlagsAndNamesItself(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            var verbose = new ArrayList<String>();
            f.player.observer = new PlayerObserver() {
                @Override
                public void flagged(Check check, double violations, Supplier<String> text) {
                    verbose.add(text.get());
                }
            };
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_ENTITY);
            pick(f, ServerboundPackets.PICK_ITEM);
            leaveBed(f);
            assertEquals(4, violations(f));
            assertEquals(List.of("action=pickBlock", "action=pickEntity", "action=pick", "action=leaveBed"), verbose);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aFlaggedActionIsCancelledWhereTheCheckMayModifyPackets(String release, ClientVersion version)
            throws Exception {
        try (var f = fixture(version)) {
            var check = spy(f.player.checkManager.getListener(MultiActionsU.class));
            doReturn(true).when(check).shouldModifyPackets();
            var pick = ServerboundPackets.PICK_ITEM_FROM_BLOCK.opaqueValue();
            var wake = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.STOP_SLEEPING, 0);

            var closed = f.event(ServerboundPackets.PICK_ITEM_FROM_BLOCK, pick);
            check.onPickBlock(closed, f.player, pick);
            assertFalse(closed.isCancelled());

            f.player.openWindow.onClientOpenOrCloseWindow(true);
            var picked = f.event(ServerboundPackets.PICK_ITEM_FROM_BLOCK, pick);
            check.onPickBlock(picked, f.player, pick);
            assertTrue(picked.isCancelled());
            var woken = f.event(ServerboundPackets.PLAYER_COMMAND, wake);
            check.onPlayerCommand(woken, f.player, wake);
            assertTrue(woken.isCancelled());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void noActionFlagsWithNoScreenOpen(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            assertFalse(pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK).isCancelled());
            assertFalse(pick(f, ServerboundPackets.PICK_ITEM_FROM_ENTITY).isCancelled());
            assertFalse(pick(f, ServerboundPackets.PICK_ITEM).isCancelled());
            assertFalse(leaveBed(f).isCancelled());
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterTheInventoryClosedDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickFlagsOnlyOnceTheClientHasHandledTheChest(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            int open = openChestUnanswered(f);
            pick(f);
            f.pongThrough(open - 1);
            pick(f);
            assertEquals(0, violations(f));
            f.pongThrough(open);
            pick(f);
            assertEquals(1, violations(f));
        }
    }

    /** The chest screen replaced InBedChatScreen, and its container_close precedes the Leave Bed button. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void leavingTheBedAfterClosingAChestOpenedOverItDoesNotFlag(String release, ClientVersion version)
            throws Exception {
        try (var f = fixture(version)) {
            f.pongThrough(openChestUnanswered(f));
            assertEquals(1, flagsOf(f, () -> leaveBed(f)));
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            leaveBed(f);
            assertEquals(1, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterABookTheServerOpenedOverAChestDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.pongThrough(openChestUnanswered(f));
            int book = openBook(f);
            assertEquals(1, flagsOf(f, () -> pick(f)));
            f.pongThrough(book);
            pick(f);
            leaveBed(f);
            assertEquals(1, violations(f));
        }
    }

    /** Minecraft#startUseItem sent the book's use, and the inventory key and a click followed before open_book. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anInventoryClickBetweenTheBooksPingsDoesNotProveTheInventoryStayed(String release, ClientVersion version)
            throws Exception {
        try (var f = fixture(version)) {
            int book = openBook(f);
            f.pongThrough(book - 1);
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.pongThrough(book);
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anInventoryClickAfterTheBookProvesTheInventoryIsOpen(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.pongThrough(openBook(f));
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            pick(f);
            assertEquals(1, violations(f));
        }
    }

    /** WinScreen sends PERFORM_RESPAWN and leaves no screen until the respawn arrives. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterTheCreditsOverAChestDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.pongThrough(openChestUnanswered(f));
            gameEvent(f, GameEventType.WIN_GAME, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anotherGameEventLeavesTheChestOpen(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.pongThrough(openChestUnanswered(f));
            int sent = f.player.lastTransactionSent.get();
            gameEvent(f, GameEventType.CHANGE_GAME_MODE, 1);
            assertEquals(sent, f.player.lastTransactionSent.get());
            pick(f);
            assertEquals(1, violations(f));
        }
    }

    /** ClientPacketListener#handleOpenSignEditor sets the editor in every release; its removed sends sign_update. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterTheSignEditorOverAChestClosedDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            openSignEditor(f);
            signUpdate(f, false);
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    /** No check cancels sign_update: the client sent it from AbstractSignEditScreen#removed all the same. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterACancelledSignUpdateDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            openSignEditor(f);
            signUpdate(f, true);
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    /** The DEMO_EVENT game event with value 0 sets the demo intro screen; leaving it sends nothing. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterTheDemoScreenOverAChestDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            gameEvent(f, GameEventType.DEMO_EVENT, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterTheDemoScreenOverTheInventoryDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            gameEvent(f, GameEventType.DEMO_EVENT, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    /** Other DEMO_EVENT values only add a chat line. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aDemoHintLeavesTheChestOpen(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            gameEvent(f, GameEventType.DEMO_EVENT, 101);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f);
            assertEquals(1, violations(f));
        }
    }

    /** start_configuration clears the level behind a reconfiguration screen; the login after it opens no screen. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterAReconfigurationLoginDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            login(f);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f);
            leaveBed(f);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterAReconfigurationLoginOverTheInventoryDoesNotFlag(String release, ClientVersion version)
            throws Exception {
        try (var f = fixture(version)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            login(f);
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    /** handlePlayerCombatKill sets a DeathScreen; its Title Screen confirm can respawn and set no screen. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterADeathScreenOverAChestDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            combatKill(f, f.player.entityID);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    /** Without the death screen the client respawns from handlePlayerCombatKill and keeps its screen. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anImmediateRespawnKillLeavesTheChestOpen(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            f.player.packetStateData.showsDeathScreen = false;
            combatKill(f, f.player.entityID);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f);
            assertEquals(1, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anotherPlayersKillLeavesTheChestOpen(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            combatKill(f, f.player.entityID + 1);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f);
            assertEquals(1, violations(f));
        }
    }

    /**
     * ClientCommonPacketListenerImpl#showDialog keeps the chest as the dialog's previous screen; the dialog is not
     * allowed in a portal, so LocalPlayer#handlePortalTransitionEffect sets no screen and sends no close for it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterAPortalDismissedADialogOverAChestDoesNotFlag(String release, ClientVersion version)
            throws Exception {
        assumeTrue(version.isNewerThanOrEquals(ClientVersion.V_1_21_6));
        try (var f = chestOpen(version)) {
            showDialog(f);
            f.pongThrough(f.player.sendTransactionAndGetId());
            f.player.openWindow.onPortalClosedScreen();
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    /** handleContainerClose closes the client's screen whatever its id: the click may come before or after it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anInventoryClickBetweenAServerClosesPingsLeavesTheScreenUnknown(String release, ClientVersion version)
            throws Exception {
        try (var f = chestOpen(version)) {
            int close = serverClose(f);
            f.pongThrough(close - 1);
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.pongThrough(close);
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anInventoryClickAfterAServerCloseProvesTheInventoryIsOpen(String release, ClientVersion version)
            throws Exception {
        try (var f = chestOpen(version)) {
            f.pongThrough(serverClose(f));
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            pick(f);
            assertEquals(1, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterAServerOpenedAndClosedAChestOverTheInventoryDoesNotFlag(String release, ClientVersion version)
            throws Exception {
        try (var f = fixture(version)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.pongThrough(openChestUnanswered(f));
            f.pongThrough(serverClose(f));
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterARespawnOverTheInventoryDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            respawn(f);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    /** LocalPlayer#handlePortalTransitionEffect sends container_close for the chest it closes. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterAPortalClosedTheChestDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            f.player.openWindow.onPortalClosedScreen();
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            pick(f);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterAPortalClosedTheLecternDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.pongThrough(openScreenUnanswered(f, LECTERN, "minecraft:lectern"));
            assertEquals(1, flagsOf(f, () -> pick(f)));
            f.player.openWindow.onPortalClosedScreen();
            pick(f);
            assertEquals(1, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void leavingTheBedBeforeTheChestsLeadingPongDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            openChestUnanswered(f);
            leaveBed(f);
            assertEquals(0, violations(f));
        }
    }

    /** The close CultAC asks the platform for reaches the client as a container_close the tracker follows. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aCancelledPickClosesTheChestForTheNextOne(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            var check = spy(f.player.checkManager.getListener(MultiActionsU.class));
            doReturn(true).when(check).shouldModifyPackets();
            var pick = ServerboundPackets.PICK_ITEM_FROM_BLOCK.opaqueValue();
            var first = f.event(ServerboundPackets.PICK_ITEM_FROM_BLOCK, pick);
            check.onPickBlock(first, f.player, pick);
            assertTrue(first.isCancelled());
            verify(f.fixture.user.getPlayer()).closeInventory();
            f.pongThrough(serverClose(f));
            var second = f.event(ServerboundPackets.PICK_ITEM_FROM_BLOCK, pick);
            check.onPickBlock(second, f.player, pick);
            assertFalse(second.isCancelled());
            assertEquals(1, check.getViolations());
        }
    }

    /** Without packet modification nothing closes the server's chest, so every later pick flags. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void withoutPacketModificationEveryPickFlagsUntilAClose(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            pick(f);
            pick(f);
            verify(f.fixture.user.getPlayer(), never()).closeInventory();
            assertEquals(2, violations(f));
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            pick(f);
            assertEquals(2, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickAfterAnInventoryClickFlags(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            pick(f);
            assertEquals(1, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aPickBeforeAServerClosesLeadingPongFlags(String release, ClientVersion version) throws Exception {
        try (var f = chestOpen(version)) {
            int close = serverClose(f);
            pick(f);
            f.pongThrough(close - 2);
            pick(f);
            assertEquals(2, violations(f));
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.of(version.getProtocolVersion()), version);
        f.player.setExperimentalChecks(true);
        f.player.platformPlayer = f.fixture.user.getPlayer();
        return f;
    }

    private JavaTickFixture chestOpen(ClientVersion version) throws Exception {
        var f = fixture(version);
        f.pongThrough(openChestUnanswered(f));
        return f;
    }

    /** The pick a vanilla client of this release sends for a block. */
    private static PacketReceiveEvent<Opaque> pick(JavaTickFixture f) {
        return pick(
                f,
                f.player.getClientVersion().isOlderThan(ClientVersion.V_1_21_4)
                        ? ServerboundPackets.PICK_ITEM
                        : ServerboundPackets.PICK_ITEM_FROM_BLOCK);
    }

    private static PacketReceiveEvent<Opaque> pick(JavaTickFixture f, PacketType<Opaque> type) {
        var packet = type.opaqueValue();
        var event = f.event(type, packet);
        MultiActionsU check = f.player.checkManager.getListener(MultiActionsU.class);
        if (type == ServerboundPackets.PICK_ITEM_FROM_BLOCK) check.onPickBlock(event, f.player, packet);
        else if (type == ServerboundPackets.PICK_ITEM_FROM_ENTITY) check.onPickEntity(event, f.player, packet);
        else check.onPickItem(event, f.player, packet);
        return event;
    }

    private static PacketReceiveEvent<ServerboundPlayerCommand> leaveBed(JavaTickFixture f) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.STOP_SLEEPING, 0);
        var event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        f.player.checkManager.getListener(MultiActionsU.class).onPlayerCommand(event, f.player, packet);
        return event;
    }

    private static int flagsOf(JavaTickFixture f, Runnable action) {
        double before = violations(f);
        action.run();
        return (int) (violations(f) - before);
    }

    private int openChestUnanswered(JavaTickFixture f) {
        return openScreenUnanswered(f, CHEST, "minecraft:generic_9x6");
    }

    private int openScreenUnanswered(JavaTickFixture f, int id, String type) {
        var packet = new ClientboundOpenScreen(id, type);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        window.onOpenScreen(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    /** The transaction of the ping after container_close; the ping before it is the one before. */
    private int serverClose(JavaTickFixture f) {
        var packet = new ClientboundContainerClose(CHEST);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.CONTAINER_CLOSE, packet, false);
        window.onClientboundContainerClose(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    /** The transaction of the ping after open_book; the ping before it is the one before. */
    private int openBook(JavaTickFixture f) {
        var packet = ClientboundPackets.OPEN_BOOK.opaqueValue();
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_BOOK, packet, false);
        window.onOpenBook(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private void openSignEditor(JavaTickFixture f) {
        var packet = ClientboundPackets.OPEN_SIGN_EDITOR.opaqueValue();
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SIGN_EDITOR, packet, false);
        window.onOpenSignEditor(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private void signUpdate(JavaTickFixture f, boolean cancelled) {
        var packet = ServerboundPackets.SIGN_UPDATE.opaqueValue();
        var event = f.event(ServerboundPackets.SIGN_UPDATE, packet);
        event.setCancelled(cancelled);
        window.onSignUpdate(event, f.player, packet);
    }

    private void gameEvent(JavaTickFixture f, GameEventType type, float param) {
        var packet = new ClientboundGameEvent(type, param);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.GAME_EVENT, packet, false);
        window.onGameEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private void showDialog(JavaTickFixture f) {
        var packet = ClientboundShowDialog.inline(new byte[] {10, 0});
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SHOW_DIALOG, packet, false);
        window.onShowDialog(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private void combatKill(JavaTickFixture f, int playerId) {
        var packet = new ClientboundPlayerCombatKill(playerId);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_COMBAT_KILL, packet, false);
        window.onPlayerCombatKill(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private static PlayerSpawnInfo overworld() {
        int type = ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld");
        return new PlayerSpawnInfo(type, "minecraft:overworld", GameMode.SURVIVAL);
    }

    private static void login(JavaTickFixture f) {
        var packet = new ClientboundLogin(f.player.entityID, true, overworld());
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.LOGIN, packet, false);
        new PacketPlayerRespawn().onLogin(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private void respawn(JavaTickFixture f) {
        var packet = new ClientboundRespawn(overworld(), (byte) 0);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        window.onRespawn(event, f.player, packet);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private static double violations(JavaTickFixture f) {
        return f.player.checkManager.getListener(MultiActionsU.class).getViolations();
    }
}
