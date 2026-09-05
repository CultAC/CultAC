package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;

// The client can send ability packets out of order due to Mojang's excellent netcode design.
// We must delay the second ability packet until the tick after the first is received
// Else the player will fly for a tick, and we won't know about it, which is bad.
public class PacketPlayerAbilities extends CultProcessor implements CheckListener {

    public PacketPlayerAbilities(CultPlayer player) {
        super(player);
    }

    boolean lastSentPlayerCanFly = false;

    @CultPacketHandler
    public void onPlayerAbilities(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerAbilitiesPacket packet) {
        player.isFlying = packet.isFlying() && player.canFly;
    }

    @CultPacketHandler
    public void onPlayerAbilities(PacketSendEvent event, CultPlayer player, ClientboundPlayerAbilitiesPacket packet) {
        player.sendTransaction();

        if (lastSentPlayerCanFly && !packet.canFly()) {
            int noFlying = player.lastTransactionSent.get();
            int maxFlyingPing = CultAPI.INSTANCE.getConfigManager().getConfig().getIntElse("max-ping-out-of-flying", 600);

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
