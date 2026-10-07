package ac.cult.cultac.checks.impl.breaking;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import ac.grim.grimac.api.storage.verbose.Verbose;
import org.jetbrains.annotations.NotNull;

@CheckData(
        name = "AirLiquidBreak",
        stableKey = "cult.breaking.air_liquid_break",
        description = "Breaking a block that cannot be broken")
public class AirLiquidBreak extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("block={block}, type={digging}");

    public final boolean noFireHitbox = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_15_2);
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
                && blockBreak.action
                        != PlayerAction.STOP_DESTROY_BLOCK) // PE DiggingAction.START_DIGGING / FINISHED_DIGGING
        return;

        final int block = blockBreak.block;

        // Fixes false from breaking kelp underwater
        // The client sends two start digging packets to the server both in the same tick. AirLiquidBreak gets called
        // twice, doesn't false the first time, but falses the second
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
                && BlockIds.is(block, BlockIds.WATER)) return;
        lastTick = newTick;
        lastBreakLoc = blockBreak.position;
        lastBlockType = block;

        // the block does not have a hitbox
        boolean invalid = (BlockIds.is(block, BlockIds.LIGHT)
                        && !(player.getInventory().getHeldItem().getItem()
                                        == ac.cult.cultac.utils.inventory.ItemTypes.LIGHT
                                || player.getInventory().getOffHand().getItem()
                                        == ac.cult.cultac.utils.inventory.ItemTypes.LIGHT))
                || DataTables.defaults().registry().facts(block).has(StateFacts.AIR)
                || BlockIds.is(block, BlockIds.WATER)
                || BlockIds.is(block, BlockIds.LAVA)
                || BlockIds.is(block, BlockIds.BUBBLE_COLUMN)
                || BlockIds.is(block, BlockIds.MOVING_PISTON)
                || BlockIds.is(block, BlockIds.FIRE) && noFireHitbox
                // or the client claims to have broken an unbreakable block
                || ClientBlockProperties.defaultDestroyTime(block) == -1.0f
                        && blockBreak.action == PlayerAction.STOP_DESTROY_BLOCK
                // or the player is holding a spear
                || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)
                        && player.getInventory().getHeldItem().components().has("minecraft:piercing_weapon");

        if (invalid
                && flag(V.write(verbose())
                        .sint(DataTables.defaults().registry().blockIndex(block))
                        .uint(VerboseCodecs.digging(blockBreak.action)))
                && shouldModifyPackets()) {
            didLastFlag = true;
            blockBreak.cancel();
        } else {
            didLastFlag = false;
        }
    }
}
