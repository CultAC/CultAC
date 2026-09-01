package ac.grim.grimac.bedrock.replay.offline;

import ac.grim.grimac.checks.impl.chat.ChatD;
import ac.grim.grimac.events.packets.listeners.PacketConfigurationListener;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.player.GrimPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class PacketConfigurationParityTest {
    @Test
    public void configurationListenerDoesNotHandlePlayCustomPayload() {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = offlineJavaPlayer();
        try {
            ServerboundCustomPayloadPacket packet = new ServerboundCustomPayloadPacket(
                    new DiscardedPayload(Identifier.parse("grim:play_only"), new byte[0]));
            PacketReceiveEvent event = new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);

            new PacketConfigurationListener().onCustomPayload(event, player, packet);

            assertTrue(player.pluginChannelManager.getRegisteredChannels().isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void configurationClientInformationUpdatesChatDVisibility() throws Exception {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = offlineJavaPlayer();
        try {
            ClientInformation defaults = ClientInformation.createDefault();
            ClientInformation hidden = new ClientInformation(
                    defaults.language(),
                    defaults.viewDistance(),
                    ChatVisiblity.HIDDEN,
                    defaults.chatColors(),
                    defaults.modelCustomisation(),
                    defaults.mainHand(),
                    defaults.textFilteringEnabled(),
                    defaults.allowsListing(),
                    defaults.particleStatus());
            ServerboundClientInformationPacket packet = new ServerboundClientInformationPacket(hidden);
            PacketReceiveEvent event = new PacketReceiveEvent(player.user, packet, ConnectionProtocol.CONFIGURATION);

            new PacketConfigurationListener().onClientInformation(event, player, packet);

            assertTrue(booleanField(player.checkManager.getListener(ChatD.class), "hidden"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void configurationClientInformationIsNotSanitizedByPlayOnlyCrashE() {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = offlineJavaPlayer();
        try {
            ClientInformation defaults = ClientInformation.createDefault();
            ClientInformation lowDistance = new ClientInformation(
                    defaults.language(),
                    1,
                    defaults.chatVisibility(),
                    defaults.chatColors(),
                    defaults.modelCustomisation(),
                    defaults.mainHand(),
                    defaults.textFilteringEnabled(),
                    defaults.allowsListing(),
                    defaults.particleStatus());
            ServerboundClientInformationPacket packet = new ServerboundClientInformationPacket(lowDistance);
            PacketReceiveEvent event = new PacketReceiveEvent(player.user, packet, ConnectionProtocol.CONFIGURATION);

            new PacketConfigurationListener().onClientInformation(event, player, packet);

            assertTrue(event.getNmsPacket() == packet);
            assertTrue(!event.shouldReEncode());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static GrimPlayer offlineJavaPlayer() {
        UUID playerId = UUID.fromString("244cd32d-3e42-4ec0-b22c-c95be54d1c58");
        User user = new User(
                new User.Profile(playerId, ".Configuration_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new GrimPlayer(user);
    }
}
