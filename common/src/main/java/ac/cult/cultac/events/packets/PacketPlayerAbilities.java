package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAbilities;

// Apply server abilities at the existing transaction boundary.
// Track the last sent permission separately from compensated state so a
// revocation can schedule its timeout immediately.
public class PacketPlayerAbilities extends CultProcessor implements CheckListener {

    public PacketPlayerAbilities(CultPlayer player) {
        super(player);
    }

    boolean lastSentPlayerCanFly = false;

    @CultPacketHandler
    public void onPlayerAbilities(
            PacketReceiveEvent<ServerboundPlayerAbilities> event,
            CultPlayer player,
            ServerboundPlayerAbilities packet) {
        if (player.isBedrockMovement()) return;
        player.isFlying = packet.flying() && player.canFly;
    }

    @CultPacketHandler
    public void onPlayerAbilities(
            PacketSendEvent<ClientboundPlayerAbilities> event, CultPlayer player, ClientboundPlayerAbilities packet) {
        player.sendTransaction();

        if (lastSentPlayerCanFly && !packet.canFly()) {
            int noFlying = player.lastTransactionSent.get();
            int maxFlyingPing =
                    CultAPI.INSTANCE.getConfigManager().getConfig().getIntElse("max-ping-out-of-flying", 600);

            player.nettyScheduler.runTaskInMs(
                    () -> {
                        if (player.lastTransactionReceived.get() < noFlying) {
                            player.getSetbackTeleportUtil().executeTooHighLatencySetback("flying");
                        }
                    },
                    maxFlyingPing);
        }

        lastSentPlayerCanFly = packet.canFly();

        player.latencyUtils.addRealTimeTaskNow(() -> {
            player.canFly = packet.canFly();
            player.isFlying = packet.flying();
            player.canInstabuild = packet.instabuild();
            player.isInvulnerable = packet.invulnerable();
            player.flySpeed = packet.flyingSpeed();
        });
    }
}
