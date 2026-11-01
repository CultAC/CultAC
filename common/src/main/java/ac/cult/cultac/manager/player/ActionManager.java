package ac.cult.cultac.manager.player;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.data.ItemDefinition;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.ItemUseInventory;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.nmsutil.RiptideUtil;
import lombok.Getter;

@Getter
public class ActionManager extends CultProcessor implements CheckListener, ClientTickEndListener {

    public long lastAttack = 0;

    public long lastFlyingPacketTime;

    public ActionManager(CultPlayer cultPlayer) {
        super(cultPlayer);
    }

    private Hand hand;

    private void handleInteract(ServerboundInteract interact) {
        if (interact != null) {
            if (interact.action() == InteractAction.ATTACK) {
                attack(interact.entityId());
            }
        }
    }

    public void useItem(Hand usedHand) {
        hand = usedHand;
        SimItemStack heldStack = player.getInventory().getHandItem(hand);
        if (heldStack == null) heldStack = SimItemStack.EMPTY;

        if (player.usesModernJavaItemUse()) {
            // ClientBlockActions owns modern action prediction, including bow ammo,
            // cooldowns and component-driven uses. Trident retains its existing path.
            if (heldStack.is("minecraft:trident") && canStartUsingItem(heldStack))
                player.clientItemUse.start(
                        hand,
                        heldStack,
                        ClientItemUseState.duration(heldStack),
                        !ItemUseInventory.possiblyRefused(player, hand)
                                && (RiptideUtil.getRiptideLevel(heldStack) <= 0
                                        || RiptideUtil.inWaterOrRain(player).determinePessimistically()));
            return;
        }
        if (canStartUsingItem(heldStack)) {
            player.packetStateData.setSlowedByUsingItem(true);
            player.packetStateData.itemInUseHand = hand;
        } else if (!player.packetStateData.isSlowedByUsingItem() || player.packetStateData.itemInUseHand == hand) {
            // A rejected use in the other hand does not stop an existing use.
            player.packetStateData.setSlowedByUsingItem(false);
        }
    }

    private void handlePlayerAction(ServerboundPlayerAction packet) {
        if (packet.action() == PlayerAction.RELEASE_USE_ITEM) {
            releaseItem();
        }
    }

    public void releaseItem() {
        if (player.usesModernJavaItemUse()) player.clientItemUse.release();
        player.packetStateData.setSlowedByUsingItem(false);
    }

    public void attack(int entityId) {
        this.lastAttack = System.currentTimeMillis();
    }

    private boolean canStartUsingItem(SimItemStack stack) {
        if (stack == null
                || stack.isEmpty()
                || player.checkManager.getCompensatedCooldown().hasItem(stack)) {
            return false;
        }

        ClientVersion version = player.getClientVersion();
        var nms = stack;

        if (version.isNewerThanOrEquals(ClientVersion.V_1_21_4)) {
            if (stack.getItem() == ItemTypes.GOAT_HORN
                    || stack.getItem() == ItemTypes.SHIELD
                    || stack.getItem() == ItemTypes.SPYGLASS) {
                return true;
            }
            if (stack.getItem() == ItemTypes.BOW || stack.getItem() == ItemTypes.CROSSBOW) {
                // Projectile availability is never inferred.
                return false;
            }
            if (stack.getItem() == ItemTypes.TRIDENT) {
                // TridentItem#use refuses a riptide trident only while !Player#isInWaterOrRain.
                return !nms.nextDamageWillBreak()
                        && (ItemUtil.enchantmentLevel(stack, "minecraft:riptide") <= 0
                                || RiptideUtil.inWaterOrRain(player).determineOptimistically());
            }

            var consumable = ItemComponents.consumable(nms.components());
            if (consumable != null) {
                var food = ItemComponents.food(nms.components());
                return consumable.consumeSeconds() > 0.0F
                        && (food == null || food.canAlwaysEat() || player.food < 20 || player.isInvulnerable);
            }

            var equippable = ItemComponents.equippable(nms.components());
            return (equippable == null || !equippable.swappable())
                    && nms.components().has("minecraft:blocks_attacks");
        }

        // Items whose use can be proven without guessing projectile inventory.
        ItemDefinition material = stack.getItem();
        if (material == ItemTypes.SHIELD || material == ItemTypes.SPYGLASS || material == ItemTypes.GOAT_HORN) {
            return true;
        }
        if (material == ItemTypes.TRIDENT) {
            // TridentItem#use refuses a riptide trident only while !Player#isInWaterOrRain.
            return !nms.nextDamageWillBreak()
                    && (ItemUtil.enchantmentLevel(stack, "minecraft:riptide") <= 0
                            || RiptideUtil.inWaterOrRain(player).determineOptimistically());
        }
        if (material == ItemTypes.BOW || material == ItemTypes.CROSSBOW) {
            return false;
        }

        var consumable = ItemComponents.consumable(nms.components());
        var food = ItemComponents.food(nms.components());
        return consumable != null
                && consumable.consumeSeconds() > 0.0F
                && (food == null || food.canAlwaysEat() || player.food < 20 || player.isInvulnerable);
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        handleInteract(packet);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        useItem(packet.hand());
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        handlePlayerAction(packet);
    }

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        ServerboundPlayerInput input = packet;
        player.packetStateData.knownInput = new KnownInput(
                input.forward(),
                input.backward(),
                input.left(),
                input.right(),
                input.jump(),
                input.shift(),
                input.sprint());
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        selectHotbarSlot();
    }

    public void selectHotbarSlot() {}

    @CultPacketHandler
    public void onMovePlayerPos(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        clearMainHandUseAfterSlotChange();
    }

    @Override
    public void onPlayerTickEnd(final PacketReceiveEvent event) {
        endClientTick();
    }

    public void endClientTick() {
        clearMainHandUseAfterSlotChange();
        ++player.totalFlyingPacketsSent;
        this.lastFlyingPacketTime = System.currentTimeMillis();
    }

    @CultPacketHandler
    public void onSetHeldSlot(
            PacketSendEvent<ClientboundSetHeldSlot> event, CultPlayer player, ClientboundSetHeldSlot packet) {
        player.itemUseBurst.serverHeldSlot(packet.slot());
        if (!player.usesModernJavaItemUse()) player.packetStateData.setSlowedByUsingItem(false);
    }

    private void resetItemUse() {
        player.clientItemUse.reset();
        player.getItemUseDesync().reset();
        player.itemUseBurst.reset();
    }

    public void onRespawn() {
        resetItemUse();
        player.packetStateData.setSlowedByUsingItem(false);
    }

    /** The Java client constructed another player; accumulated anti-abuse accounting survives. */
    public void onJavaPlayerReplacement() {
        player.clientItemUse.reset();
        player.clientItemUse.onNewLocalPlayer();
        player.checkManager.getCompensatedCooldown().clearPossibleCooldowns();
        // A LocalPlayer's FoodData starts full.
        player.food = 20;
        player.packetStateData.setSlowedByUsingItem(false);
    }

    private void clearMainHandUseAfterSlotChange() {
        if (player.usesModernJavaItemUse()) return;
        if (player.packetStateData.isSlowedByUsingItem()
                && player.packetStateData.itemInUseHand == Hand.MAIN_HAND
                && player.packetStateData.getSlowedByUsingItemSlot() != player.packetStateData.lastSlotSelected) {
            player.packetStateData.setSlowedByUsingItem(false);
        }
    }
}
