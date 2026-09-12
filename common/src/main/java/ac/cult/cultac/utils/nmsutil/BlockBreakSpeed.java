package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.protocol.ClientVersion;
import org.bukkit.inventory.ItemStack;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import org.bukkit.block.data.BlockData;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffectType;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.state.BlockState;
import ac.cult.cultac.utils.inventory.ItemUtil;

public class BlockBreakSpeed {

    public static double getBlockDamage(CultPlayer player, BlockPos position) { return getBlockDamage(player, position, false); }

    public static double getBlockDamage(CultPlayer player, BlockPos position, boolean debug) {
        return getBlockDamage(player, position, player.compensatedWorld.getBlockDataAt(position), debug);
    }

    public static double getBlockDamage(CultPlayer player, BlockPos position, BlockData block, boolean debug) {
        ItemStack tool = player.getInventory().getHeldItem();
        BlockState nmsBlock = toNmsState(block);
        // Bukkit hardness mirrors vanilla destroySpeed, including -1 for unbreakable blocks.
        float blockHardness = block == null ? 0.0f : block.getMaterial().getHardness();
        return getBlockDamage(player, tool, nmsBlock, blockHardness, debug);
    }

    public static boolean couldInstantlyBreakBlock(CultPlayer player, BlockState block) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().inventory.getInventoryStorage()
                    .getItem(ac.cult.cultac.utils.inventory.Inventory.HOTBAR_OFFSET + slot);
            if (getBlockDamage(player, stack, block) >= 1.0D) return true;
        }
        return false;
    }

    public static double getBlockDamage(CultPlayer player, ItemStack tool, BlockState nmsBlock) {
        return getBlockDamage(player, tool, nmsBlock, nmsBlock.getBlock().defaultDestroyTime(), false);
    }

    private static double getBlockDamage(
            CultPlayer player,
            ItemStack tool,
            BlockState nmsBlock,
            float blockHardness,
            boolean debug
    ) {
        net.minecraft.world.item.ItemStack nmsTool = SpigotConversionUtil.toNmsItemStack(tool);

        if (player.gamemode == GameMode.CREATIVE) {
            if (tool != null && ItemUtil.isSword(tool.getType())) {
                return 0;
            }
            return 1;
        }

        if (blockHardness == -1) return 0; // Unbreakable block

        ModernToolData toolData = modernToolData(player, nmsTool.get(DataComponents.TOOL), nmsBlock);
        float speedMultiplier = toolData.speed;
        boolean isCorrectToolForDrop = toolData.correctForDrops;

        if (speedMultiplier > 1.0f) {
            int digSpeed = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
            if (digSpeed > 0) {
                speedMultiplier += digSpeed * digSpeed + 1;
            }
        }

        Integer digSpeed = player.compensatedEntities.getPotionLevelForPlayer(PotionEffectType.HASTE);
        Integer conduit = player.compensatedEntities.getPotionLevelForPlayer(PotionEffectType.CONDUIT_POWER);

        if (digSpeed != null || conduit != null) {
            int hasteLevel = Math.max(digSpeed == null ? 0 : digSpeed, conduit == null ? 0 : conduit);
            speedMultiplier *= 1 + (0.2 * (hasteLevel + 1));
        }

        Integer miningFatigue = player.compensatedEntities.getPotionLevelForPlayer(PotionEffectType.MINING_FATIGUE);

        if (miningFatigue != null && !player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            // RC1 Player#getDestroySpeed: cast the factor before multiplying.
            speedMultiplier *= miningFatigueMultiplier(miningFatigue);
        } else if (miningFatigue != null) {
            switch (miningFatigue) {
                case 0:
                    speedMultiplier *= 0.3;
                    break;
                case 1:
                    speedMultiplier *= 0.09;
                    break;
                case 2:
                    speedMultiplier *= 0.0027;
                    break;
                default:
                    speedMultiplier *= 0.00081;
            }
        }

        double eyeHeight = player.getEyeHeight();
        double eye = player.y + eyeHeight - 0.1111111119389534D;
        double d1 = (float) Math.floor(eye) + player.compensatedWorld.getWaterFluidLevelAt(player.x, eye, player.z);
        boolean eyeInWater = eye < d1;

        if (eyeInWater) {
            ItemStack helmet = player.getInventory().getHelmet();
            ItemStack chestplate = player.getInventory().getChestplate();
            ItemStack leggings = player.getInventory().getLeggings();
            ItemStack boots = player.getInventory().getBoots();

            if ((helmet == null || helmet.getEnchantmentLevel(Enchantment.AQUA_AFFINITY) == 0) &&
                    (chestplate == null || chestplate.getEnchantmentLevel(Enchantment.AQUA_AFFINITY) == 0) &&
                    (leggings == null || leggings.getEnchantmentLevel(Enchantment.AQUA_AFFINITY) == 0) &&
                    (boots == null || boots.getEnchantmentLevel(Enchantment.AQUA_AFFINITY) == 0)) {
                speedMultiplier /= 5;
            }
        }

        if (!canUseGroundedBreakSpeed(player)) {
            speedMultiplier /= 5;
        }

        float damage = speedMultiplier / blockHardness;

        boolean canHarvest = !nmsBlock.requiresCorrectToolForDrops() || isCorrectToolForDrop;
        if (canHarvest) {
            damage /= 30;
        } else {
            damage /= 100;
        }

        //if (debug) player.sendMessage("correctTool=" + isCorrectToolForDrop + " canHarvest=" + canHarvest);

        return damage;
    }

    static float miningFatigueMultiplier(int amplifier) {
        return (float) Math.pow(0.3, amplifier + 1);
    }

    private static ModernToolData modernToolData(CultPlayer player, Tool tool, BlockState state) {
        if (tool == null) {
            return new ModernToolData(1.0F, false);
        }

        float speed = tool.defaultMiningSpeed();
        boolean correctForDrops = false;
        boolean foundSpeed = false;
        boolean foundCorrectForDrops = false;

        // Vanilla resolves speed and correct-for-drops independently using the first
        // matching rule which defines that property. Match named rule sets against the
        // per-player tag payload rather than this server's registry bindings.
        for (Tool.Rule rule : tool.rules()) {
            if (!rule.blocks().contains(state.getBlock().builtInRegistryHolder())) {
                continue;
            }

            if (!foundSpeed && rule.speed().isPresent()) {
                speed = rule.speed().get();
                foundSpeed = true;
            }
            if (!foundCorrectForDrops && rule.correctForDrops().isPresent()) {
                correctForDrops = rule.correctForDrops().get();
                foundCorrectForDrops = true;
            }
            if (foundSpeed && foundCorrectForDrops) {
                break;
            }
        }

        return new ModernToolData(speed, correctForDrops);
    }

    private static boolean canUseGroundedBreakSpeed(CultPlayer player) {
        if (player.packetStateData.packetPlayerOnGround) {
            return true;
        }

        // Vanilla uses Player#onGround for the mining penalty. If the latest
        // movement packet has not refreshed it, only use the grounded branch
        // when the compensated world proves the current pose is supported.
        return player.boundingBox != null
                && Collisions.collide(player, 0.0D, -SimpleCollisionBox.COLLISION_EPSILON, 0.0D).y == 0.0D;
    }

    private record ModernToolData(float speed, boolean correctForDrops) {}

    private static BlockState toNmsState(BlockData data) {
        if (data instanceof CraftBlockData craftBlockData) {
            return craftBlockData.getState();
        }
        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }
}
