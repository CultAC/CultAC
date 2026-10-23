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

// Flight abilities apply at the preceding transaction, or after every earlier packet's state when no ping precedes
// them; item-use abilities apply at the trailing proof.
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
        boolean flying = packet.flying() && player.canFly;
        if (flying && !player.isFlying) player.packetStateData.flyingStartBeforeTravel = true;
        player.packetStateData.flyingStopAfterTravel = !flying && player.isFlying;
        if (player.packetStateData.flyingStopAfterTravel) player.packetStateData.flightStopThisClientTick = true;
        if (!player.packetStateData.flyingStopAfterTravel) player.isFlying = flying;
    }

    @CultPacketHandler
    public void onPlayerAbilities(
            PacketSendEvent<ClientboundPlayerAbilities> event, CultPlayer player, ClientboundPlayerAbilities packet) {
        boolean pingPrecedes = player.sendTransactionAndGetId() != -1;

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

        int preceding = player.lastTransactionSent.get();
        Runnable flight = () -> {
            player.canFly = packet.canFly();
            player.isFlying = packet.flying();
            player.flySpeed = packet.flyingSpeed();
        };
        // Without a ping ahead of it, the flight follows every earlier packet's state, such as LOGIN's game mode.
        if (pingPrecedes) player.latencyUtils.addRealTimeTask(preceding, flight);
        else player.latencyUtils.addRealTimeTaskNext(flight);

        // Player#canEat and the projectile ammo check read these at the client's USE_ITEM.
        if (!player.runAtTrailingProof(
                event, () -> player.abilitiesHandled(packet.instabuild(), packet.invulnerable()))) {
            player.latencyUtils.addRealTimeTask(
                    preceding, () -> player.abilitiesSent(packet.instabuild(), packet.invulnerable()));
        }
    }
}
