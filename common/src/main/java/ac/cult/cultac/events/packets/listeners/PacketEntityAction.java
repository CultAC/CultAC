package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsQ;
import ac.cult.cultac.checks.impl.elytra.ElytraA;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.RidingJumpCharge;
import ac.cult.cultac.utils.data.SprintingState;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.inventory.ClientGlider;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.RiptideUtil;
import org.jetbrains.annotations.Nullable;

public class PacketEntityAction {

    // LOW
    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        // Bedrock commands have already been consumed at their movement phase.
        if (player.isBedrockMovement()) return;
        switch (packet.action()) {
            case START_SPRINTING:
                player.isSprinting = true;
                player.sprintClearedAsPassenger = false;
                player.vehicleData.camelSprintingState = SprintingState.STARTED;
                break;
            case STOP_SPRINTING:
                player.isSprinting = false;
                player.vehicleData.camelSprintingState = SprintingState.STOPPED;
                break;
            case START_FLYING_WITH_ELYTRA:
                boolean grounded = player.onGround || player.lastOnGround;
                // Only a TridentItem#releaseUsing spin lifts a player on ground before LocalPlayer#aiStep reads
                // onGround, so a glide start after a release on the ground follows a spin.
                boolean released = RiptideUtil.releasedOnTheGround(player);
                if (released) player.packetStateData.riptideReleaseCertain = true;
                if (grounded && !released
                        || lavaPreventsGlideActivation(player)
                        || RiptideUtil.glideStartsInWater(player)
                        || provenStateForbidsGlide(player)) {
                    rejectGlideStart(event, player);
                    break;
                }

                // ElytraA evaluates the pre-command glide state after the
                // grounded rejection, but before equipment validation.
                player.checkManager.getCheck(ElytraA.class).onStartGliding(event);

                if (canGlideUsingEquippedItem(player)) {
                    // The pose follows in Player#updatePlayerPose, after this tick's aiStep.
                    player.isGliding = true;
                } else {
                    rejectGlideStart(event, player);
                }
                break;
            case START_JUMPING_WITH_HORSE:
                PacketEntityNautilus nautilus = player.compensatedEntities.vehicles.getVelocityMovementVehicle()
                                instanceof PacketEntityNautilus value
                        ? value
                        : null;
                if (nautilus != null) {
                    // The release follows the nautilus's whole tick, whose dash and cooldown the tick's vehicle move
                    // and end commit, so the tick end judges AbstractNautilus#onPlayerJump after them.
                    nautilus.nextPendingJumpScale = packet.data() >= 90 ? 1.0F : 0.4F + 0.4F * packet.data() / 90.0F;
                    break;
                }
                PacketEntityHorse horse = player.compensatedEntities.vehicles.getClientVisibleHorseRoot();
                // Camel#onPlayerJump. The client releases the jump only if Camel#canJump holds after the camel's tick.
                if (horse instanceof PacketEntityCamel camel
                        && (!camel.hasSaddle || camel.dashCooldown > 0 || !camel.onGround)) {
                    break;
                }
                int data = packet.data();
                if (horse != null && !(horse instanceof PacketEntityCamel)) {
                    Integer charged = chargedReleaseData(event, player, packet);
                    if (charged != null && charged == RidingJumpCharge.NO_RELEASE) break;
                    if (charged != null) data = charged;
                }
                if (horse != null && horse.hasSaddle) {
                    // AbstractHorse#onPlayerJump takes a negative amount as 0, without standing the horse.
                    boolean stands = data >= 0;
                    data = Math.max(data, 0);
                    double jumpScale = data >= 90 ? 1.0F : 0.4F + 0.4F * data / 90.0F;
                    // MCP-Reborn ClientLevel#tickNonPassenger ticks the root
                    // horse before tickPassenger runs LocalPlayer#rideTick.
                    // LocalPlayer#aiStep calls onPlayerJump(...) during that
                    // later passenger tick, so START_RIDING_JUMP can only arm
                    // the next client horse tick, not the already-finished
                    // tick whose ServerboundMoveVehiclePacket is about to be
                    // serialized by LocalPlayer#tick. The same callback also
                    // sets AbstractHorse#allowStandSliding and calls
                    // standIfPossible() for that next tick.
                    horse.nextHorseJump = jumpScale;
                    horse.nextAllowStandSliding = stands;
                    // Before 1.21.5, Entity#isEffectiveAi is !level().isClientSide, so the client horse never stands.
                    if (stands && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)) {
                        horse.armClientRidingJumpStand();
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * The START_RIDING_JUMP data LocalPlayer#aiStep's charge lets this release send, when it differs from the packet's;
     * NO_RELEASE when no release can be sent now, null when the server receives the packet's data: it is that charge,
     * the charge is not known, or the packet is left unmodified.
     */
    @Nullable
    private static Integer chargedReleaseData(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) return null;
        Integer charged =
                player.ridingJumpCharge.releaseData(player.compensatedEntities.vehicles.ridingJumpable());
        if (charged == null || charged == packet.data()) return null;
        if (!player.checkManager.getCheck(BadPacketsQ.class).onReleaseBeyondCharge(packet.data(), charged)) {
            return null;
        }
        if (charged == RidingJumpCharge.NO_RELEASE) {
            event.setCancelled(true);
            player.onPacketCancel();
        } else {
            event.replace(new ServerboundPlayerCommand(packet.entityId(), packet.action(), charged));
        }
        return charged;
    }

    static boolean lavaPreventsGlideActivation(CultPlayer player) {
        // 26.3+
        if (!ClientFluidQueries.usesTagBasedFluidRules(player)) return false;
        // Entity#baseTick refreshes fluids before LocalPlayer#aiStep attempts
        // gliding. The previous prediction sampled the preceding tick's start.
        for (SimpleCollisionBox box : RiptideUtil.glideStartBoxes(player)) {
            if (ClientFluidQueries.depth(player, box, FluidTags.LAVA) <= 0.0D) return false;
        }
        return true;
    }

    /**
     * LivingEntity#canGlide: an effect or a vehicle applies at the ping that trails its packet, which the client answers
     * before its next tick, so the applied state is the client's.
     */
    private static boolean provenStateForbidsGlide(CultPlayer player) {
        return player.compensatedEntities.hasPotionEffect(MovementEffect.LEVITATION)
                || player.inVehicle();
    }

    private void rejectGlideStart(PacketReceiveEvent event, CultPlayer player) {
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) return;
        // A grounded start or ghost glider must be resynchronized.
        final SetbackTeleportUtil setbackUtil = player.getSetbackTeleportUtil();
        setbackUtil.executeForceResync("elytra");
        player.resyncPose();
        event.setCancelled(true);
        player.onPacketCancel();
    }

    private boolean canGlideUsingEquippedItem(CultPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            SimItemStack itemStack = getEquippedItem(player, slot);
            if (canGlideUsing(itemStack, slot)) {
                return true;
            }
        }

        return false;
    }

    private static boolean canGlideUsing(SimItemStack itemStack, EquipmentSlot slot) {
        return ClientGlider.canUse(itemStack, slot);
    }

    private SimItemStack getEquippedItem(CultPlayer player, EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> player.getInventory().getHeldItem();
            case OFFHAND -> player.getInventory().getOffHand();
            case FEET -> player.getInventory().getBoots();
            case LEGS -> player.getInventory().getLeggings();
            case CHEST -> player.getInventory().getChestplate();
            case HEAD -> player.getInventory().getHelmet();
            case BODY, SADDLE -> SimItemStack.EMPTY;
        };
    }
}
