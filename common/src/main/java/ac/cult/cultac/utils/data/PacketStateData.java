package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.math.Vec3;

// This is to keep all the packet data out of the main player class
// Helps clean up the player class and makes devs aware they are sync'd to the netty thread
public class PacketStateData {
    // A completed server configuration installs a new Java PLAY listener/player.
    public boolean javaLocalPlayerPresent;
    public boolean packetPlayerOnGround = false;
    public boolean lastPacketWasTeleport = false;
    // lastPacketWasTeleport lasts one packet; this keeps whether any packet of the client tick accepted a teleport.
    public boolean teleportedThisClientTick = false;
    public boolean lastPacketMatchedTeleportPosition = false;
    // Minecraft#tick sends ServerboundClientTickEndPacket after LocalPlayer#tick, proving whether movement happened.
    public boolean receivedMovementThisClientTick = false;
    // A flight stop LocalPlayer#aiStep may have sent after travel on landing; that tick still travelled flying.
    public boolean flyingStopAfterTravel = false;
    // A flight start LocalPlayer#aiStep sent this client tick, ahead of its player_input and move.
    public boolean flyingStartBeforeTravel = false;
    // A flight stop this client tick's player_abilities sent.
    public boolean flightStopThisClientTick = false;
    // LocalPlayer#updateAutoJump can have set autoJumpTime for the next client tick.
    public boolean autoJumpPossible = false;
    // The jump key of the last client tick LocalPlayer#aiStep ran.
    public boolean lastTickJump = false;
    // Bit t: Player#jumpTriggerTime can be t at the next LocalPlayer#aiStep flight toggle check.
    public int jumpTriggerTimes = 1;
    // Entity#isSwimming can be true from the last client tick's baseTick on; the server's entity data overwrites it.
    public boolean swimmingPossible = false;
    // Entity#wasEyeInWater at the next client tick's baseTick: the eye fluid at the last client tick's start.
    public boolean eyeInWaterBeforeTick = false;
    // Geyser forwards each auth frame before its movement. A horse move
    // precedes the rider's rotation; tick-end closes frames without movement.
    public final BedrockTranslatedMovementGate bedrockTranslatedMovement = new BedrockTranslatedMovementGate();
    // Geyser sent AcceptTeleportation; its teleport PosRot is the next MovePlayer.
    public boolean bedrockServerResponse;
    private Boolean desiredOnGround;
    // ClientPacketListener#hasClientLoaded: cleared by login and respawn, set with ServerboundPlayerLoadedPacket.
    // LocalPlayer#tick only runs while it is set. A connection attached mid-session starts loaded.
    public boolean playerLoadedIntoLevel = true;
    // A tick end of a client tick that ran LocalPlayer#tick came since the last login or respawn. Java attacks before
    // it are cancelled: ServerGamePacketListenerImpl#hasClientLoaded drops them on vanilla.
    public boolean localPlayerTickedSinceSpawn = true;
    public int lastSlotSelected;
    // Packet-ordered mirror of LocalPlayer's active item state.  Checks must not
    // read Bukkit's server-thread active item because the attack/use ordering is
    // decided on the Netty packet stream.
    public Hand itemInUseHand = Hand.MAIN_HAND;
    private int slowedByUsingItemSlot = Integer.MIN_VALUE;
    public KnownInput knownInput = KnownInput.DEFAULT;
    public int riptideLevel = 0;
    public boolean riptideReleaseCertain = false;
    public int acceptedClientTick = 0;
    public int riptideUseStartClientTick = Integer.MIN_VALUE;
    public Hand riptideUseHand = null;
    public boolean invalidRiptideRelease = false;
    public String invalidRiptideReleaseReason = "";
    public float serverTickRate = 20.0F;
    public boolean serverTicksFrozen = false;
    public int serverFrozenTickStepsRemaining = 0;
    // LocalPlayer#tick sends changed raw input before the mounted Rot/MoveVehicle pair.
    public boolean clientMovementInputKnown = false;
    public boolean clientMovementInputUpdatedThisClientTick = false;
    public Vec3 clientMovementInput = new Vec3(0, 0, 0);
    public Vec3 clientSidePosition = new Vec3(0, 0, 0);
    // LocalPlayer#xLast: the position of the client's last tick movement packet, unknown until one arrives.
    public Vec3 lastSentTickPosition = null;
    // The rotation of the client's last tick movement packet that carried one, NaN while unknown. A new LocalPlayer
    // starts LocalPlayer#yRotLast and xRotLast at zero.
    public float lastSentTickYaw = Float.NaN;
    public float lastSentTickPitch = Float.NaN;
    // LocalPlayer#yRotLast and xRotLast, written only by sendPosition: the rotation of the last tick movement packet
    // that carried one outside passenger Rot, NaN while unknown.
    public float lastSendPositionYaw = Float.NaN;
    public float lastSendPositionPitch = Float.NaN;
    // LocalPlayer#tick emits passenger Rot immediately before MoveVehicle; packet-handler echoes send MoveVehicle
    // alone.
    private boolean awaitingVehicleMoveAfterPassengerRotation = false;
    private boolean mountedPassengerRotationSeenThisClientTick = false;
    private boolean vehicleMovementFromClientTick = false;
    public int vehicleMovePacketsThisClientTick = 0;
    public int clientTickVehicleMovePacketsThisClientTick = 0;
    public int localAuthoritativeVehicleMovePacketsThisClientTick = 0;
    public boolean carriedItemChangedThisClientTick = false;
    public boolean carriedItemClaimedThisClientTick = false;
    public boolean vehicleMovementStartOnGround = false;
    // Original-client-reported ground, excluding fields synthesized by protocol translation.
    public boolean vehicleMovementOnGroundPresent = true;
    public Vec3 lastPacketProvenVehiclePhysicalMovement = null;
    // Transaction observed at the last tick-end packet; used to prove tick-boundary state ordering.
    public int lastClientTickEndTransaction = Integer.MIN_VALUE;

