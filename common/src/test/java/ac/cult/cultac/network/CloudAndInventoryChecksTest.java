package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.checks.impl.cloud.Cloud;
import ac.cult.cultac.checks.impl.misc.SkinBlinker;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsT;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientInformation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.ChatVisibility;
import ac.cult.cultac.protocol.value.ClientInformation;
import ac.cult.cultac.protocol.value.MainHand;
import ac.cult.cultac.protocol.value.ParticleStatus;
import ac.cult.cultac.utils.data.KnownInput;
import org.junit.jupiter.api.Test;

class CloudAndInventoryChecksTest {
    private static final KnownInput FORWARD = new KnownInput(true, false, false, false, false, false, false);
    private static final KnownInput TOGGLED = new KnownInput(false, false, false, false, false, true, true);

    @Test
    void skinPartsChangedWhileAMovementKeyIsHeldFlag() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = new SkinBlinker(f.player);
            f.player.packetStateData.knownInput = FORWARD;
            check.onClientInformation(information(f, 0x7F), f.player, information(0x7F));
            check.onClientInformation(information(f, 0x7F), f.player, information(0x7F));
            assertEquals(0, check.getViolations());
            check.onClientInformation(information(f, 0x7E), f.player, information(0x7E));
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void skinPartsChangedWithOnlyToggledKeysDownDoNotFlag() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = new SkinBlinker(f.player);
            f.player.packetStateData.knownInput = TOGGLED;
            check.onClientInformation(information(f, 0x7F), f.player, information(0x7F));
            check.onClientInformation(information(f, 0x00), f.player, information(0x00));
            check.onClientInformation(information(f, 0x7F), f.player, information(0x7F));
            assertEquals(0, check.getViolations());
        }
    }

    @Test
    void aHeldSlotChangeWithAnInventoryOpenFlags() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = spy(new MultiActionsT(f.player));
            doReturn(true).when(check).flag();
            doReturn(true).when(check).shouldModifyPackets();
            var packet = new ServerboundSetCarriedItem(3);
            var closed = slot(f, packet);
            check.onSetCarriedItem(closed, f.player, packet);
            verify(check, never()).flag();
            assertFalse(closed.isCancelled());

            f.player.openWindow.onClientOpenOrCloseWindow(true);
            var open = slot(f, packet);
            check.onSetCarriedItem(open, f.player, packet);
            verify(check).flag();
            assertTrue(open.isCancelled());
        }
    }

    @Test
    void cloudFindingsBelowTheConfiguredConfidenceDoNotFlag() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = new Cloud(f.player);
            var minConfidence = Cloud.class.getDeclaredField("minConfidence");
            minConfidence.setAccessible(true);
            minConfidence.setDouble(check, 0.5);
            check.finding("aim.tiny_yaw", 0.4, "yaw=0.0004");
            assertEquals(0, check.getViolations());
            check.finding("aim.tiny_yaw", 0.8, "yaw=0.0004");
            assertEquals(1, check.getViolations());
        }
    }

    private static PacketReceiveEvent<ServerboundClientInformation> information(RecordReceiveFixture f, int skin) {
        return new PacketReceiveEvent<>(
                f.user, ConnectionPhase.PLAY, ServerboundPackets.CLIENT_INFORMATION, information(skin));
    }

    private static ServerboundClientInformation information(int skin) {
        return new ServerboundClientInformation(new ClientInformation(
                "en_us", 8, ChatVisibility.FULL, true, skin, MainHand.RIGHT, false, true, ParticleStatus.ALL));
    }

    private static PacketReceiveEvent<ServerboundSetCarriedItem> slot(
            RecordReceiveFixture f, ServerboundSetCarriedItem packet) {
        return new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, ServerboundPackets.SET_CARRIED_ITEM, packet);
    }
}
