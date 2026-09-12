package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.events.packets.PacketPlayerJoinQuit;
import ac.cult.cultac.events.packets.PacketPluginMessage;
import ac.cult.cultac.events.packets.PacketServerPlayerRotation;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.events.packets.listeners.BedrockAuthInputPluginMessageListener;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.listeners.PacketConfigurationListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.events.packets.listeners.PacketPlayerCooldown;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketPlayerSteer;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.events.packets.listeners.PlayerInfoListener;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.PacketRegistrar;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.utils.anticheat.LogUtil;

public class PacketManager implements StartableInitable {

    @Override
    public void start() {
        LogUtil.info("Registering packets...");

        CultNetworkManager networkManager = CultAPI.INSTANCE.getNetworkManager();
        PacketRegistrar registrar = new PacketRegistrar(networkManager);

        networkManager.registerListener(new PacketPlayerJoinQuit());

        CheckManagerListener checkManagerListener = new CheckManagerListener();
        checkManagerListener.registerForwardingEarlyReceivePackets(registrar, PacketListenerPriority.LOW);

        BedrockAuthInputPluginMessageListener bedrockAuthInputPluginMessageListener = new BedrockAuthInputPluginMessageListener();
        registrar.registerReceiveListener(PacketListenerPriority.LOWEST, bedrockAuthInputPluginMessageListener);

        PacketConfigurationListener configurationListener = new PacketConfigurationListener();
        registrar.registerReceiveListener(PacketListenerPriority.NORMAL, configurationListener);

        PacketPingListener pingListener = new PacketPingListener();
        registrar.registerListener(PacketListenerPriority.LOWEST, pingListener);

        PacketPlayerWindow windowListener = new PacketPlayerWindow();
        registrar.registerListener(PacketListenerPriority.LOW, windowListener);

        PacketPlayerDigging diggingListener = new PacketPlayerDigging();
        registrar.registerReceiveListener(PacketListenerPriority.LOW, diggingListener);

        PacketPlayerAttack attackListener = new PacketPlayerAttack();
        registrar.registerReceiveListener(PacketListenerPriority.LOW, attackListener);

        PacketEntityAction entityActionListener = new PacketEntityAction();
        registrar.registerReceiveListener(PacketListenerPriority.LOW, entityActionListener);

        PacketServerTeleport teleportListener = new PacketServerTeleport();
        registrar.registerListener(PacketListenerPriority.LOW, teleportListener);

        registrar.registerReceiveListener(PacketListenerPriority.LOW, checkManagerListener);
        checkManagerListener.registerForwardingSendPackets(registrar, PacketListenerPriority.LOW);

        PacketPlayerSteer steerListener = new PacketPlayerSteer();
        registrar.registerReceiveListener(PacketListenerPriority.LOW, steerListener);

        PacketBlockAction blockActionListener = new PacketBlockAction();
        registrar.registerSendListener(PacketListenerPriority.HIGH, blockActionListener);

        PacketSelfMetadataListener selfMetadataListener = new PacketSelfMetadataListener();
        registrar.registerSendListener(PacketListenerPriority.HIGH, selfMetadataListener);

        PacketPlayerCooldown cooldownListener = new PacketPlayerCooldown();
        registrar.registerSendListener(PacketListenerPriority.HIGH, cooldownListener);

        PacketPlayerRespawn respawnListener = new PacketPlayerRespawn();
        registrar.registerSendListener(PacketListenerPriority.HIGH, respawnListener);

        PacketWorldReaderTwentySix worldReader = new PacketWorldReaderTwentySix();
        registrar.registerSendListener(PacketListenerPriority.HIGH, worldReader);

        PlayerInfoListener playerInfoListener = new PlayerInfoListener();
        // PacketHidePlayerInfo was pre-Via HIGHEST. Keep its replacement last so
        // compensation observes the real packet before spectate visibility rewrites it.
        registrar.registerSendListener(PacketListenerPriority.HIGHEST, playerInfoListener);

        // Proxy details may arrive before a CultPlayer exists.
        networkManager.registerReceiveTap(PacketListenerPriority.NORMAL, new PacketPluginMessage());
        registrar.registerSendListener(PacketListenerPriority.LOW, new PacketServerPlayerRotation());
        registrar.registerSendListener(PacketListenerPriority.NORMAL, new PacketServerRegistries());

        new ac.cult.cultac.events.packets.ProxyAlertMessenger();
    }
}
