package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.network.protocol.util.SpigotConversionUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.bukkit.GameMode;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MCP-Reborn 26.2 PotentSulfurBlockEntity#LAUNCH_ENTITY_TICKER: while a potent
 * sulfur block is ERUPTING or CONTINUOUS, the client (and server) add +0.2 to the
 * deltaMovement of entities inside the geyser plume column every tick. The client
 * ticks entities before block entities (Minecraft#tick), so the impulse lands on
 * the carried deltaMovement and becomes next-tick starting velocity.
 */
public final class GeyserVelocity {
    private static final double LAUNCH_FORCE = 0.2D;
    private static final float BASE_LAUNCH_SPEED = 0.3F;
    private static final float LAUNCH_SPEED_PER_WATER_BLOCK = 0.1F;
    private static final int MAX_WATER_SCAN = 5;
    private static final int FORCE_HEIGHT_PER_WATER_BLOCK = 6;
    private static final int MAX_COLUMN_HEIGHT = FORCE_HEIGHT_PER_WATER_BLOCK * (MAX_WATER_SCAN - 1);

    private GeyserVelocity() {
    }

    public static Set<Vec3> applyToResult(GrimPlayer player, PredictionResult result, Set<Vec3> velocities) {
        if (player == null
                || player.getClientVersion().isOlderThan(ClientVersion.V_26_2)
                || player.bedrockState != null) {
            return velocities;
        }

        SimulationContext context = result.getSimulationContext();
        // The ticker skips passengers, flying players, and spectators.
        if (context == null || context.getVehicle() != null || player.isFlying || player.gamemode == GameMode.SPECTATOR) {
            return velocities;
        }

        SimpleCollisionBox playerBox = context.getToActualPose();
        List<Float> caps = findIntersectingColumnCaps(player, playerBox);
        if (caps.isEmpty()) {
            return velocities;
        }

        Set<Vec3> adjusted = new HashSet<>();
        for (Vec3 velocity : velocities) {
            Vec3 current = velocity;
            for (float cap : caps) {
                if (current.y < cap) {
                    current = current.add(0.0D, LAUNCH_FORCE, 0.0D);
                }
            }
            adjusted.add(current);
        }
        return adjusted;
    }

    private static List<Float> findIntersectingColumnCaps(GrimPlayer player, SimpleCollisionBox box) {
        List<Float> caps = new ArrayList<>();
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = Math.max(minY - MAX_COLUMN_HEIGHT - 1, player.compensatedWorld.getMinHeight()); y <= maxY; y++) {
                    BlockState state = player.compensatedWorld.getBlockStateAt(x, y, z);
                    if (!isEruptingPotentSulfur(state)) {
                        continue;
                    }

                    int waterBlocks = waterBlocksAbove(player, x, y, z);
                    if (waterBlocks < 0) {
                        continue;
                    }

                    int forceHeight = unobstructedCount(player, x, y + 1, z, FORCE_HEIGHT_PER_WATER_BLOCK * waterBlocks);
                    SimpleCollisionBox column = new SimpleCollisionBox(x, y + 1, z, x + 1, y + 1 + forceHeight, z);
                    if (box.isIntersected(column)) {
                        caps.add(BASE_LAUNCH_SPEED + LAUNCH_SPEED_PER_WATER_BLOCK * waterBlocks);
                    }
                }
            }
        }
        return caps;
    }

    private static boolean isEruptingPotentSulfur(BlockState state) {
        BlockData data = SpigotConversionUtil.fromNmsBlockState(state);
        if (!"POTENT_SULFUR".equals(data.getMaterial().name())) {
            return false;
        }
        // The PotentSulfurBlock class only exists on 26.2 servers, so read the
        // BlockStateProperties#POTENT_SULFUR_STATE property from the serialized form.
        String serialized = data.getAsString(false);
        return serialized.contains("potent_sulfur_state=erupting") || serialized.contains("potent_sulfur_state=continuous");
    }

    // MCP-Reborn 26.2 PotentSulfurBlockEntity#findNoxiousGasSourceBlock
    private static int waterBlocksAbove(GrimPlayer player, int x, int y, int z) {
        int maxY = y + MAX_WATER_SCAN;
        for (int cy = y + 1; cy <= maxY; cy++) {
            BlockState state = player.compensatedWorld.getBlockStateAt(x, cy, z);
            boolean waterSource = player.compensatedWorld.getFluidStateAt(x, cy, z).isSourceOfType(Fluids.WATER);
            if (!waterSource || (!state.is(Blocks.WATER) && !isPassable(player, state, x, cy, z))) {
                if (state.isAir() || isPassable(player, state, x, cy, z)) {
                    return cy - y - 1;
                }
                return -1;
            }
        }
        return -1;
    }

    // MCP-Reborn 26.2 PotentSulfurBlockEntity#getUnobstructedBlockCount
    private static int unobstructedCount(GrimPlayer player, int x, int startY, int z, int maxHeight) {
        for (int i = 0; i < maxHeight; i++) {
            BlockState state = player.compensatedWorld.getBlockStateAt(x, startY + i, z);
            if (!isPassable(player, state, x, startY + i, z)) {
                return i;
            }
        }
        return maxHeight;
    }

    // MCP-Reborn 26.2 PotentSulfurBlockEntity#isGeyserPassableBlock
    private static boolean isPassable(GrimPlayer player, BlockState state, int x, int y, int z) {
        if (state.isAir() || state.is(Blocks.WATER)) {
            return true;
        }
        return state.getCollisionShape(player.compensatedWorld, new BlockPos(x, y, z),
                CollisionContext.positionContext(y - 1)).isEmpty();
    }
}
