package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.FormerName;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.grim.grimac.api.storage.verbose.Verbose;

// LocalPlayer#closeContainer alone sends container_close, and sets the player inventory menu and no screen. Only a
// container screen sends container_click, from its key and mouse handlers, and the player inventory opens only in
// Minecraft#handleKeybinds, inside a client tick, which sends its client_tick_end before the next frame's input. So
// after a close, nothing but a server window handled after it can be clicked or closed before the tick ends, with two
// exceptions. LocalPlayer#handlePortalTransitionEffect closes the inventory that tick's handleKeybinds opened. And
// through 1.21.8 AnvilScreen#keyPressed does not return after the close of its Escape key, whose Screen#keyPressed
// then closes the player inventory menu too, unless the name box consumes the key.
@CheckData(
        name = "InventoryM",
        formerNames = @FormerName(name = "MultiActionsV", until = 2),
        stableKey = "cult.multiactions.inventory_after_close",
        description = "Clicked in or closed an inventory again in the tick it closed one",
        experimental = true)
public class MultiActionsV extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("action=[click|close], window={sint}");

    private boolean closedThisTick;
    // The newest transaction the client had answered when it sent the tick's last close.
    private int lastCloseTransaction;
    // A pre-1.21.9 anvil's Escape may still close the player inventory menu.
    private boolean anvilEscapeClose;
    // This tick's LocalPlayer#aiStep may still close the screen for a nether portal.
    private boolean portalClosePossible;

    public MultiActionsV(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onContainerClose(
            PacketReceiveEvent<ServerboundContainerClose> event, CultPlayer player, ServerboundContainerClose packet) {
        if (!judged()) return;
        int id = packet.containerId();
        boolean first = !closedThisTick;
        if (first || player.openWindow.mayHaveOpenedSince(id, lastCloseTransaction)) {
            anvilEscapeClose = player.getClientVersion().isOlderThan(ClientVersion.V_1_21_9)
                    && player.openWindow.mayBe(id, MenuType.ANVIL);
        } else if (id == 0 && anvilEscapeClose) {
            anvilEscapeClose = false;
        } else if (id == 0 && portalClosePossible && portalMayCloseScreen()) {
            portalClosePossible = false;
        } else {
            // Like InventoryB, never cancelled: the server would keep a window open that the client closed.
            flag(V.write(verbose()).bool(false).sint(id));
        }
        if (first) portalClosePossible = true;
        closedThisTick = true;
        lastCloseTransaction = player.lastTransactionReceived.get();
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        // A click on any other window that was not reopened is BadPacketsWindow's.
        if (!judged() || !closedThisTick || packet.windowId() != 0) return;
        if (player.openWindow.mayHaveOpenedSince(0, lastCloseTransaction)) return;
        if (flag(V.write(verbose()).bool(true).sint(0)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    // Any tick end, also one the timer cancels: the client ran that tick.
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        closedThisTick = false;
        anvilEscapeClose = false;
    }

    // LocalPlayer#aiStep, which LivingEntity#tick skips once LocalPlayer#tickDeath removed the player.
    private boolean portalMayCloseScreen() {
        return player.localPlayerTicks() && player.localPortalProcess.mayCloseScreenOfNextAiStep();
    }

    // Older clients send no client_tick_end, so the tick of a packet is unknown.
    private boolean judged() {
        return !player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }
}
