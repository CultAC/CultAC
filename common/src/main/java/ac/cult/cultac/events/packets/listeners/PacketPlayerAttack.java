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
import ac.cult.cultac.utils.data.ClientAttackStrength;
import ac.cult.cultac.utils.data.ClientCombatAttributes;
import ac.cult.cultac.utils.data.ClientSprintState.AttackReset;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.OptionalInt;

public class PacketPlayerAttack {

    // LOW
    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (packet.action() == InteractAction.ATTACK) {
            if (!player.isBedrockMovement()) {
                onLocalAttack(player, packet.entityId());
            } else if (player.compensatedEntities.getEntity(packet.entityId()) != null) {
                player.maxPlayerAttackSlow += 1;
            }
        }
    }

    // A damping certain from an attack after the tick's travel lands on the next travel's deltaMovement.
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        player.minPlayerAttackSlow = player.attackSlowsAfterTravel;
        player.attackSlowsAfterTravel = 0;
        player.travelPredictedThisClientTick = false;
        player.attackStrength.onClientTickEnd();
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        // Minecraft#startAttack resets on a miss before its main-hand swing (26.3: punch).
        // ClientPacketListener#handleAnimate's LocalPlayer#swing leaves the attack strength ticker as it is.
        if (packet.hand() == Hand.MAIN_HAND && !player.serverSwingEcho.isEcho()) {
            player.attackStrength.onPossibleMiss();
        }
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

    /**
     * Minecraft#startAttack attacks only the entity of an EntityHitResult, which Minecraft#pick finds among the
     * client's pickable entities. An id the client cannot hold, or a type it cannot pick, is no client attack.
     */
    private static void onLocalAttack(CultPlayer player, int entityId) {
        CompensatedEntities entities = player.compensatedEntities;
        PacketEntity held = entities.getEntity(entityId);
        if (held != null) {
            if (EntityTypeUtil.isPickable(held.type)) onLocalAttack(player, clientHurt(held.type), true);
            return;
        }
        int unproven = entities.unprovenEntityType(entityId);
        if (unproven >= 0) {
            if (EntityTypeUtil.isPickable(unproven)) onLocalAttack(player, possibleHurt(unproven), false);
            return;
        }
        // An EnderDragonPart is picked and hurts its dragon, which Entity#hurtClient leaves unhurt.
        OptionalInt dragon = entities.enderDragonOfPart(entityId);
        if (dragon.isPresent()) {
            onLocalAttack(player, AttackReset.NONE, entities.getEntity(dragon.getAsInt()) != null);
        }
    }

    /**
     * MultiPlayerGameMode#attack: Player#attack samples attack strength, then resetAttackStrengthTicker. An entity the
     * client has not proven it holds may not be the one the client attacked, so its reset is only possible.
     */
    private static void onLocalAttack(CultPlayer player, AttackReset hurt, boolean held) {
        player.attackStrength.onClaimedMainHand(player.getInventory().getHeldItem());
        player.attackStrength.onEntityAttack();
        AttackReset reset = attackReset(player, hurt);
        player.sprintState.onAttack(reset);
        if (reset != AttackReset.NONE) player.maxPlayerAttackSlow += 1;
        if (reset == AttackReset.CERTAIN) {
            player.minPlayerAttackSlow += 1;
            if (player.packetStateData.receivedMovementThisClientTick && player.travelPredictedThisClientTick) {
                player.attackSlowsAfterTravel += 1;
            }
        }
        if (held) {
            player.attackStrength.onReset();
        } else {
            player.attackStrength.onPossibleReset();
        }
    }

    /**
     * Player#attack reaches causeExtraKnockback, which multiplies X/Z movement by 0.6 and calls setSprinting(false),
     * when the target is attackable, the damage is positive, hurtClient succeeds and the client-side knockback is
     * positive: LivingEntity#getKnockback is attack_knockback / 2 on the client, plus 0.5 for a sprinting attack above
     * 0.9 strength. Combat attributes come from the client's bundle-proven server snapshots.
     */
    private static AttackReset attackReset(CultPlayer player, AttackReset hurt) {
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
                || EntityTypeUtil.isType(type, "leash_knot")
                || EntityTypeUtil.isType(type, "cushion")) {
            return AttackReset.POSSIBLE;
        }
        return AttackReset.NONE;
    }

    private static AttackReset possibleHurt(int type) {
        return clientHurt(type) == AttackReset.NONE ? AttackReset.NONE : AttackReset.POSSIBLE;
    }
}
