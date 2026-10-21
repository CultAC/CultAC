package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.InventoryPackets.CreativeSlot;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.data.ClientAttackStrength;
import ac.cult.cultac.utils.data.ClientCombatAttributes;
import ac.cult.cultac.utils.data.ClientSprintState.AttackReset;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHurtingProjectile;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;

public class PacketPlayerAttack {

    // LOW
    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (packet.action() == InteractAction.ATTACK) {
            PacketEntity entity = player.compensatedEntities.getEntity(packet.entityId());
            if (!player.isBedrockMovement()) {
                onLocalAttack(player, entity);
            } else if (entity != null) {
                player.maxPlayerAttackSlow += 1;
            }
        }
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        player.minPlayerAttackSlow = 0;
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        // Minecraft#startAttack resets on a miss before its main-hand swing (26.3: punch).
        if (packet.hand() == Hand.MAIN_HAND) player.attackStrength.onPossibleReset();
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        switch (packet.action()) {
            // MultiPlayerGameMode#piercingAttack -> Player#onAttack
            case STAB -> player.attackStrength.onReset();
            case ABORT_DESTROY_BLOCK -> player.attackStrength.onPossibleReset();
            // Releasing use does not replace the main-hand item client-side. Offhand swaps are
            // server-authoritative; the client's key handler only sends the action packet.
            default -> {}
        }
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (packet.hand() == Hand.MAIN_HAND) {
            var item = player.getInventory().getHeldItem();
            var consumable = ItemComponents.consumable(item.components());
            // Timed consumption only starts use; completion requires the server's entity event 9.
            // Blocking and kinetic weapon use also leave the held item unchanged.
            if (consumable != null
                    ? consumable.ticks() > 0
                    : item.components().has("minecraft:blocks_attacks")
                            || item.components().has("minecraft:kinetic_weapon")) return;
            onMainHandUse(player);
        }
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        if (packet.hand() == Hand.MAIN_HAND) onMainHandUse(player);
    }

    @CultPacketHandler
    public void onCreativeSlot(PacketReceiveEvent<CreativeSlot> event, CultPlayer player, CreativeSlot packet) {
        // Only hotbar edits can change the selected item; count/components alone cannot reset it.
        if (packet.slot() >= 36
                && packet.slot() <= 44
                && !player.getInventory()
                        .inventory
                        .getInventoryStorage()
                        .getItem(packet.slot())
                        .itemKey()
                        .equals(packet.item().itemKey())) {
            player.attackStrength.onPossibleReset();
        }
    }

    // Instant item use can replace the last main-hand item before the server confirms it.
    private static void onMainHandUse(CultPlayer player) {
        if (player.getInventory().getHeldItem().getCount() <= 1) {
            player.attackStrength.onUnconfirmedMainHandChange(player.packetStateData.lastSlotSelected);
        }
    }

    // MultiPlayerGameMode#attack: Player#attack samples attack strength, then resetAttackStrengthTicker.
    private static void onLocalAttack(CultPlayer player, PacketEntity target) {
        player.attackStrength.onClaimedMainHand(player.getInventory().getHeldItem());
        AttackReset reset = attackReset(player, target);
        player.sprintState.onAttack(reset);
        if (reset != AttackReset.NONE) player.maxPlayerAttackSlow += 1;
        if (reset == AttackReset.CERTAIN) player.minPlayerAttackSlow += 1;
        player.attackStrength.onReset();
        if (target instanceof PacketEntityHurtingProjectile projectile)
            projectile.onLocalAttack(new ClientEntityQueries(player));
    }

    /**
     * Player#attack reaches causeExtraKnockback, which multiplies X/Z movement by 0.6 and calls setSprinting(false),
     * when the target is attackable, the damage is positive, hurtClient succeeds and the client-side knockback is
     * positive: LivingEntity#getKnockback is attack_knockback / 2 on the client, plus 0.5 for a sprinting attack above
     * 0.9 strength. Combat attributes come from the client's bundle-proven server snapshots.
     */
    private static AttackReset attackReset(CultPlayer player, PacketEntity target) {
        AttackReset hurt = target == null ? AttackReset.POSSIBLE : clientHurt(target.type);
        if (hurt == AttackReset.NONE) return AttackReset.NONE;

        ClientCombatAttributes current = player.combatAttributes;
        float delay = ClientAttackStrength.delay(current.attackSpeed());
        // The sprint model holds while mounted, where LocalPlayer#tick does not send the flag.
        boolean sprintKnown = player.sprintState.flagKnown()
                && !player.compensatedEntities.getSelf().inVehicle();
        boolean sprinting = player.sprintState.flag() || !sprintKnown;

        boolean knockback = current.attackKnockback() > 0.0;
        if (!knockback && !(sprinting && player.attackStrength.canBeFullStrength(delay))) {
            return AttackReset.NONE;
        }

        boolean sprintKnockbackCertain =
                sprintKnown && player.sprintState.flag() && player.attackStrength.isFullStrength(delay);
        boolean damageCertain = current.attackDamage() > 0.0;
        return hurt == AttackReset.CERTAIN && damageCertain && (knockback || sprintKnockbackCertain)
                ? AttackReset.CERTAIN
                : AttackReset.POSSIBLE;
    }

    /**
     * Entity#hurtClient is false unless overridden. RemotePlayer and VehicleEntity return true and keep Entity's
     * isAttackable/skipAttackInteraction; EndCrystal, ItemFrame, BlockAttachedEntity and ShulkerBullet (a possible
     * deflected projectile) depend on more state. ItemEntity and ExperienceOrb are not attackable.
     */
    private static AttackReset clientHurt(int type) {
        if (EntityTypeUtil.isType(type, "player") || EntityTypeUtil.isBoat(type) || EntityTypeUtil.isMinecart(type)) {
            return AttackReset.CERTAIN;
        }
        if (EntityTypeUtil.isType(type, "shulker_bullet")
                || EntityTypeUtil.isType(type, "end_crystal")
                || EntityTypeUtil.isType(type, "item_frame")
                || EntityTypeUtil.isType(type, "glow_item_frame")
                || EntityTypeUtil.isType(type, "painting")
                || EntityTypeUtil.isType(type, "leash_knot")) {
            return AttackReset.POSSIBLE;
        }
        return AttackReset.NONE;
    }
}
