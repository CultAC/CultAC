package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.packetorder.PacketOrderO;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientInformation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.ChatVisibility;
import ac.cult.cultac.protocol.value.ClientInformation;
import ac.cult.cultac.protocol.value.MainHand;
import ac.cult.cultac.protocol.value.ParticleStatus;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick sends the player's movement from the entity tick, then runs Tutorial#tick (and the first-server
 * option save) before ServerboundClientTickEndPacket. A tutorial step change saves the options, and
 * Options#broadcastOptions sends ServerboundClientInformationPacket when the settings differ from the last sent, which
 * they always do for the first save of a play session.
 */
class PacketOrderOTickOrderTest {
    private static final ClientInformation INFORMATION = new ClientInformation(
            "en_us", 12, ChatVisibility.FULL, true, 127, MainHand.RIGHT, false, true, ParticleStatus.ALL);

    @Test
    void optionsSavedAfterTheMoveInTheSameTickDoNotFlag() throws Exception {
        try (var f = new JavaTickFixture()) {
            moveThen(f, ServerboundPackets.CLIENT_INFORMATION, new ServerboundClientInformation(INFORMATION));
            assertEquals(0, f.player.checkManager.getCheck(PacketOrderO.class).violations);
        }
    }

    @Test
    void aSwingAfterTheMoveInTheSameTickFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            moveThen(f, ServerboundPackets.SWING, ServerboundSwing.PUNCH);
            assertEquals(1, f.player.checkManager.getCheck(PacketOrderO.class).violations);
        }
    }

    @Test
    void aSwingAfterTheOptionsSavedInTheSameTickFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            move(f);
            CheckManagerListener.receiveGeneric(
                    f.event(
                            ServerboundPackets.CLIENT_INFORMATION, new ServerboundClientInformation(INFORMATION)),
                    f.player);
            CheckManagerListener.receiveGeneric(f.event(ServerboundPackets.SWING, ServerboundSwing.PUNCH), f.player);
            f.tickEnd();
            assertEquals(1, f.player.checkManager.getCheck(PacketOrderO.class).violations);
        }
    }

    private static <T extends ServerboundPacket> void moveThen(JavaTickFixture f, PacketType<T> type, T packet) {
        move(f);
        CheckManagerListener.receiveGeneric(f.event(type, packet), f.player);
        f.tickEnd();
    }

    private static void move(JavaTickFixture f) {
        f.player.setExperimentalChecks(true);
        Vec3 standing = new Vec3(.5, JavaTickFixture.FLOOR_Y, .5);
        f.place(standing, Vec3.ZERO);
        f.tick(standing, true);
        f.move(standing, true);
    }
}
