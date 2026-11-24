package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsWindow;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundShowDialog;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.math.Vec3;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * ClientCommonPacketListenerImpl#showDialog sets a dialog over the open container screen and keeps it as the dialog's
 * previous screen. LocalPlayer#handlePortalTransitionEffect sets no screen in place of a pause:false dialog, which is
 * no AbstractContainerScreen, so the client sends no container_close and turns freely from that tick on; a dialog that
 * pauses is Screen#isAllowedInPortal and stays, and closing it brings the container screen back. As in a 26.3 capture,
 * the player clicks slot 36 of the inventory, a dialog is shown and the player stands in a nether portal.
 */
class MultiActionsOPortalDialogTest {
    private static final Vec3 STANDING = new Vec3(1.1201, JavaTickFixture.FLOOR_Y, 0.1564);
    private static final float YAW = 179.54813f;
    private static final float PITCH = 2.328525f;
    private static final int CHEST = 1;

    private final PacketPlayerWindow window = new PacketPlayerWindow();

    @Test
    void turningAfterAPortalClosedADialogOverTheInventoryDoesNotFlag() throws Exception {
        try (var f = inPortal(false, notice(false))) {
            look(f, 209.35518f, -8.167854f);
            f.tickEnd();
            look(f, 211.0f, -9.0f);
            assertEquals(0, violations(f));
        }
    }

    @Test
    void turningWithTheInventoryOnTopInAPortalAndNoContainerCloseFlags() throws Exception {
        try (var f = inPortal(false, null)) {
            look(f, 209.35518f, -8.167854f);
            f.tickEnd();
            look(f, 211.0f, -9.0f);
            assertEquals(2, violations(f));
        }
    }

    @Test
    void aClickInAChestUnderAPausingDialogInAPortalDoesNotFlag() throws Exception {
        try (var f = inPortal(true, notice(true))) {
            look(f, 209.35518f, -8.167854f);
            BadPacketsWindow check = f.player.checkManager.getCheck(BadPacketsWindow.class);
            check.setEnabled(true);
            check.reload();
            check.violations = 0;
            var event = click(f, CHEST, 0);
            check.onContainerClick(event, f.player, event.getPacket());
            assertFalse(event.isCancelled());
            assertEquals(0, check.getViolations());
        }
    }

    private static ClientboundShowDialog notice(boolean pauses) {
        var fields = new HashMap<String, NbtValue>();
        fields.put("type", new NbtValue.Text("minecraft:notice"));
        fields.put("title", new NbtValue.Text("Test"));
        if (!pauses) fields.put("pause", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 0));
        return ClientboundShowDialog.inline(BinaryNbt.write(new NbtValue.Compound(fields)));
    }

    private JavaTickFixture inPortal(boolean chest, @Nullable ClientboundShowDialog dialog) throws Exception {
        var f = new JavaTickFixture();
        MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
        f.player.setExperimentalChecks(true);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        f.player.packetStateData.playerLoadedIntoLevel = true;
        f.place(STANDING, Vec3.ZERO);
        f.player.xRot = YAW;
        f.player.yRot = PITCH;
        for (int tick = 0; tick < 3; tick++) f.tick(STANDING, true);
        if (chest) {
            var packet = new ClientboundOpenScreen(CHEST, "minecraft:generic_9x3");
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
            window.onOpenScreen(event, f.player, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
        } else {
            var event = click(f, 0, 36);
            window.onContainerClick(event, f.player, event.getPacket());
        }
        if (dialog != null) {
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SHOW_DIALOG, dialog, false);
            window.onShowDialog(event, f.player, dialog);
        }
        f.pongThrough(f.player.sendTrailingTransaction());
        f.tickEnd();
        for (int y = JavaTickFixture.FLOOR_Y + 1; y <= JavaTickFixture.FLOOR_Y + 2; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.NETHER_PORTAL.defaultState());
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.tick(STANDING, true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static PacketReceiveEvent<InventoryClick> click(JavaTickFixture f, int windowId, int slot) {
        var click = new InventoryClick(windowId, 4, slot, 0, WindowClickType.PICKUP, Map.of(), SimItemStack.EMPTY);
        var type = (PacketType<InventoryClick>)
                f.fixture.registrar.scanner().runtime().typeForRecord(InventoryClick.class);
        return new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, click);
    }

    private static void look(JavaTickFixture f, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
    }

    private static double violations(JavaTickFixture f) {
        return f.player.checkManager.getCheck(MultiActionsO.class).getViolations();
    }
}
