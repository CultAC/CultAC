package ac.cult.cultac.manager.player;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import lombok.Getter;

@Getter
public class ActionManager extends CultProcessor implements CheckListener, ClientTickEndListener {

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

    public ActionManager(CultPlayer cultPlayer) {
        super(cultPlayer);
    }

    private Hand hand;

    private InteractAction lastAction = null;

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

        this.blocking = heldStack.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.SHIELD
                || (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)
                        && ac.cult.cultac.utils.inventory.ItemUtil.name(heldStack.getItem())
                                .endsWith("_SWORD"));

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
        this.releasing = true;
        this.blocking = false;
        player.packetStateData.setSlowedByUsingItem(false);
    }

    public void attack(int entityId) {
        this.lastAction = InteractAction.ATTACK;
        this.lastTargetId = this.targetId;
        this.targetId = entityId;
        this.target = player.compensatedEntities.getEntity(this.targetId);
        this.flyingPacketsSinceAttack = 0;
        this.attacking = true;
        this.lastAttack = System.currentTimeMillis();
    }

    private boolean canStartUsingItem(SimItemStack stack) {
        if (stack == null
                || stack.isEmpty()
                || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                        && player.checkManager.getCompensatedCooldown().hasItem(stack))) {
            return false;
        }

        ClientVersion version = player.getClientVersion();
        var nms = stack;

        if (version.isNewerThanOrEquals(ClientVersion.V_1_21_4)) {
            if (stack.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.GOAT_HORN
                    || stack.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.SHIELD
                    || stack.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.SPYGLASS) {
                return true;
            }
            if (stack.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.BOW
                    || stack.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.CROSSBOW) {
                // Baseline deliberately does not infer projectile availability.
                return false;
            }
            if (stack.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.TRIDENT) {
                return !nms.nextDamageWillBreak()
                        && ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(stack, "minecraft:riptide") <= 0;
            }

            var consumable = ac.cult.blocksim.data.ItemComponents.consumable(nms.components());
            if (consumable != null) {
                var food = ac.cult.blocksim.data.ItemComponents.food(nms.components());
                return consumable.consumeSeconds() > 0.0F
                        && (food == null
                                || food.canAlwaysEat()
                                || player.food < 20
                                || player.gamemode == GameMode.CREATIVE);
            }

            var equippable = ac.cult.blocksim.data.ItemComponents.equippable(nms.components());
            return (equippable == null || !equippable.swappable())
                    && nms.components().has("minecraft:blocks_attacks");
        }

        // The compact legacy path retained by this fork. These are the baseline
        // items whose use can be proven without guessing projectile inventory.
        ac.cult.blocksim.data.ItemDefinition material = stack.getItem();
        // 1.8 ItemSword#onItemRightClick sets a 72000-tick BLOCK use action.
        if (version.isOlderThan(ClientVersion.V_1_9)
                && ac.cult.cultac.utils.inventory.ItemUtil.name(material).endsWith("_SWORD")) return true;
        if (material == ac.cult.cultac.utils.inventory.ItemTypes.SHIELD
                || material == ac.cult.cultac.utils.inventory.ItemTypes.SPYGLASS
                || material == ac.cult.cultac.utils.inventory.ItemTypes.GOAT_HORN) {
            return true;
        }
        if (material == ac.cult.cultac.utils.inventory.ItemTypes.TRIDENT) {
            return !nms.nextDamageWillBreak()
                    && ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(stack, "minecraft:riptide") <= 0;
        }
        if (material == ac.cult.cultac.utils.inventory.ItemTypes.BOW
                || material == ac.cult.cultac.utils.inventory.ItemTypes.CROSSBOW) {
            return false;
        }

        var consumable = ac.cult.blocksim.data.ItemComponents.consumable(nms.components());
        var food = ac.cult.blocksim.data.ItemComponents.food(nms.components());
        return consumable != null
                && consumable.consumeSeconds() > 0.0F
                && (food == null || food.canAlwaysEat() || player.food < 20 || player.gamemode == GameMode.CREATIVE);
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
        player.packetStateData.knownInput = new ac.cult.cultac.utils.data.KnownInput(
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

    public void selectHotbarSlot() {
        if (hand != null && hand != Hand.OFF_HAND) {
            this.blocking = false;
        }
    }

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

    @CultPacketHandler
    public void onSetHeldSlot(
            ac.cult.cultac.network.event.PacketSendEvent<ClientboundSetHeldSlot> event,
            CultPlayer player,
            ClientboundSetHeldSlot packet) {
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
                && player.packetStateData.itemInUseHand == Hand.MAIN_HAND
                && player.packetStateData.getSlowedByUsingItemSlot() != player.packetStateData.lastSlotSelected) {
            player.packetStateData.setSlowedByUsingItem(false);
        }
    }
}
