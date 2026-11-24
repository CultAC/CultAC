package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsV;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMountScreenOpen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LocalPlayer#closeContainer sets the player inventory menu and no screen, and the player inventory opens only in
 * Minecraft#handleKeybinds, inside a client tick: after a container_close, only a server window handled since can be
 * clicked or closed before client_tick_end. LocalPlayer#handlePortalTransitionEffect closes the inventory the tick
 * opened, and through 1.21.8 an anvil's Escape also closes the player inventory menu.
 */
class InventoryCloseTickTest {
    private static final int CHEST = 51;
    private static final int OTHER = 52;
    private static final int ANVIL = 7;

    private final PacketPlayerWindow window = new PacketPlayerWindow();

    // C-1
    @Test
    void aClickOnThePlayerInventoryInTheTickItClosedFlagsAndIsCancelled() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            var verbose = new ArrayList<String>();
            f.player.observer = new PlayerObserver() {
                @Override
                public void flagged(Check check, double violations, Supplier<String> text) {
                    if (check instanceof MultiActionsV) verbose.add(text.get());
                }
            };
            var check = spy(check(f));
            doReturn(true).when(check).shouldModifyPackets();
            close(f, check, CHEST);
            var click = click(f, check, 0);
            assertTrue(click.isCancelled());
            assertEquals(1, check.getViolations());
            assertEquals(List.of("action=click, window=0"), verbose);
        }
    }

    // C-1
    @Test
    void aClickAfterTheTickEndDoesNotFlag() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            close(f, CHEST);
            tickEnd(f);
            assertFalse(click(f, check(f), 0).isCancelled());
            assertEquals(0, violations(f));
        }
    }

    // C-2, C-3
    @ParameterizedTest(name = "{0}: an anvil's Escape closes the player inventory menu too only through 1.21.8")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anAnvilEscapeSendsASecondCloseOnlyThrough1218(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.pongThrough(open(f, ANVIL, "minecraft:anvil"));
            close(f, ANVIL);
            close(f, 0);
            boolean returnsAfterClose = version.isNewerThanOrEquals(ClientVersion.V_1_21_9);
            assertEquals(returnsAfterClose ? 1 : 0, violations(f));
            // One Escape sends one more close at most.
            close(f, 0);
            assertEquals(returnsAfterClose ? 2 : 1, violations(f));
        }
    }

    @ParameterizedTest(name = "{0}: a chest's close is never followed by the player inventory menu's")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aChestCloseFollowedByAPlayerInventoryCloseFlags(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            f.pongThrough(open(f, CHEST, "minecraft:generic_9x3"));
            close(f, CHEST);
            close(f, 0);
            assertEquals(1, violations(f));
        }
    }

    /** An anvil reopened after the tick's first close sends its pair of closes too. */
    @Test
    void anAnvilTheServerOpenedAfterTheFirstCloseSendsItsPair() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_2)) {
            close(f, CHEST);
            f.pongThrough(open(f, ANVIL, "minecraft:anvil"));
            close(f, ANVIL);
            close(f, 0);
            assertEquals(0, violations(f));
        }
    }

    // C-4
    @Test
    void aWindowTheServerOpenedAfterTheCloseMayBeClosedOrClicked() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            close(f, CHEST);
            f.pongThrough(open(f, OTHER, "minecraft:generic_9x3"));
            click(f, check(f), OTHER);
            close(f, OTHER);
            assertEquals(0, violations(f));
        }
    }

    // C-4
    @Test
    void aReopenedWindowMayReuseTheClosedId() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.pongThrough(open(f, CHEST, "minecraft:generic_9x3"));
            close(f, CHEST);
            f.pongThrough(open(f, CHEST, "minecraft:generic_9x3"));
            close(f, CHEST);
            assertEquals(0, violations(f));
        }
    }

    // C-4
    @Test
    void aWindowSentBeforeTheCloseArrivedMayBeHandledAfterIt() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            int sent = open(f, OTHER, "minecraft:generic_9x3");
            close(f, CHEST);
            f.pongThrough(sent);
            close(f, OTHER);
            assertEquals(0, violations(f));
        }
    }

    // C-5
    @Test
    void aSecondCloseOfNoReopenedWindowFlags() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.pongThrough(open(f, CHEST, "minecraft:generic_9x3"));
            close(f, CHEST);
            close(f, CHEST);
            assertEquals(1, violations(f));
            tickEnd(f);
            close(f, CHEST);
            close(f, OTHER);
            assertEquals(2, violations(f));
            tickEnd(f);
            close(f, 0);
            close(f, 0);
            assertEquals(3, violations(f));
        }
    }

    /** A window handled before the tick's first close is the one that close closed. */
    @Test
    void aWindowHandledBeforeTheFirstCloseIsNotReopened() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.pongThrough(open(f, OTHER, "minecraft:generic_9x3"));
            f.pongThrough(open(f, CHEST, "minecraft:generic_9x3"));
            close(f, CHEST);
            close(f, OTHER);
            assertEquals(1, violations(f));
        }
    }

    // C-6
    @Test
    void thePortalClosesTheInventoryTheTickOpenedOnce() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.player.localPortalProcess.onContactJudged();
            f.player.localPortalProcess.onAiStep(true, true);
            close(f, CHEST);
            close(f, 0);
            assertEquals(0, violations(f));
            close(f, 0);
            assertEquals(1, violations(f));
        }
    }

    @Test
    void outsideAPortalTheSecondPlayerInventoryCloseFlags() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.player.localPortalProcess.onContactJudged();
            f.player.localPortalProcess.onAiStep(true, false);
            close(f, CHEST);
            close(f, 0);
            assertEquals(1, violations(f));
        }
    }

    /** No simulated movement judged the last tick's portal contact. */
    @Test
    void anUnjudgedPortalContactLeavesTheSecondCloseUnjudged() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.player.localPortalProcess.onAiStep(true, false);
            close(f, CHEST);
            close(f, 0);
            assertEquals(0, violations(f));
        }
    }

    /** The aiStep's first player_input applied the portal's close: a later close is not it. */
    @Test
    void aCloseAfterTheAiStepTookThePortalCloseFlags() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.player.localPortalProcess.onContactJudged();
            f.player.localPortalProcess.onAiStep(true, true);
            assertTrue(f.player.localPortalProcess.takeScreenCloseOfNextAiStep());
            close(f, CHEST);
            close(f, 0);
            assertEquals(1, violations(f));
        }
    }

    // C-7
    @Test
    void aDeadPlayersScreenTickCloseAndTheNextTicksCloseDoNotFlag() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.pongThrough(open(f, CHEST, "minecraft:generic_9x3"));
            close(f, CHEST);
            tickEnd(f);
            close(f, 0);
            assertEquals(0, violations(f));
        }
    }

    // C-8
    @Test
    void theRespawnHandlerClosesAWindowReopenedAfterTheClose() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            close(f, CHEST);
            f.pongThrough(open(f, OTHER, "minecraft:generic_9x3"));
            int respawn = respawn(f);
            f.pongThrough(respawn - 1);
            close(f, OTHER);
            f.pongThrough(respawn);
            assertEquals(0, violations(f));
        }
    }

    // C-9
    @Test
    void aServerWindowWithIdZeroMayBeClickedAfterTheClose() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            close(f, ANVIL);
            f.pongThrough(open(f, 0, "minecraft:generic_9x3"));
            click(f, check(f), 0);
            assertEquals(0, violations(f));
        }
    }

    // C-10
    @Test
    void aMountScreenTheModelCouldNotFollowMayBeClosedAfterTheClose() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            close(f, CHEST);
            mountScreen(f, OTHER, 1234);
            close(f, OTHER);
            assertEquals(0, violations(f));
        }
    }

    // C-10
    @Test
    void anUnknownMenuTypeMayBeClosedAfterTheClose() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            close(f, CHEST);
            open(f, OTHER, "example:custom_menu");
            close(f, OTHER);
            assertEquals(0, violations(f));
        }
    }

    /** The client answered a ping written after the untracked window before it closed: that close closed it. */
    @Test
    void anUntrackedWindowHandledBeforeTheCloseIsNotReopened() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            mountScreen(f, OTHER, 1234);
            f.pongThrough(f.player.sendTrailingTransaction());
            close(f, OTHER);
            close(f, OTHER);
            assertEquals(1, violations(f));
        }
    }

    // C-11
    @Test
    void aLoneCloseFromTheRespawnHandlerDoesNotFlag() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            int respawn = respawn(f);
            f.pongThrough(respawn - 1);
            close(f, 0);
            f.pongThrough(respawn);
            assertEquals(0, violations(f));
        }
    }

    // C-12
    @Test
    void clientsWithoutATickEndAndBedrockPlayersAreNotJudged() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21)) {
            close(f, CHEST);
            close(f, CHEST);
            click(f, check(f), 0);
            assertEquals(0, violations(f));
        }
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.player.movementPlatform = MovementPlatform.BEDROCK;
            close(f, CHEST);
            close(f, CHEST);
            click(f, check(f), 0);
            assertEquals(0, violations(f));
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.setExperimentalChecks(true);
        return f;
    }

    private static MultiActionsV check(JavaTickFixture f) {
        return f.player.checkManager.getListener(MultiActionsV.class);
    }

    private static double violations(JavaTickFixture f) {
        return check(f).getViolations();
    }

    private void close(JavaTickFixture f, int id) {
        close(f, check(f), id);
    }

    /** The early check first, then the window model, as the receive routes order them. */
    private void close(JavaTickFixture f, MultiActionsV check, int id) {
        var packet = new ServerboundContainerClose(id);
        var event = f.event(ServerboundPackets.CONTAINER_CLOSE, packet);
        check.onContainerClose(event, f.player, packet);
        window.onServerboundContainerClose(event, f.player, packet);
    }

    @SuppressWarnings("unchecked")
    private PacketReceiveEvent<InventoryClick> click(JavaTickFixture f, MultiActionsV check, int id) {
        var packet = new InventoryClick(id, 0, 20, 0, WindowClickType.PICKUP, Map.of(), SimItemStack.EMPTY);
        var type = (PacketType<InventoryClick>)
                f.fixture.registrar.scanner().runtime().typeForRecord(InventoryClick.class);
        var event = new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet);
        check.onContainerClick(event, f.player, packet);
        window.onContainerClick(event, f.player, packet);
        return event;
    }

    private static void tickEnd(JavaTickFixture f) {
        var packet = ServerboundPackets.CLIENT_TICK_END.opaqueValue();
        check(f).onClientTickEnd(f.event(ServerboundPackets.CLIENT_TICK_END, packet), f.player, packet);
    }

    /** An open_screen and its proofs, unanswered; the transaction of the ping after it. */
    private int open(JavaTickFixture f, int id, String menu) {
        var packet = new ClientboundOpenScreen(id, menu);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        window.onOpenScreen(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    /** A horse screen for an entity the model does not track. */
    private void mountScreen(JavaTickFixture f, int id, int entityId) {
        var packet = new ClientboundMountScreenOpen(id, 3, entityId);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOUNT_SCREEN_OPEN, packet, false);
        window.onMountScreenOpen(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    /** A respawn's leading proof, unanswered; its transaction. */
    private int respawn(JavaTickFixture f) {
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld"),
                f.player.compensatedWorld.getLastClientboundDimension().dimension(),
                GameMode.SURVIVAL,
                63,
                0,
                0);
        var packet = new ClientboundRespawn(spawnInfo, (byte) 3);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        window.onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }
}
