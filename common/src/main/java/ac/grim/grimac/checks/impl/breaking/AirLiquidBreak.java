package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.network.protocol.util.SpigotConversionUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

@CheckData(name = "AirLiquidBreak", stableKey = "grim.breaking.air_liquid_break", description = "Breaking a block that cannot be broken")
public class AirLiquidBreak extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("block={block}, type={digging}");

    public final boolean noFireHitbox = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_15_2);
    private int lastTick;
    private boolean didLastFlag;
    // Initialize to non-null values to prevent NPE when checking for blockType properties and if position equals old position
    private @NotNull BlockPos lastBreakLoc = BlockPos.ZERO;
    private @NotNull Block lastBlockType = Blocks.AIR;

    public AirLiquidBreak(GrimPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && blockBreak.action != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) // PE DiggingAction.START_DIGGING / FINISHED_DIGGING
            return;

        final Block block = blockBreak.block.getBlock();

        // Fixes false from breaking kelp underwater
        // The client sends two start digging packets to the server both in the same tick. AirLiquidBreak gets called twice, doesn't false the first time, but falses the second
        // One ends up breaking the kelp, the other ends up doing nothing besides falsing this check because we think they're trying to mine water
        // I am explicitly making this patch as narrow and specific as possible to potentially discover other blocks that exhibit similar behaviour
        int newTick = GrimAPI.INSTANCE.getTickManager().currentTick;
        if (lastTick == newTick
                && lastBreakLoc.equals(blockBreak.position)
                && !didLastFlag
                && lastBlockType.defaultDestroyTime() == 0.0F
                && lastBlockType.getExplosionResistance() == 0.0F
                && block == Blocks.WATER
        ) return;
        lastTick = newTick;
        lastBreakLoc = blockBreak.position;
        lastBlockType = block;

        // the block does not have a hitbox
        boolean invalid = (block == Blocks.LIGHT && !(player.getInventory().getHeldItem().getType() == Material.LIGHT || player.getInventory().getOffHand().getType() == Material.LIGHT))
                || blockBreak.block.isAir()
                || block == Blocks.WATER
                || block == Blocks.LAVA
                || block == Blocks.BUBBLE_COLUMN
                || block == Blocks.MOVING_PISTON
                || block == Blocks.FIRE && noFireHitbox
                // or the client claims to have broken an unbreakable block
                || block.defaultDestroyTime() == -1.0f && blockBreak.action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                // or the player is holding a spear
                || SpigotConversionUtil.toNmsItemStack(player.getInventory().getHeldItem()).has(DataComponents.PIERCING_WEAPON) && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11);

        if (invalid && flag(V.write(verbose())
                .sint(BuiltInRegistries.BLOCK.getId(block))
                .uint(VerboseTags.enumId(blockBreak.action))) && shouldModifyPackets()) {
            didLastFlag = true;
            blockBreak.cancel();
        } else {
            didLastFlag = false;
        }
    }
}
