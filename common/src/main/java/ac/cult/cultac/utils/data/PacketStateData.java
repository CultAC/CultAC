package ac.cult.cultac.utils.data;

import net.minecraft.world.phys.Vec3;

// This is to keep all the packet data out of the main player class
// Helps clean up the player class and makes devs aware they are sync'd to the netty thread
public class PacketStateData {
    public boolean packetPlayerOnGround = false;
    public boolean lastPacketWasTeleport = false;
    // Vanilla 1.17 through 1.20.6 can emit a redundant PosRot packet at the
    // movement threshold. It carries look state, but is not another movement.
    public boolean lastPacketWasOnePointSeventeenDuplicate = false;
    public boolean lastPacketMatchedTeleportPosition = false;
    // If true, the player's rotation was forced to the horse's rotation only on 1.13-
    public boolean horseInteractCausedForcedRotation = false;
    // Minecraft#tick sends ServerboundClientTickEndPacket after LocalPlayer#tick, proving whether movement happened.
    public boolean receivedMovementThisClientTick = false;
    // Geyser forwards each auth frame before its movement. A horse move
    // precedes the rider's rotation; tick-end closes frames without movement.
    public final BedrockTranslatedMovementGate bedrockTranslatedMovement = new BedrockTranslatedMovementGate();
    public boolean bedrockServerResponse;
    private Boolean desiredOnGround;
    // ServerboundPlayerLoadedPacket is the client-visible post-load boundary for modern clients.
    public boolean playerLoadedIntoLevel = false;
    public int lastSlotSelected;
    // Packet-ordered mirror of LocalPlayer's active item state.  Checks must not
    // read Bukkit's server-thread active item because the attack/use ordering is
    // decided on the Netty packet stream.
    public net.minecraft.world.InteractionHand itemInUseHand = net.minecraft.world.InteractionHand.MAIN_HAND;
    private int slowedByUsingItemSlot = Integer.MIN_VALUE;
    public KnownInput knownInput = KnownInput.DEFAULT;
    public int riptideLevel = 0;
    public int acceptedClientTick = 0;
    public int riptideUseStartClientTick = Integer.MIN_VALUE;
    public net.minecraft.world.InteractionHand riptideUseHand = null;
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
    // LocalPlayer#tick emits passenger Rot immediately before MoveVehicle; packet-handler echoes send MoveVehicle alone.
    private boolean awaitingVehicleMoveAfterPassengerRotation = false;
    private boolean mountedPassengerRotationSeenThisClientTick = false;
    private boolean vehicleMovementFromClientTick = false;
    public int vehicleMovePacketsThisClientTick = 0;
    public int clientTickVehicleMovePacketsThisClientTick = 0;
    public int localAuthoritativeVehicleMovePacketsThisClientTick = 0;
    public boolean carriedItemChangedThisClientTick = false;
    public boolean vehicleMovementStartOnGround = false;
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
