package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsA;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsC;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleLogin creates a MultiPlayerGameMode, whose carriedIndex starts at 0, and after a
 * reconfiguration a LocalPlayer, whose lastSentInput starts at Input.EMPTY.
 */
class JavaLoginClientStateTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, true, true, false);

    @Test
    void theSlotHeldBeforeABackendSwitchIsSentAgainAfterIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            carry(f, 3);
            switchBackend(f, true);
            assertEquals(0, carry(f, 3));
        }
    }

    @Test
    void slotZeroRightAfterALoginRepeatsTheNewCarriedIndex() throws Exception {
        try (var f = new JavaTickFixture()) {
            carry(f, 3);
            switchBackend(f, true);
            assertEquals(1, carry(f, 0));
        }
    }

    @Test
    void aSlotTheClientSendsBeforeItProcessesTheLoginRepeatsTheOldCarriedIndex() throws Exception {
        try (var f = new JavaTickFixture()) {
            carry(f, 3);
            switchBackend(f, false);
            assertEquals(1, carry(f, 3));
        }
    }

    @Test
    void movementKeysHeldBeforeABackendSwitchAreNotHeldAfterIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.input(FORWARD_JUMP_SNEAK);
            assertEquals(new KnownInput(true, false, false, false, true, true, false), f.player.boatData.vehicleKeys);
            switchBackend(f, true);
            assertEquals(0, click(f));
            assertEquals(KnownInput.DEFAULT, f.player.packetStateData.knownInput);
            assertEquals(KnownInput.DEFAULT, f.player.boatData.vehicleKeys);
            assertEquals(KnownInput.DEFAULT, f.player.boatData.nextVehicleKeys);
        }
    }

    @Test
    void movementKeysPressedAfterABackendSwitchAreHeld() throws Exception {
        try (var f = new JavaTickFixture()) {
            switchBackend(f, true);
            f.input(FORWARD);
            assertEquals(1, click(f));
        }
    }

    /** A reconfiguration and a LOGIN; with {@code received}, the client answers the ping after the LOGIN. */
    private static void switchBackend(JavaTickFixture f, boolean received) {
        var type = ClientboundPackets.FINISH_CONFIGURATION;
        var finish = new PacketSendEvent<>(f.player.user, ConnectionPhase.CONFIGURATION, type, new Opaque(type), false);
        new PacketServerRegistries().onFinishConfiguration(finish, f.player, new Opaque(type));
        finish.getTasksAfterSend().forEach(Runnable::run);
        int ping = ItemUseTestSupport.login(f.player, GameMode.SURVIVAL);
        if (received) f.pongThrough(ping);
    }

    /** A set_carried_item for {@code slot}; the BadPacketsA violations it added. */
    private static int carry(JavaTickFixture f, int slot) {
        BadPacketsA check = f.player.checkManager.getCheck(BadPacketsA.class);
        double before = check.violations;
        var packet = new ServerboundSetCarriedItem(slot);
        check.onSetCarriedItem(f.event(ServerboundPackets.SET_CARRIED_ITEM, packet), f.player, packet);
        return (int) (check.violations - before);
    }

    /** A container click; the MultiActionsC violations it added. */
    @SuppressWarnings("unchecked")
    private static int click(JavaTickFixture f) {
        PacketType<InventoryClick> type = (PacketType<InventoryClick>) OfflineCultTestBootstrap.catalog().stream()
                .filter(packet -> packet.recordClass() == InventoryClick.class)
                .findFirst()
                .orElseThrow();
        MultiActionsC check = f.player.checkManager.getCheck(MultiActionsC.class);
        double before = check.violations;
        var click = new InventoryClick(0, 0, 36, 0, WindowClickType.PICKUP, Map.of(), null);
        check.onContainerClick(f.event(type, click), f.player, click);
        return (int) (check.violations - before);
    }
}
