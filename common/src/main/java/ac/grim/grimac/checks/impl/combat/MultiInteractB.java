package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

@CheckData(name = "MultiInteractB", stableKey = "grim.multiinteract.interact_at_position_changed", description = "Sent multiple entity interaction packets with different hit positions in one tick", experimental = true)
public class MultiInteractB extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("pos={f64}, {f64}, {f64}, lastPos={f64}, {f64}, {f64}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private final ArrayList<FlagData> flags = new ArrayList<>();
    private Vec3 lastPos;
    private boolean hasInteracted;

    public MultiInteractB(final GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onInteractEntity(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        NmsPacketUtil.InteractData data = NmsPacketUtil.readInteract(packet);
        if (data.action() != NmsPacketUtil.InteractAction.INTERACT_AT) return;

        Vec3 pos = data.target().orElse(null);
        if (pos == null) return; // shouldn't ever happen, but whatever

        if (!player.cameraEntity.isSelf()) {
            hasInteracted = false;
        }

        if (hasInteracted && !pos.equals(lastPos)) {
            if (!canSkipTicks()) {
                if (flag(V.write(verbose()).f64(pos.x).f64(pos.y).f64(pos.z).f64(lastPos.x).f64(lastPos.y).f64(lastPos.z))
                        && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(pos.x, pos.y, pos.z, lastPos.x, lastPos.y, lastPos.z));
            }
        }

        lastPos = pos;
        hasInteracted = true;
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            hasInteracted = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        if (!player.cameraEntity.isSelf()
                || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick)) {
            hasInteracted = false;
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                flag(V.write(verbose())
                        .f64(data.posX()).f64(data.posY()).f64(data.posZ())
                        .f64(data.lastPosX()).f64(data.lastPosY()).f64(data.lastPosZ()));
            }
        }

        flags.clear();
    }

    private boolean canSkipTicks() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                && !(player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2));
    }

    private record FlagData(
            double posX,
            double posY,
            double posZ,
            double lastPosX,
            double lastPosY,
            double lastPosZ) {}
}
