package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "PacketOrderD",
        stableKey = "cult.packetorder.interact_hand_order",
        description = "Sent offhand entity interaction before the matching mainhand interaction",
        experimental = true)
public class PacketOrderD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of(
            "[Skipped Mainhand|requiredEntity={sint}, entity={sint}, requiredSneaking={bool}, sneaking={bool}]");

    public PacketOrderD(final CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
    }

    private boolean sentMainhand;
    private int requiredEntity;
    private boolean requiredSneaking;

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (!isApplicable()
                || !DecodedPacketReliability.interactionFamilyReliable(
                        player.getClientVersion(), player.getObservedProtocol())) return;

        final InteractAction action = packet.action();
        if (action == InteractAction.ATTACK) return;

        final boolean sneaking = packet.sneaking();
        final int entity = packet.entityId();

        if (packet.hand() == Hand.OFF_HAND) {
            if (action == InteractAction.INTERACT
                    || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)) {
                if (!sentMainhand) {
                    if (flag(V.write(verbose())
                                    .bool(true) // skipped mainhand
                                    .sint(0)
                                    .sint(0)
                                    .bool(false)
                                    .bool(false))
                            && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                }
                sentMainhand = false;
            }

            if (action == InteractAction.INTERACT_AT) {
                if (sneaking != requiredSneaking || entity != requiredEntity) {
                    if (flag(V.write(verbose())
                                    .bool(false) // mismatch
                                    .sint(requiredEntity)
                                    .sint(entity)
                                    .bool(requiredSneaking)
                                    .bool(sneaking))
                            && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                }
            }
        } else {
            requiredEntity = entity;
            requiredSneaking = sneaking;
            sentMainhand = true;
        }
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!isApplicable()) return;
        if (!player.packetStateData.lastPacketWasTeleport) {
            sentMainhand = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!isApplicable()) return;
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick) {
            sentMainhand = false;
        }
    }
}
