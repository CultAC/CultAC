package ac.cult.cultac.manager.player;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import org.bukkit.inventory.ItemStack;
import org.bukkit.GameMode;
import lombok.Getter;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.entity.player.Input;
import org.bukkit.Material;

@Getter
public class ActionManager extends CultProcessor implements CheckListener, ClientTickEndListener {
    private static final DataComponentType<?> BLOCKS_ATTACKS_COMPONENT = findDataComponent("BLOCKS_ATTACKS");

    private boolean attacking = false;
    private boolean digging = false;
    private boolean blocking = false;
    private boolean releasing = false;

    private int blockingTicks = 0;

    public long lastAttack = 0;

    public int flyingPacketsSinceAttack;

    public long lastFlyingPacketTime;

    private int targetId = 0;
    private int lastTargetId = 0;

    private PacketEntity target = null;

    public ActionManager(CultPlayer cultPlayer) { super(cultPlayer); }

    private InteractionHand hand;

    private NmsPacketUtil.InteractAction lastAction = null;

    private void handleInteract(NmsPacketUtil.InteractData interact) {
        if (interact != null) {
            if (interact.action() == NmsPacketUtil.InteractAction.ATTACK) {
                this.lastAction = interact.action();
                this.lastTargetId = this.targetId;
                this.targetId = interact.entityId();
                this.target = player.compensatedEntities.getEntity(this.targetId);
                this.flyingPacketsSinceAttack = 0;
                this.attacking = true;
                this.lastAttack = System.currentTimeMillis();
            }
        }
    }

    private void handleUseItem(ServerboundUseItemPacket packet) {
        hand = NmsPacketUtil.readUseItem(packet).hand();
        ItemStack heldStack = player.getInventory().getHandItem(hand);
        if (heldStack == null) heldStack = ItemStack.empty();

        this.blocking = heldStack.getType() == Material.SHIELD
                || (player.getClientVersion().isOlderThan(ClientVersion.V_1_9) && heldStack.getType().name().endsWith("_SWORD"));

        if (canStartUsingItem(heldStack)) {
            player.packetStateData.setSlowedByUsingItem(true);
            player.packetStateData.itemInUseHand = hand;
        } else if (!player.packetStateData.isSlowedByUsingItem()
                || player.packetStateData.itemInUseHand == hand) {
            // A rejected use in the other hand does not stop an existing use.
            player.packetStateData.setSlowedByUsingItem(false);
        }
    }

    private void handlePlayerAction(ServerboundPlayerActionPacket packet) {
        if (NmsPacketUtil.readPlayerAction(packet).action() == Action.RELEASE_USE_ITEM) {
            this.releasing = true;
            this.blocking = false;
            player.packetStateData.setSlowedByUsingItem(false);
        }
    }