    public boolean isVehicleMovementFromClientTick() {
        return vehicleMovementFromClientTick;
    }

    public void stageDesiredOnGround(boolean onGround) {
        desiredOnGround = onGround;
    }

    public Boolean consumeDesiredOnGround() {
        Boolean onGround = desiredOnGround;
        desiredOnGround = null;
        return onGround;
    }

    public void clearDesiredOnGround() {
        desiredOnGround = null;
    }

    public boolean hasPassengerRotationThisClientTick() {
        return mountedPassengerRotationSeenThisClientTick;
    }

    public boolean isAwaitingVehicleMoveAfterPassengerRotation() {
        return awaitingVehicleMoveAfterPassengerRotation;
    }

    public void markPassengerRotation() {
        mountedPassengerRotationSeenThisClientTick = true;
        awaitingVehicleMoveAfterPassengerRotation = true;
    }

    public void markVehicleMove(boolean fromClientTick, boolean localAuthoritative) {
        vehicleMovePacketsThisClientTick++;
        if (localAuthoritative) {
            localAuthoritativeVehicleMovePacketsThisClientTick++;
        }
        vehicleMovementFromClientTick = fromClientTick && localAuthoritative;
        awaitingVehicleMoveAfterPassengerRotation = false;
    }

    public void clearPendingVehicleMoveAfterPassengerRotation() {
        awaitingVehicleMoveAfterPassengerRotation = false;
        vehicleMovementFromClientTick = false;
    }

    public void clearMountedMovementTickState() {
        awaitingVehicleMoveAfterPassengerRotation = false;
        mountedPassengerRotationSeenThisClientTick = false;
        vehicleMovementFromClientTick = false;
    }

    // For future checks
    public float lastHealth, lastSaturation;
    public int lastFood;

    // Client-visible login/game-event option used by the death/respawn state machine.
    public boolean showsDeathScreen = true;

    public boolean lastServerTransWasValid = false;

    public void setSlowedByUsingItem(boolean slowedByUsingItem) {
        slowedByUsingItemSlot = slowedByUsingItem ? lastSlotSelected : Integer.MIN_VALUE;
    }

    public boolean isSlowedByUsingItem() {
        return slowedByUsingItemSlot != Integer.MIN_VALUE;
    }

    public int getSlowedByUsingItemSlot() {
        return slowedByUsingItemSlot;
    }
}
