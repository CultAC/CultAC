package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

public class BlockProperties {
    /**
     * This is used for falling onto a block (We care if there is a bouncy block)
     * This is also used for striders checking if they are on lava
     * <p>
     * For 1.16.0-1.16.1 client soul speed detection
     * And powder snow block attribute
     */
    public static Material getOnPos(CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_19_4)) {
            // Entity#moveEntity uses the center below the feet, with a fallback
            // for the upper half of fences, walls and gates.
            Material block = player.compensatedWorld.getMaterialAt(BlockPos.containing(playerPos.x, playerPos.y - 0.2F, playerPos.z));
            if (block.isAir()) {
                Material below = player.compensatedWorld.getMaterialAt(BlockPos.containing(playerPos.x, playerPos.y - 1.2F, playerPos.z));
                if (NmsBlockTags.isFence(below) || NmsBlockTags.isWall(below) || NmsBlockTags.isFenceGate(below)) return below;
            }
            return block;
        }
        BlockPos pos = getOnPos(player, playerPos, mainSupportingBlockData, 0.2F);
        return player.compensatedWorld.getMaterialAt(pos);
    }

    public static float getFriction(CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_19_4)) {
            double below = player.getClientVersion().isOlderThan(ClientVersion.V_1_15) ? 1.0D : 0.5000001D;
            Material material = player.compensatedWorld.getMaterialAt(BlockPos.containing(playerPos.x, playerPos.y - below, playerPos.z));
            return getMaterialFriction(material);
        }
        Material underPlayer = getBlockPosBelowThatAffectsMyMovement(player, mainSupportingBlockData, playerPos);
        return getMaterialFriction(underPlayer);
    }

    public static float getBlockSpeedFactor(CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        // Before 1.15 soul sand is an inside-block callback. Applying the
        // modern speed factor as well would slow the same movement twice.
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_15)) return 1.0F;
        if (player.isGliding || player.isFlying) return 1.0f;

        PacketEntity entity = player.compensatedEntities.getEntityInControl();
        // 1.16 through 1.20.6 check the boots enchantment client side; 1.21+
        // clients use the MOVEMENT_EFFICIENCY attribute instead.
        boolean soulSpeedBoots = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_16)
                && player.getClientVersion().isOlderThan(ClientVersion.V_1_21)
                && entity == player.compensatedEntities.getSelf() && hasSoulSpeedBoots(player);

        // 1.16.0 and 1.16.1 detect soul speed from the legacy on-pos block
        if (soulSpeedBoots && player.getClientVersion().isOlderThan(ClientVersion.V_1_16_2)
                && getOnPos(player, mainSupportingBlockData, playerPos) == Material.SOUL_SAND) {
            return 1.0f;
        }

        BlockState inBlock = player.compensatedWorld.getBlockStateAt(CultMath.floor(playerPos.x), CultMath.floor(playerPos.y), CultMath.floor(playerPos.z));
        float inBlockSpeedFactor = getBlockSpeedFactor(player, inBlock, soulSpeedBoots);
        if (inBlockSpeedFactor != 1.0f || inBlock.getBukkitMaterial() == Material.WATER || inBlock.getBukkitMaterial() == Material.BUBBLE_COLUMN) {
            return applyMovementEfficiency(entity, inBlockSpeedFactor);
        }

        Material underPlayer = getBlockPosBelowThatAffectsMyMovement(player, mainSupportingBlockData, playerPos);
        return applyMovementEfficiency(entity, getBlockSpeedFactor(player, underPlayer, soulSpeedBoots));
    }

    // MCP-Reborn 1.21+ LivingEntity#getBlockSpeedFactor:
    // Mth.lerp(MOVEMENT_EFFICIENCY, super.getBlockSpeedFactor(), 1.0F).
    // Non-living entities never receive the attribute and stay at 0.0.
    static float applyMovementEfficiency(PacketEntity entity, float blockSpeedFactor) {
        float movementEfficiency = (float) entity.movementEfficiency;
        return blockSpeedFactor + movementEfficiency * (1.0F - blockSpeedFactor);
    }

    private static boolean hasSoulSpeedBoots(CultPlayer player) {
        ItemStack boots = player.getInventory().getBoots();
        return !boots.isEmpty() && boots.getEnchantmentLevel(Enchantment.SOUL_SPEED) > 0;
    }

    public static boolean onHoneyBlock(CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_15)) return false;
        Material inBlock = player.compensatedWorld.getBlockStateAt(CultMath.floor(playerPos.x), CultMath.floor(playerPos.y), CultMath.floor(playerPos.z)).getBukkitMaterial();
        // MCP-Reborn Entity#getBlockJumpFactor reads the entity's current
        // blockPosition first, then getBlockPosBelowThatAffectsMyMovement().
        // Jump-factor honey must follow that exact lookup, not getOnPosLegacy().
        return inBlock == Material.HONEY_BLOCK || getBlockPosBelowThatAffectsMyMovement(player, mainSupportingBlockData, playerPos) == Material.HONEY_BLOCK;
    }

    /**
     * Friction
     * Block jump factor
     * Block speed factor
     */
    private static Material getBlockPosBelowThatAffectsMyMovement(CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        BlockPos pos = getOnPos(player, playerPos, mainSupportingBlockData, 0.500001F);
        return player.compensatedWorld.getMaterialAt(pos);
    }

    private static BlockPos getOnPos(CultPlayer player, Vec3 playerPos, MainSupportingBlockData mainSupportingBlockData, float searchBelowPlayer) {
        BlockPos mainBlockPos = mainSupportingBlockData.getBlockPos();
        if (mainBlockPos != null) {
            Material blockstate = player.compensatedWorld.getMaterialAt(mainBlockPos);

            // I genuinely don't understand this code, or why fences are special
            boolean shouldReturn = (!((double) searchBelowPlayer <= 0.5D) || !NmsBlockTags.isFence(blockstate))
                    && !NmsBlockTags.isWall(blockstate)
                    && !NmsBlockTags.isFenceGate(blockstate);

            return shouldReturn ? mainBlockPos.atY(CultMath.floor(playerPos.y - (double) searchBelowPlayer)) : mainBlockPos;
        }

        return new BlockPos(CultMath.floor(playerPos.x), CultMath.floor(playerPos.y - searchBelowPlayer), CultMath.floor(playerPos.z));
    }

    public static float getMaterialFriction(Material material) {
        return getStateFriction(((CraftBlockData) material.createBlockData()).getState());
    }

    private static float getBlockSpeedFactor(CultPlayer player, Material type, boolean soulSpeedBoots) {
        return getBlockSpeedFactor(player, ((CraftBlockData) type.createBlockData()).getState(), soulSpeedBoots);
    }

    private static float getBlockSpeedFactor(CultPlayer player, BlockState state, boolean soulSpeedBoots) {
        // 1.16.2 through 1.20.6 ignore the soul sand slowdown with soul speed boots.
        if (soulSpeedBoots && state.getBukkitMaterial() == Material.SOUL_SAND
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_16_2)) {
            return 1.0f;
        }
        return getStateSpeedFactor(state);
    }

    private static float getStateFriction(BlockState state) {
        return state.getBlock().getFriction();
    }

    private static float getStateSpeedFactor(BlockState state) {
        return state.getBlock().getSpeedFactor();
    }
}
