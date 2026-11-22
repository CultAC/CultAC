package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertTrue;

import ac.cult.cultac.checks.impl.chat.ChatD;
import ac.cult.cultac.events.packets.listeners.PacketConfigurationListener;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientInformation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.protocol.value.ChatVisibility;
import ac.cult.cultac.protocol.value.ClientInformation;
import ac.cult.cultac.protocol.value.MainHand;
import ac.cult.cultac.protocol.value.ParticleStatus;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import org.junit.Test;

public final class PacketConfigurationParityTest {
    @Test
    public void configurationListenerDoesNotHandlePlayCustomPayload() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            var packet = new ServerboundCustomPayload("cult:play_only", new byte[0]);
            var event = RecordReceiveTestEvents.customPayload(player, packet);

            new PacketConfigurationListener().onCustomPayload(event, player, packet);

            assertTrue(player.pluginChannelManager.getRegisteredChannels().isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void configurationClientInformationUpdatesChatDVisibility() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            ClientInformation defaults = defaults();
            ClientInformation hidden = new ClientInformation(
                    defaults.language(),
                    defaults.viewDistance(),
                    ChatVisibility.HIDDEN,
                    defaults.chatColors(),
                    defaults.modelCustomisation(),
                    defaults.mainHand(),
                    defaults.textFilteringEnabled(),
                    defaults.allowsListing(),
                    defaults.particleStatus());
            ServerboundClientInformation packet = new ServerboundClientInformation(hidden);
            var event = RecordReceiveTestEvents.clientInformation(player, packet, ConnectionPhase.CONFIGURATION);

            new PacketConfigurationListener().onClientInformation(event, player, packet);

            assertTrue(player.checkManager.getListener(ChatD.class).information() == hidden);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void configurationClientInformationIsNotSanitizedByPlayOnlyCrashE() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            ClientInformation defaults = defaults();
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
            ServerboundClientInformation packet = new ServerboundClientInformation(lowDistance);
            var event = RecordReceiveTestEvents.clientInformation(player, packet, ConnectionPhase.CONFIGURATION);

            new PacketConfigurationListener().onClientInformation(event, player, packet);

            assertTrue(event.getPacket() == packet);
            assertTrue(event.getOriginalPacket() == packet);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static ClientInformation defaults() {
        return new ClientInformation(
                "en_us", 2, ChatVisibility.FULL, true, 0, MainHand.RIGHT, false, false, ParticleStatus.ALL);
    }

    private static CultPlayer offlineJavaPlayer() {
        UUID playerId = UUID.fromString("244cd32d-3e42-4ec0-b22c-c95be54d1c58");
        User user = TestUsers.create(new User.Profile(playerId, ".Configuration_Test"), new EmbeddedChannel());
        return new CultPlayer(user);
    }
}