    private boolean canStartUsingItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9) && player.checkManager.getCompensatedCooldown().hasItem(stack))) {
            return false;
        }

        ClientVersion version = player.getClientVersion();
        net.minecraft.world.item.ItemStack nms = SpigotConversionUtil.toNmsItemStack(stack);

        if (version.isNewerThanOrEquals(ClientVersion.V_1_21_4)) {
            if (stack.getType() == Material.GOAT_HORN
                    || stack.getType() == Material.SHIELD
                    || stack.getType() == Material.SPYGLASS) {
                return true;
            }
            if (stack.getType() == Material.BOW || stack.getType() == Material.CROSSBOW) {
                // Baseline deliberately does not infer projectile availability.
                return false;
            }
            if (stack.getType() == Material.TRIDENT) {
                return !nms.nextDamageWillBreak()
                        && stack.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.RIPTIDE) <= 0;
            }

            Consumable consumable = nms.get(DataComponents.CONSUMABLE);
            if (consumable != null) {
                FoodProperties food = nms.get(DataComponents.FOOD);
                return consumable.consumeSeconds() > 0.0F
                        && (food == null || food.canAlwaysEat() || player.food < 20
                        || player.gamemode == GameMode.CREATIVE);
            }

            Equippable equippable = nms.get(DataComponents.EQUIPPABLE);
            return (equippable == null || !equippable.swappable())
                    && BLOCKS_ATTACKS_COMPONENT != null
                    && nms.has(BLOCKS_ATTACKS_COMPONENT);
        }

        // The compact legacy path retained by this fork. These are the baseline
        // items whose use can be proven without guessing projectile inventory.
        Material material = stack.getType();
        // 1.8 ItemSword#onItemRightClick sets a 72000-tick BLOCK use action.
        if (version.isOlderThan(ClientVersion.V_1_9) && material.name().endsWith("_SWORD")) return true;
        if (material == Material.SHIELD || material == Material.SPYGLASS || material == Material.GOAT_HORN) {
            return true;
        }
        if (material == Material.TRIDENT) {
            return !nms.nextDamageWillBreak()
                    && stack.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.RIPTIDE) <= 0;
        }
        if (material == Material.BOW || material == Material.CROSSBOW) {
            return false;
        }

        Consumable consumable = nms.get(DataComponents.CONSUMABLE);
        FoodProperties food = nms.get(DataComponents.FOOD);
        return consumable != null && consumable.consumeSeconds() > 0.0F
                && (food == null || food.canAlwaysEat() || player.food < 20
                || player.gamemode == GameMode.CREATIVE);
    }

    private static DataComponentType<?> findDataComponent(String fieldName) {
        try {
            return (DataComponentType<?>) DataComponents.class.getField(fieldName).get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @CultPacketHandler
    public void onInteract(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        handleInteract(NmsPacketUtil.readInteract(packet));
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        handleInteract(NmsPacketUtil.readAttack(packet));
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemPacket packet) {
        handleUseItem(packet);
    }

    @CultPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        handlePlayerAction(packet);
    }

    @CultPacketHandler
    public void onPlayerInput(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerInputPacket packet) {
        Input input = packet.input();
        player.packetStateData.knownInput = new ac.cult.cultac.utils.data.KnownInput(
                input.forward(), input.backward(), input.left(), input.right(),
                input.jump(), input.shift(), input.sprint());
    }

    @CultPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, CultPlayer player, ServerboundSetCarriedItemPacket packet) {
        if (hand != null && hand != InteractionHand.OFF_HAND) {
            this.blocking = false;
        }
    }

    @CultPacketHandler
    public void onMovePlayerPos(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket.Pos packet) {
        clearMainHandUseAfterSlotChange();
    }

    @CultPacketHandler
    public void onMovePlayerRot(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket.Rot packet) {
        clearMainHandUseAfterSlotChange();
    }

    @CultPacketHandler
    public void onMovePlayerPosRot(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket.PosRot packet) {
        clearMainHandUseAfterSlotChange();
    }

    @CultPacketHandler
    public void onMovePlayerStatusOnly(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket.StatusOnly packet) {
        clearMainHandUseAfterSlotChange();
    }

    @Override
    public void onPlayerTickEnd(final PacketReceiveEvent event) {
        clearMainHandUseAfterSlotChange();
        ++player.totalFlyingPacketsSent;
        ++this.flyingPacketsSinceAttack;
        this.attacking = false;
        this.digging = false;
        this.releasing = false;
        this.lastFlyingPacketTime = System.currentTimeMillis();
        //
        if (this.blocking) {
            ++this.blockingTicks;
        } else {
            this.blockingTicks = 0;
        }
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket")
    public void onSetHeldSlot(ac.cult.cultac.network.event.PacketSendEvent event, CultPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        this.blocking = false;
        player.packetStateData.setSlowedByUsingItem(false);
    }

    public boolean attackedWithin(long windowMs) {
        return System.currentTimeMillis() - this.lastAttack < windowMs;
    }

    public void onRespawn() {
        this.target = null;
        this.lastTargetId = 0;
        this.targetId = 0;
        this.blocking = false;
        player.packetStateData.setSlowedByUsingItem(false);
    }

    private void clearMainHandUseAfterSlotChange() {
        if (player.packetStateData.isSlowedByUsingItem()
                && player.packetStateData.itemInUseHand == InteractionHand.MAIN_HAND
                && player.packetStateData.getSlowedByUsingItemSlot() != player.packetStateData.lastSlotSelected) {
            player.packetStateData.setSlowedByUsingItem(false);
        }
    }

}
