package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;

// The client can send ability packets out of order due to Mojang's excellent netcode design.
// We must delay the second ability packet until the tick after the first is received
// Else the player will fly for a tick, and we won't know about it, which is bad.
public class PacketPlayerAbilities extends GrimProcessor implements CheckListener {

    public PacketPlayerAbilities(GrimPlayer player) {
        super(player);
    }

    boolean lastSentPlayerCanFly = false;

    @GrimPacketHandler
    public void onPlayerAbilities(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerAbilitiesPacket packet) {
        player.isFlying = packet.isFlying() && player.canFly;
    }

    @GrimPacketHandler
    public void onPlayerAbilities(PacketSendEvent event, GrimPlayer player, ClientboundPlayerAbilitiesPacket packet) {
        player.sendTransaction();

        if (lastSentPlayerCanFly && !packet.canFly()) {
            int noFlying = player.lastTransactionSent.get();
            int maxFlyingPing = GrimAPI.INSTANCE.getConfigManager().getConfig().getIntElse("max-ping-out-of-flying", 600);

            player.nettyScheduler.runTaskInMs(() -> {
                if (player.lastTransactionReceived.get() < noFlying) { player.getSetbackTeleportUtil().executeTooHighLatencySetback("flying"); }
            }, maxFlyingPing);
        }

        lastSentPlayerCanFly = packet.canFly();

        player.latencyUtils.addRealTimeTaskNow(() -> { player.canFly = packet.canFly();
            player.isFlying = packet.isFlying();
            player.canInstabuild = packet.canInstabuild();
            player.flySpeed = packet.getFlyingSpeed();
        });
    }
}
