package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;

@CheckData(
        name = "BadPacketsJ",
        stableKey = "cult.badpackets.use_item_rotation_mismatch",
        description = "Rotation in use item packet did not match tick rotation")
public class BadPacketsJ extends Check implements DecodedPacketReceiveListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    private float yaw;
    private float pitch;
    private int rotations;

    public BadPacketsJ(CultPlayer player) {
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

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (!isApplicable()) return;
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        final float yaw = packet.yaw();
        final float pitch = packet.pitch();

        if (this.rotations > 0 && (this.yaw != yaw || this.pitch != pitch)) {
            if (!player.canSkipTicks() || this.yaw != player.xRot || this.pitch != player.yRot) {
                while (this.rotations-- > 0) flag();
            }
            this.rotations = 0;
        }

        if (this.rotations != Integer.MAX_VALUE) this.rotations++;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    // isTickPacket: movement packets count unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!isApplicable()) return;
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        if (this.rotations > 0 && !player.packetStateData.lastPacketWasTeleport) {
            onTickPacket(player, packet.hasRotation());
        }
    }

    // isTickPacket: tick end counts for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
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

    private void onTickPacket(CultPlayer player, boolean rotationPacket) {
        if (this.yaw != player.xRot || this.pitch != player.yRot) {

            boolean allowLast = player.canSkipTicks() && rotationPacket;
            if (!allowLast || this.yaw != player.lastTickXRot || this.pitch != player.lastTickYRot) {
                while (this.rotations-- > 0) flag();
            }
        }
        this.rotations = 0;
    }
}
