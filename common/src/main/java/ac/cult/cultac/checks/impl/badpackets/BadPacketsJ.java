package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CameraDetachListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.ForwardHold;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.nmsutil.BoatRiderYaw;

@CheckData(
        name = "BadPacketsJ",
        stableKey = "cult.badpackets.use_item_rotation_mismatch",
        description = "Rotation in use item packet did not match tick rotation")
public class BadPacketsJ extends Check implements CameraDetachListener, ClientTickEndListener {
    private static final String MINECART_IMPROVEMENTS = "minecraft:minecart_improvements";
    private float yaw;
    private float pitch;
    private int rotations;

    public BadPacketsJ(CultPlayer player) {
        super(player);
    }

    @Override
    public void onCameraDetached() {
        judged(false);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (!player.cameraEntity.isSelf()) {
            judged(false);
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

        if (this.rotations > 0 && (this.yaw != yaw || this.pitch != pitch)) judged(flagRotations());

        if (this.rotations != Integer.MAX_VALUE) this.rotations++;
        this.yaw = yaw;
        this.pitch = pitch;
        if (holdsUses()) hold().gate();
    }

    // isTickPacket: movement packets count unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.cameraEntity.isSelf()) {
            judged(false);
            return;
        }

        // A boat rider's Rot carries the turn of the vehicle move the client sends after it, judged at tick end
        if (this.rotations > 0 && !event.answersTeleport() && riddenBoat() == null) {
            judged(onTickPacket(player.xRot, player.yRot));
        }
    }

    // isTickPacket: tick end closes the client tick, also for a movement packet cancelled before this check saw it.
    // Runs after the riding tree's interpolation step, which gives a ridden boat the yaw positionRider clamped against.
    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        // The timeout's tick end ends no client tick: the held use waits for its own tick's rotation
        if (event.isSynthetic() && hold().gating()) return;
        judgeTickEnd();
    }

    /** A tick end another check rejects still ends the client tick its uses were made in. */
    public void onRejectedTickEnd() {
        if (this.rotations == 0) return;
        PacketEntity boat = riddenBoat();
        // The comparison reads the riding step that only an accepted tick end runs
        if (boat != null && turnsRider(boat)) {
            ended(shouldModifyPackets());
            return;
        }
        judgeTickEnd();
    }

    private void judgeTickEnd() {
        if (!player.cameraEntity.isSelf()) {
            judged(false);
            return;
        }

        if (this.rotations > 0) {
            boolean flagged = false;
            // The client sends no tick movement, so no rotation, until ClientPacketListener#hasClientLoaded
            if (player.localPlayerTicks()) {
                // LocalPlayer#sendChanges sends Rot every riding tick, so a tick without movement ran sendPosition,
                // which sends no rotation while the player's equals the last one it sent
                if (player.packetStateData.receivedMovementThisClientTick) {
                    flagged = onTickPacket(
                            player.packetStateData.lastSentTickYaw, player.packetStateData.lastSentTickPitch);
                } else {
                    flagged = onTickPacket(
                            player.packetStateData.lastSendPositionYaw, player.packetStateData.lastSendPositionPitch);
                }
            } else if (holdsAnsweredLook()) {
                flagged = (this.yaw != player.xRot || this.pitch != player.yRot) && flagRotations();
            }
            judged(flagged);
        }
    }

    // Until ClientPacketListener#hasClientLoaded the loading screen keeps the mouse from turning the player and
    // LocalPlayer#tick sends nothing: the player keeps the look its last teleport answer reported
    private boolean holdsAnsweredLook() {
        PacketEntitySelf self = player.compensatedEntities.getSelf();
        return !player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)
                && player.packetStateData.teleportLookOf == self
                && !self.isRemovedFromLevel()
                && self.getRiding() == null;
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
    private boolean onTickPacket(float tickYaw, float tickPitch) {
        PacketEntity boat = riddenBoat();
        float expectedYaw = boat != null && turnsRider(boat) ? player.yawWithoutLook(this.yaw) : this.yaw;
        boolean yawDiffers = expectedYaw != tickYaw && !minecartMayTurnRider();
        return !Float.isNaN(tickYaw) && (yawDiffers || this.pitch != tickPitch) && flagRotations();
    }

    // From 26.3 Minecart#positionRider turns the rider's yaw, never its pitch, before LocalPlayer#sendChanges when the
    // minecart improvements are enabled and the client's rotateWithMinecart option, which it never sends, is on
    private boolean minecartMayTurnRider() {
        PacketEntity vehicle = player.compensatedEntities.getSelf().getRiding();
        return vehicle != null
                && vehicle.isMinecart()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                && player.compensatedWorld.clientHasFeature(MINECART_IMPROVEMENTS);
    }

    private boolean flagRotations() {
        boolean flagged = false;
        while (this.rotations-- > 0) flagged |= flag();
        return flagged;
    }

    /** Ends the use_items since the last tick rotation; the held ones reach the server unless they were flagged. */
    private void judged(boolean flagged) {
        ended(flagged && shouldModifyPackets());
    }

    private void ended(boolean drop) {
        this.rotations = 0;
        if (!hold().gating()) return;
        hold().settle(!drop);
        if (drop) player.onPacketCancel();
    }

    // Minecraft#tick ends every client tick with ServerboundClientTickEndPacket, which bounds the hold
    private boolean holdsUses() {
        return shouldModifyPackets()
                && !player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && player.user.getCultConnection().packets().observed().supports(ServerboundPackets.CLIENT_TICK_END);
    }

    private ForwardHold hold() {
        return player.user.getCultConnection().forwardHold();
    }
}
