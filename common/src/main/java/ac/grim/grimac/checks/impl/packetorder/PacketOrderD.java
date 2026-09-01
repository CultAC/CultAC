package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.DecodedPacketReliability;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;

@CheckData(name = "PacketOrderD", stableKey = "grim.packetorder.interact_hand_order", description = "Sent offhand entity interaction before the matching mainhand interaction", experimental = true)
public class PacketOrderD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of(
            "[Skipped Mainhand|requiredEntity={sint}, entity={sint}, requiredSneaking={bool}, sneaking={bool}]");

    public PacketOrderD(final GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
    }

    private boolean sentMainhand;
    private int requiredEntity;
    private boolean requiredSneaking;

    @GrimPacketHandler
    public void onInteract(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
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
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!isApplicable()) return;
        if (!player.packetStateData.lastPacketWasTeleport) {
            sentMainhand = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        if (!isApplicable()) return;
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick) {
            sentMainhand = false;
        }
    }
}
