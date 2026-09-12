package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.CultMath;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.Material;

public class BlockProperties {
    /**
     * This is used for falling onto a block (We care if there is a bouncy block)
     * This is also used for striders checking if they are on lava
     * <p>
     * For soul speed (server-sided only)
     * (we don't account for this and instead remove this debuff) And powder snow block attribute
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

        BlockState inBlock = player.compensatedWorld.getBlockStateAt(CultMath.floor(playerPos.x), CultMath.floor(playerPos.y), CultMath.floor(playerPos.z));
        float inBlockSpeedFactor = getBlockSpeedFactor(inBlock);
        if (inBlockSpeedFactor != 1.0f || inBlock.getBukkitMaterial() == Material.WATER || inBlock.getBukkitMaterial() == Material.BUBBLE_COLUMN) {
            return inBlockSpeedFactor;
        }

        Material underPlayer = getBlockPosBelowThatAffectsMyMovement(player, mainSupportingBlockData, playerPos);
        return getBlockSpeedFactor(underPlayer);
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
     * <p>
     * On soul speed block (server-sided only)
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

    private static float getBlockSpeedFactor(Material type) {
        return getStateSpeedFactor(((CraftBlockData) type.createBlockData()).getState());
    }

    private static float getBlockSpeedFactor(BlockState state) {
        return getStateSpeedFactor(state);
    }

    private static float getStateFriction(BlockState state) {
        return state.getBlock().getFriction();
    }

    private static float getStateSpeedFactor(BlockState state) {
        return state.getBlock().getSpeedFactor();
    }
}
