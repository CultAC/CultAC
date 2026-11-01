package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

public class BlockProperties {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    /** Returns the block's default model state, preserving the material-only identity of this query. */
    public static int getOnPos(CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        return BLOCKS.block(getOnPosState(player, playerPos, mainSupportingBlockData, 0.2F))
                .defaultState();
    }

    public static float getFriction(
            CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        int underPlayer = getBlockPosBelowThatAffectsMyMovement(player, mainSupportingBlockData, playerPos);
        return getMaterialFriction(underPlayer);
    }

    public static float getBlockSpeedFactor(
            CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
        if (player.isGliding || player.isFlying) return 1.0f;
        return getEntityBlockSpeedFactor(player, mainSupportingBlockData, playerPos);
    }

    /** Entity#getBlockSpeedFactor of an entity at {@code playerPos} on the supporting block its last move found. */
    public static float getEntityBlockSpeedFactor(
            CultPlayer player, MainSupportingBlockData mainSupportingBlockData, Vec3 playerPos) {
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
        int inBlock = player.compensatedWorld.getBlockStateIdAt(
                CultMath.floor(playerPos.x), CultMath.floor(playerPos.y), CultMath.floor(playerPos.z));
        // MCP-Reborn Entity#getBlockJumpFactor reads the entity's current
        // blockPosition first, then getBlockPosBelowThatAffectsMyMovement().
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
        return getOnPosState(player, playerPos, mainSupportingBlockData, 0.500001F);
    }

    /** The block state at Entity#getOnPos for {@code searchBelowPlayer}. */
    private static int getOnPosState(
            CultPlayer player,
            Vec3 playerPos,
            MainSupportingBlockData mainSupportingBlockData,
            float searchBelowPlayer) {
        BlockPos mainBlockPos = mainSupportingBlockData.getBlockPos();
        if (mainBlockPos != null) {
            int blockstate = player.compensatedWorld.getBlockStateIdAt(mainBlockPos);

            boolean shouldReturn = (!((double) searchBelowPlayer <= 0.5D) || !ClientBlockProperties.isFence(blockstate))
                    && !ClientBlockProperties.isWall(blockstate)
                    && !ClientBlockProperties.isFenceGate(blockstate);

            return shouldReturn
                    ? player.compensatedWorld.getBlockStateIdAt(
                            mainBlockPos.getX(),
                            CultMath.floor(playerPos.y - (double) searchBelowPlayer),
                            mainBlockPos.getZ())
                    : blockstate;
        }

        return player.compensatedWorld.getBlockStateIdAt(
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
