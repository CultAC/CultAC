package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.checks.impl.elytra.ElytraA;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.utils.data.SprintingState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

public class PacketEntityAction {

    //LOW
    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
        switch (packet.getAction()) {
                case START_SPRINTING:
                    player.isSprinting = true;
                    player.vehicleData.camelSprintingState = SprintingState.STARTED;
                    // MCP-Reborn LocalPlayer#tick sends START_SPRINTING before the
                    // movement packet, and ServerGamePacketListenerImpl#handlePlayerCommand
                    // immediately calls LivingEntity#setSprinting, which adds the
                    // sprinting movement-speed modifier for that same movement.
                    player.compensatedEntities.hasSprintingAttributeEnabled = true;
                    break;
                case STOP_SPRINTING:
                    player.isSprinting = false;
                    player.vehicleData.camelSprintingState = SprintingState.STOPPED;
                    // STOP_SPRINTING removes the same transient modifier immediately
                    // on the server before any following movement packet is handled.
                    player.compensatedEntities.hasSprintingAttributeEnabled = false;
                    break;
                case START_FALL_FLYING:
                    if (player.onGround || player.lastOnGround) {
                        rejectGlideStart(event, player);
                        break;
                    }

                    // ElytraA evaluates the pre-command glide state after the
                    // grounded rejection, but before equipment validation.
                    // This direct callback bypasses CheckManager's Bedrock
                    // support filter, so retain that boundary explicitly.
                    if (!player.isBedrockMovement()) {
                        player.checkManager.getCheck(ElytraA.class).onStartGliding(event);
                    }

                    if (canGlideUsingEquippedItem(player)) {
                        player.isGliding = true;
                        player.refreshPlayerPose();
                    } else {
                        rejectGlideStart(event, player);
                    }
                    break;
                case START_RIDING_JUMP:
                    PacketEntityNautilus nautilus = player.compensatedEntities.vehicles.getVelocityMovementVehicle() instanceof PacketEntityNautilus value
                            ? value : null;
                    if (nautilus != null && nautilus.hasSaddle && nautilus.dashCooldown <= 0) {
                        nautilus.nextPendingJumpScale = packet.getData() >= 90
                                ? 1.0F
                                : 0.4F + 0.4F * packet.getData() / 90.0F;
                        break;
                    }
                    PacketEntityHorse horse = player.compensatedEntities.vehicles.getClientVisibleHorseRoot();
                    if (horse instanceof PacketEntityCamel camel
                            && (!camel.hasSaddle || camel.dashCooldown > 0 || !camel.onGround)) {
                        break;
                    }
                    if (horse != null && horse.hasSaddle) {
                        double jumpScale = packet.getData() >= 90
                                ? 1.0F
                                : 0.4F + 0.4F * packet.getData() / 90.0F;
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
                        horse.nextAllowStandSliding = true;
                        horse.armClientRidingJumpStand();
                    }
                    break;
                default:
                    break;
            }
    }

    private void rejectGlideStart(PacketReceiveEvent event, CultPlayer player) {
        // A grounded start or ghost glider must be resynchronized.
        final SetbackTeleportUtil setbackUtil = player.getSetbackTeleportUtil();
        setbackUtil.executeForceResync("elytra");
        final org.bukkit.entity.Player bukkitPlayer = player.bukkitPlayer;
        if (bukkitPlayer != null) {
            // Client ignores sneaking, use it to resync
            bukkitPlayer.setSneaking(!bukkitPlayer.isSneaking());
        }
        event.setCancelled(true);
        player.onPacketCancel();
    }

    private boolean canGlideUsingEquippedItem(CultPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            net.minecraft.world.item.ItemStack itemStack = SpigotConversionUtil.toNmsItemStack(getEquippedItem(player, slot));
            if (canGlideUsing(itemStack, slot)) {
                return true;
            }
        }

        return false;
    }

    private static boolean canGlideUsing(net.minecraft.world.item.ItemStack itemStack, EquipmentSlot slot) {
        try {
            return (boolean) LivingEntity.class
                    .getMethod("canGlideUsing", net.minecraft.world.item.ItemStack.class, EquipmentSlot.class)
                    .invoke(null, itemStack, slot);
        } catch (NoSuchMethodException ignored) {
            // Before data-component gliders, the client only accepted a usable
            // elytra in the chest equipment slot.
            if (slot != EquipmentSlot.CHEST) {
                return false;
            }
            try {
                Class<?> elytraItem = Class.forName("net.minecraft.world.item.ElytraItem");
                return (boolean) elytraItem
                        .getMethod("isFlyEnabled", net.minecraft.world.item.ItemStack.class)
                        .invoke(null, itemStack);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to evaluate legacy elytra", exception);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to evaluate equipped glider", exception);
        }
    }

    private ItemStack getEquippedItem(CultPlayer player, EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> player.getInventory().getHeldItem();
            case OFFHAND -> player.getInventory().getOffHand();
            case FEET -> player.getInventory().getBoots();
            case LEGS -> player.getInventory().getLeggings();
            case CHEST -> player.getInventory().getChestplate();
            case HEAD -> player.getInventory().getHelmet();
            case BODY, SADDLE -> ItemStack.empty();
        };
    }
}
