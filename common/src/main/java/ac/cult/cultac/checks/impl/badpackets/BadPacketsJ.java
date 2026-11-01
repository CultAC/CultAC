package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.BoatRiderYaw;

@CheckData(
        name = "BadPacketsJ",
        stableKey = "cult.badpackets.use_item_rotation_mismatch",
        description = "Rotation in use item packet did not match tick rotation")
public class BadPacketsJ extends Check implements DecodedPacketReceiveListener, ClientTickEndListener {
    private float yaw;
    private float pitch;
    private int rotations;

    public BadPacketsJ(CultPlayer player) {
        super(player);
    }

    @Override
    public void onDecodedPacketReceive(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
        }
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        final float yaw = packet.yaw();
        final float pitch = packet.pitch();

        // Entity#turn ends a boat rider's mouse turn with AbstractBoat#clampRotation against the boat's yaw, which only
        // the client tick after this use_item changes; without a mouse turn the yaw is the one the client last sent.
        PacketEntity boat = riddenBoat();
        if (boat != null
                && yaw != player.xRot
                && (drives(boat) || boat.clientPhysicalYawCurrent)
                && !BoatRiderYaw.canHold(yaw, boat.clientPhysicalYaw)) {
            flag();
        }

        if (this.rotations > 0 && (this.yaw != yaw || this.pitch != pitch)) {
            while (this.rotations-- > 0) flag();
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
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        // A boat rider's Rot carries the turn of the vehicle move the client sends after it, judged at tick end
        if (this.rotations > 0 && !player.packetStateData.lastPacketWasTeleport && riddenBoat() == null) {
            onTickPacket(player.xRot, player.yRot);
        }
    }

    // isTickPacket: tick end closes the client tick, also for a movement packet cancelled before this check saw it.
    // Runs after the riding tree's interpolation step, which gives a ridden boat the yaw positionRider clamped against.
    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        if (this.rotations > 0) {
            // The client sends no tick movement, so no rotation, until ClientPacketListener#hasClientLoaded
            if (CheckManagerListener.localPlayerTicked(player)) {
                // LocalPlayer#sendChanges sends Rot every riding tick, so a tick without movement ran sendPosition,
                // which sends no rotation while the player's equals the last one it sent
                if (player.packetStateData.receivedMovementThisClientTick) {
                    onTickPacket(player.packetStateData.lastSentTickYaw, player.packetStateData.lastSentTickPitch);
                } else {
                    onTickPacket(
                            player.packetStateData.lastSendPositionYaw, player.packetStateData.lastSendPositionPitch);
                }
            } else {
                this.rotations = 0;
            }
        }
    }

    private PacketEntity riddenBoat() {
        PacketEntity vehicle = player.compensatedEntities.getSelf().getRiding();
        return vehicle != null && vehicle.isBoat() ? vehicle : null;
    }

    private boolean drives(PacketEntity boat) {
        return !boat.passengers.isEmpty() && boat.passengers.get(0) == player.compensatedEntities.getSelf();
    }

    // AbstractBoat#controlBoat turns a driven boat by deltaRotation; LinearInterpolationHandler turns another one.
    // Before 26.3 LocalPlayer#tick sends the mounted Rot, before Entity#rideTick runs AbstractBoat#positionRider.
    private boolean turnsRider(PacketEntity boat) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) return false;
        return drives(boat) ? player.boatYawFollowsTurn() : boat.clientPhysicalYawCurrent;
    }

    // Minecraft#tick sends use_item from handleKeybinds, then AbstractBoat#positionRider turns a rider in
    // ClientLevel#tickEntities before LocalPlayer#sendChanges sends the tick's rotation
    private void onTickPacket(float tickYaw, float tickPitch) {
        PacketEntity boat = riddenBoat();
        float expectedYaw = boat != null && turnsRider(boat) ? player.yawWithoutLook(this.yaw) : this.yaw;
        if (!Float.isNaN(tickYaw) && (expectedYaw != tickYaw || this.pitch != tickPitch)) {
            while (this.rotations-- > 0) flag();
        }
        this.rotations = 0;
    }
}
