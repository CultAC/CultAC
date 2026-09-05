package ac.cult.cultac.checks.impl.movement;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.data.PacketStateData;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.phys.Vec3;

public class SetbackBlocker extends CultProcessor implements CheckListener {
    public SetbackBlocker(CultPlayer playerData) {
        super(playerData);
    }

    private void handleMovePlayer(final PacketReceiveEvent event, ServerboundMovePlayerPacket movePacket,
                                  boolean translatedBedrockMovement) {
        // This is transport integrity, not a check-level setback decision.
        // Real Geyser emits at most one MovePlayer projection for each
        // PlayerAuthInputPacket, followed by ClientTickEnd. Consume that one
        // permit for every projection shape (including Rot/StatusOnly) before
        // any configuration or disabled-player early return can bypass it.
        if (translatedBedrockMovement) {
            PacketStateData.BedrockTranslatedMovementAuthorization authorization =
                    player.packetStateData.consumeBedrockTranslatedMovementPermit();
            if (authorization == null
                    || authorization.decision() == PacketStateData.BedrockTranslatedMovementDecision.REJECT) {
                event.setCancelled(true);
            } else if (authorization.expectedProjectedGround() != movePacket.isOnGround()) {
                // The client cannot author this Java packet; a mismatch means
                // the pinned Geyser projection semantics changed or ordering
                // was corrupted. Fail the integration closed without turning
                // server drift into a player violation.
                event.setCancelled(true);
                LogUtil.warn("Discarded mismatched Geyser movement projection for "
                        + player.getName() + " at Bedrock tick " + authorization.clientTick());
            } else {
                player.packetStateData.stageBedrockTranslatedCanonicalGround(
                        authorization.canonicalGround());
            }
        }

        // A pending Bedrock correction must contain every projected position,
        // including packets already queued by Geyser before the flag occurred.
        if (translatedBedrockMovement
                && movePacket.hasPosition()
                && player.getSetbackTeleportUtil().isPendingSetback()) {
            event.setCancelled(true);
        }

        // Protocol integrity is independent of check/setback configuration.
        // LocalPlayer#tick emits only Rot (and, for a locally authoritative
        // root, MoveVehicle) while mounted. Combine the immediate local mount
        // marker with the transaction-delayed passenger graph so ordinary
        // movement cannot cross either side of a mount/dismount boundary.
        // Packet-handler teleport acknowledgements have already set teleport.
        boolean teleport = player.packetStateData.lastPacketWasTeleport;
        if (!player.isBedrockMovement()
                && !teleport
                && player.compensatedEntities.vehicles.hasPlayerPassengerState()
                && !(movePacket instanceof ServerboundMovePlayerPacket.Rot)) {
            event.setCancelled(true);
        }

        if (player.isDisabled()) { return; } // Let's avoid letting people disable cult with cult.nomodifypackets
        if (!player.shouldEnforceMovementSetbacks()) return;

        NmsPacketUtil.MovePlayerData wrapper = NmsPacketUtil.readMovePlayer(movePacket, player);
        // The player must obey setbacks
        if (!teleport && wrapper.hasPositionChanged()
                && player.getSetbackTeleportUtil().shouldBlockMovement()) {
            if (player.getSetbackTeleportUtil().isDebug()) { LogUtil.info(player.getName() + " movement has been blocked! : " + player.getSetbackTeleportUtil().getDebugStrings()); }
            event.setCancelled(true);
        }

        if (!teleport && player.isInBed && wrapper.hasPositionChanged()) {
            Vec3 bedPosition = player.bedPosition;
            if (bedPosition == null
                    || Math.max(Math.abs(wrapper.x() - bedPosition.x), Math.abs(wrapper.z() - bedPosition.z)) > 0.5D
                    || Math.abs(wrapper.y() - bedPosition.y) >= 0.1D) {
                event.setCancelled(true);
            }
        }

        // Player is dead
        if (!teleport && player.compensatedEntities.getSelf().isDead) {
            event.setCancelled(true);
        }
    }

    private void handleMoveVehicle(final PacketReceiveEvent event) {
        if (player.isDisabled()) { return; } // Let's avoid letting people disable cult with cult.nomodifypackets

        boolean teleport = player.packetStateData.lastPacketWasTeleport;
        Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
        int ridingVehicle = player.getRidingVehicleId();

        // The client must not move a vehicle unless the server currently has the player mounted.
        if (serverVehicle == null) {
            event.setCancelled(true);
        }

        // If a player is in a different vehicle than what the server is telling them is the right one, ignore it
        // Mojang doesn't handle this scenario when over 50 ms of ping or so... great job mojang
        //
        // This won't patch any bypasses, but it will patch an annoying vanilla bug.
        // The client can send its first vehicle move before Cult's delayed passenger task runs.
        if (serverVehicle != null && ridingVehicle != Integer.MIN_VALUE && serverVehicle != ridingVehicle) {
            event.setCancelled(true);
        }

        // A client can intentionally delay transaction responses while continuing to send
        // movement. Only block this for Cult-owned vehicle setbacks; ordinary server
        // vehicle teleports may be pending while the client keeps moving old state.
        if (!teleport && player.getSetbackTeleportUtil().hasUnacknowledgedSetbackVehicleTeleport()) {
            event.setCancelled(true);
        }

        if (!teleport && player.getSetbackTeleportUtil().shouldBlockVehicleMovement()) {
            event.setCancelled(true);
        }

        // A player is sleeping while in a vehicle
        if (!teleport && player.isInBed) {
            event.setCancelled(true);
        }

        // Player is dead
        if (!teleport && player.compensatedEntities.getSelf().isDead) {
            event.setCancelled(true);
        }

    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer(event, packet, false);
    }

    /**
     * Applies the shared movement gate to Geyser's Java projection without
     * treating that projection as a second prediction input. The raw auth-input
     * plugin message must already have completed on this client tick, and a
     * pending setback always blocks position updates even in an unloaded chunk.
     */
    public void onTranslatedBedrockMove(PacketReceiveEvent event, ServerboundMovePlayerPacket packet) {
        handleMovePlayer(event, packet, true);
    }

    @CultPacketHandler
    public void onMoveVehicle(PacketReceiveEvent event, CultPlayer player, ServerboundMoveVehiclePacket packet) {
        handleMoveVehicle(event);
    }
}
