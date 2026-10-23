package ac.cult.cultac.checks.impl.breaking;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import ac.grim.grimac.api.storage.verbose.Verbose;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@CheckData(
        name = "AirLiquidBreak",
        stableKey = "cult.breaking.air_liquid_break",
        description = "Breaking a block that cannot be broken")
public class AirLiquidBreak extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("block={block}, type={digging}");

    // The client never digs with a piercing weapon in hand, but sends START before its carried slot, so a
    // START with a spear in the server-side hand waits for the first packet sent after the slot is.
    private int pendingStarts;
    private int pendingStartBlock;
    private boolean pendingStartTickEnded;

    private int lastTick;
    private boolean didLastFlag;
    // Initialize to non-null values to prevent NPE when checking for blockType properties and if position equals old
    // position
    private @NotNull BlockPos lastBreakLoc = BlockPos.ZERO;
    private int lastBlockType = BlockIds.AIR.defaultState();

    public AirLiquidBreak(CultPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action != PlayerAction.START_DESTROY_BLOCK
                && blockBreak.action != PlayerAction.STOP_DESTROY_BLOCK) return;

        boolean flagged = checkBreak(blockBreak);
        // MultiPlayerGameMode#continueDestroyBlock sends the carried slot before STOP; a flagged STOP stands for
        // the START it completes
        if (blockBreak.action == PlayerAction.STOP_DESTROY_BLOCK) {
            if (flagged && pendingStarts > 0) pendingStarts--;
            settlePendingStart(player.getInventory().getHeldItem());
        }
    }

    private boolean checkBreak(BlockBreak blockBreak) {
        final int block = blockBreak.block;

        // When breaking kelp underwater, the client sends two start digging packets in the same tick.
        // AirLiquidBreak gets called twice, doesn't false the first time, but falses the second
        // One ends up breaking the kelp, the other ends up doing nothing besides falsing this check because we think
        // they're trying to mine water
        // I am explicitly making this patch as narrow and specific as possible to potentially discover other blocks
        // that exhibit similar behaviour
        int newTick = CultAPI.INSTANCE.getTickManager().currentTick;
        if (lastTick == newTick
                && lastBreakLoc.equals(blockBreak.position)
                && !didLastFlag
                && ClientBlockProperties.defaultDestroyTime(lastBlockType) == 0.0F
                && ClientBlockProperties.explosionResistance(lastBlockType) == 0.0F
                && BlockIds.is(block, BlockIds.WATER)) return false;
        lastTick = newTick;
        lastBreakLoc = blockBreak.position;
        lastBlockType = block;

        // the block does not have a hitbox
        boolean invalid = (BlockIds.is(block, BlockIds.LIGHT)
                        && !(player.getInventory().getHeldItem().getItem() == ItemTypes.LIGHT
                                || player.getInventory().getOffHand().getItem() == ItemTypes.LIGHT))
                || DataTables.defaults().registry().facts(block).has(StateFacts.AIR)
                || BlockIds.is(block, BlockIds.WATER)
                || BlockIds.is(block, BlockIds.LAVA)
                || BlockIds.is(block, BlockIds.BUBBLE_COLUMN)
                || BlockIds.is(block, BlockIds.MOVING_PISTON)
                // or the client claims to have broken an unbreakable block
                || ClientBlockProperties.defaultDestroyTime(block) == -1.0f
                        && blockBreak.action == PlayerAction.STOP_DESTROY_BLOCK
                // or the player is holding a spear
                || blockBreak.action == PlayerAction.STOP_DESTROY_BLOCK
                        && holdsPiercingWeapon(player.getInventory().getHeldItem());

        if (!invalid
                && blockBreak.action == PlayerAction.START_DESTROY_BLOCK
                && holdsPiercingWeapon(player.getInventory().getHeldItem())) {
            if (pendingStarts++ == 0) {
                pendingStartBlock = block;
                pendingStartTickEnded = false;
            }
        }

        boolean flagged = invalid && flagBreak(block, blockBreak.action);
        if (flagged && shouldModifyPackets()) {
            didLastFlag = true;
            blockBreak.cancel();
        } else {
            didLastFlag = false;
        }
        return flagged;
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        int slot = packet.slot();
        if (slot < 0 || slot > 8) return;
        settlePendingStart(
                player.getInventory().inventory.getInventoryStorage().getItem(Inventory.HOTBAR_OFFSET + slot));
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        PlayerAction action = packet.action();
        if (action == PlayerAction.STOP_DESTROY_BLOCK) return;
        if (pendingStartTickEnded
                || action == PlayerAction.CHANGE_DESTROY_DIRECTION
                || action == PlayerAction.RELEASE_USE_ITEM
                || action == PlayerAction.STAB) {
            settlePendingStart(player.getInventory().getHeldItem());
        }
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        settlePendingStart(player.getInventory().getHeldItem());
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        settlePendingStart(player.getInventory().getHeldItem());
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        settlePendingStart(player.getInventory().getHeldItem());
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        if (pendingStartTickEnded) settlePendingStart(player.getInventory().getHeldItem());
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (pendingStartTickEnded) {
            settlePendingStart(player.getInventory().getHeldItem());
        } else if (pendingStarts > 0) {
            pendingStartTickEnded = true;
        }
    }

    /** ClientPacketListener#handleLogin's new MultiPlayerGameMode never sends the slot a pending START was dug with. */
    public void resetPending() {
        pendingStarts = 0;
        pendingStartTickEnded = false;
    }

    private void settlePendingStart(@Nullable SimItemStack carried) {
        int starts = pendingStarts;
        pendingStarts = 0;
        pendingStartTickEnded = false;
        if (starts == 0 || !holdsPiercingWeapon(carried)) return;
        for (int i = 0; i < starts; i++) flagBreak(pendingStartBlock, PlayerAction.START_DESTROY_BLOCK);
    }

    private boolean holdsPiercingWeapon(@Nullable SimItemStack item) {
        return item != null
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)
                && item.components().has("minecraft:piercing_weapon");
    }

    private boolean flagBreak(int block, PlayerAction action) {
        return flag(V.write(verbose())
                .sint(DataTables.defaults().registry().blockIndex(block))
                .uint(VerboseCodecs.digging(action)));
    }
}
