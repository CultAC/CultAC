package ac.cult.cultac.checks.impl.packetorder;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;

@CheckData(name = "PacketOrderD", stableKey = "cult.packetorder.interact_hand_order", description = "Sent offhand entity interaction before the matching mainhand interaction", experimental = true)
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
    public void onInteract(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        if (!isApplicable()
                || !DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;

        final NmsPacketUtil.InteractData data = NmsPacketUtil.readInteract(packet);
        final NmsPacketUtil.InteractAction action = data.action();
        if (action == NmsPacketUtil.InteractAction.ATTACK) return;

        final boolean sneaking = data.sneaking();
        final int entity = data.entityId();

        if (data.hand() == InteractionHand.OFF_HAND) {
            if (action == NmsPacketUtil.InteractAction.INTERACT || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)) {
                if (!sentMainhand) {
                    if (flag(V.write(verbose())
                            .bool(true) // skipped mainhand
                            .sint(0)
                            .sint(0)
                            .bool(false)
                            .bool(false)) && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                }
                sentMainhand = false;
            }

            if (action == NmsPacketUtil.InteractAction.INTERACT_AT) {
                if (sneaking != requiredSneaking || entity != requiredEntity) {
                    if (flag(V.write(verbose())
                            .bool(false) // mismatch
                            .sint(requiredEntity)
                            .sint(entity)
                            .bool(requiredSneaking)
                            .bool(sneaking)) && shouldModifyPackets()) {
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
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (!isApplicable()) return;
        if (!player.packetStateData.lastPacketWasTeleport) {
            sentMainhand = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        if (!isApplicable()) return;
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick) {
            sentMainhand = false;
        }
    }
}
