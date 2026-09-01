package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.DecodedPacketReceiveListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

@CheckData(name = "BadPacketsJ", stableKey = "grim.badpackets.use_item_rotation_mismatch", description = "Rotation in use item packet did not match tick rotation")
public class BadPacketsJ extends Check implements DecodedPacketReceiveListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private float yaw;
    private float pitch;
    private int rotations;

    public BadPacketsJ(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21); // PE ServerVersion.V_1_21
    }

    @Override
    public void onDecodedPacketReceive(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
        }
    }

    @GrimPacketHandler
    public void onUseItem(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemPacket packet) {
        if (!isApplicable()) return;
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        final NmsPacketUtil.UseItemData data = NmsPacketUtil.readUseItem(packet);
        final float yaw = data.yaw();
        final float pitch = data.pitch();

        if (this.rotations > 0 && (this.yaw != yaw || this.pitch != pitch)) {
            if (!player.canSkipTicks() || this.yaw != player.xRot || this.pitch != player.yRot) {
                while (this.rotations-- > 0) flag();
            }
            this.rotations = 0;
        }

        if (this.rotations != Integer.MAX_VALUE)
            this.rotations++;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    // isTickPacket: movement packets count unless they answered a teleport
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!isApplicable()) return;
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        if (this.rotations > 0 && !player.packetStateData.lastPacketWasTeleport) {
            onTickPacket(player, packet instanceof ServerboundMovePlayerPacket.PosRot
                    || packet instanceof ServerboundMovePlayerPacket.Rot);
        }
    }

    // isTickPacket: tick end counts for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        if (!isApplicable()) return;
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        if (this.rotations > 0
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick) {
            onTickPacket(player, false);
        }
    }

    private void onTickPacket(GrimPlayer player, boolean rotationPacket) {
        if (this.yaw != player.xRot || this.pitch != player.yRot) {

            boolean allowLast = player.canSkipTicks() && rotationPacket;
            if (!allowLast || this.yaw != player.lastTickXRot || this.pitch != player.lastTickYRot) {
                while (this.rotations-- > 0) flag();
            }
        }
        this.rotations = 0;
    }
}
