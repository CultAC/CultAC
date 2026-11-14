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
        // LocalPlayer#aiStep sends every player_abilities through onUpdateAbilities.
        player.packetStateData.localPlayerTickInputOrCommandThisClientTick = true;
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

        if (lastSentPlayerCanFly && !packet.canFly()) limitRevocation(player, player.lastTransactionSent.get());

        lastSentPlayerCanFly = packet.canFly();

        int preceding = player.lastTransactionSent.get();
        Runnable flight = () -> {
            player.canFly = packet.canFly();
            player.isFlying = packet.flying();
            player.flySpeed = packet.flyingSpeed();
        };
        // Player#canEat and the projectile ammo check read these at the client's USE_ITEM.
        Runnable handled = () -> player.abilitiesHandled(packet.instabuild(), packet.invulnerable());
        // ClientPacketListener#handlePlayerAbilities writes the flight and the build flags together, and
        // LocalPlayer#aiStep reads mayfly and flying from the next tick: a proof in the packet's bundle applies both.
        if (!player.runAtTrailingProof(
                event,
                () -> {
                    flight.run();
                    handled.run();
                },
                handled)) {
            // Unbundled, the flight follows the ping ahead of the packet, or without one every earlier packet's state,
            // such as LOGIN's game mode.
            player.latencyUtils.addRealTimeTask(pingPrecedes ? preceding : preceding + 1, flight);
            player.latencyUtils.addRealTimeTask(
                    preceding, () -> player.abilitiesSent(packet.instabuild(), packet.invulnerable()));
        }
    }

    /**
     * Movement the server no longer allows stays possible until the client answers {@code transaction}, the ping it
     * applies at. Left unanswered past max-ping-out-of-flying, the player is set back, without a flag, to where it was
     * when the change was sent; a value of 0 or less turns this off.
     */
    public static void limitRevocation(CultPlayer player, int transaction) {
        int maxFlyingPing = CultAPI.INSTANCE.getConfigManager().getConfig().getIntElse("max-ping-out-of-flying", 1000);
        if (maxFlyingPing <= 0) return;
        player.getSetbackTeleportUtil().holdSafePositionsUntil(transaction);
        player.nettyScheduler.runTaskInMs(
                () -> player.getSetbackTeleportUtil().revocationExpired(transaction, "flying"), maxFlyingPing);
    }
}
