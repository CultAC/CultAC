package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

public class BlockProperties {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    /**
     * This is used for falling onto a block (We care if there is a bouncy block)
     * This is also used for striders checking if they are on lava
     * <p>
     * For soul speed (server-sided only)
     * (we don't account for this and instead remove this debuff) And powder snow block attribute
     * Returns the block's default model state, preserving the material-only identity of this query.
     */
    public static int getOnPos(CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_19_4)) {
            // Entity#moveEntity uses the center below the feet, with a fallback
            // for the upper half of fences, walls and gates.
            int block = player.compensatedWorld.getBlockStateIdAt(
                    BlockPos.containing(playerPos.x, playerPos.y - 0.2F, playerPos.z));
            if (BLOCKS.facts(block).has(StateFacts.AIR)) {
                int below = player.compensatedWorld.getBlockStateIdAt(
                        BlockPos.containing(playerPos.x, playerPos.y - 1.2F, playerPos.z));
                if (ClientBlockProperties.isFence(below)
                        || ClientBlockProperties.isWall(below)
                        || ClientBlockProperties.isFenceGate(below))
                    return BLOCKS.block(below).defaultState();
            }
            return BLOCKS.block(block).defaultState();
        }
        BlockPos pos = getOnPos(player, playerPos, mainSupportingBlockData, 0.2F);
        return BLOCKS.block(player.compensatedWorld.getBlockStateIdAt(pos)).defaultState();
    }

    public static float getFriction(
            CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_19_4)) {
            double below = player.getClientVersion().isOlderThan(ClientVersion.V_1_15) ? 1.0D : 0.5000001D;
            int material = player.compensatedWorld.getBlockStateIdAt(
                    BlockPos.containing(playerPos.x, playerPos.y - below, playerPos.z));
            return getMaterialFriction(material);
        }
        int underPlayer = getBlockPosBelowThatAffectsMyMovement(player, mainSupportingBlockData, playerPos);
        return getMaterialFriction(underPlayer);
    }

    public static float getBlockSpeedFactor(
            CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        // Before 1.15 soul sand is an inside-block callback. Applying the
        // modern speed factor as well would slow the same movement twice.
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_15)) return 1.0F;
        if (player.isGliding || player.isFlying) return 1.0f;

        int inBlock = player.compensatedWorld.getBlockStateIdAt(
                CultMath.floor(playerPos.x), CultMath.floor(playerPos.y), CultMath.floor(playerPos.z));
        float inBlockSpeedFactor = getBlockSpeedFactor(inBlock);
        if (inBlockSpeedFactor != 1.0f
                || BlockIds.is(inBlock, BlockIds.WATER)
                || BlockIds.is(inBlock, BlockIds.BUBBLE_COLUMN)) {
            return inBlockSpeedFactor;
        }

        int underPlayer = getBlockPosBelowThatAffectsMyMovement(player, mainSupportingBlockData, playerPos);
        return getBlockSpeedFactor(underPlayer);
    }

    public static boolean onHoneyBlock(
            CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_15)) return false;
        int inBlock = player.compensatedWorld.getBlockStateIdAt(
                CultMath.floor(playerPos.x), CultMath.floor(playerPos.y), CultMath.floor(playerPos.z));
        // MCP-Reborn Entity#getBlockJumpFactor reads the entity's current
        // blockPosition first, then getBlockPosBelowThatAffectsMyMovement().
        // Jump-factor honey must follow that exact lookup, not getOnPosLegacy().
        return BlockIds.is(inBlock, BlockIds.HONEY_BLOCK)
                || BlockIds.is(
                        getBlockPosBelowThatAffectsMyMovement(player, mainSupportingBlockData, playerPos),
                        BlockIds.HONEY_BLOCK);
    }

    /**
     * Friction
     * Block jump factor
     * Block speed factor
     * <p>
     * On soul speed block (server-sided only)
     */
    private static int getBlockPosBelowThatAffectsMyMovement(
            CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        BlockPos pos = getOnPos(player, playerPos, mainSupportingBlockData, 0.500001F);
        return player.compensatedWorld.getBlockStateIdAt(pos);
    }

    private static BlockPos getOnPos(
            CultPlayer player,
            Vec3 playerPos,
            MainSupportingBlockData mainSupportingBlockData,
            float searchBelowPlayer) {
        BlockPos mainBlockPos = mainSupportingBlockData.getBlockPos();
        if (mainBlockPos != null) {
            int blockstate = player.compensatedWorld.getBlockStateIdAt(mainBlockPos);

            // I genuinely don't understand this code, or why fences are special
            boolean shouldReturn = (!((double) searchBelowPlayer <= 0.5D) || !ClientBlockProperties.isFence(blockstate))
                    && !ClientBlockProperties.isWall(blockstate)
                    && !ClientBlockProperties.isFenceGate(blockstate);

            return shouldReturn
                    ? mainBlockPos.atY(CultMath.floor(playerPos.y - (double) searchBelowPlayer))
                    : mainBlockPos;
        }

        return new BlockPos(
                CultMath.floor(playerPos.x),
                CultMath.floor(playerPos.y - searchBelowPlayer),
                CultMath.floor(playerPos.z));
    }

    public static float getMaterialFriction(int state) {
        return BLOCKS.facts(state).friction();
    }

    private static float getBlockSpeedFactor(int state) {
        return BLOCKS.facts(state).speedFactor();
    }
}
