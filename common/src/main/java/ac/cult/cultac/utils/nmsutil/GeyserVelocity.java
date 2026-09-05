package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.EntityTypeTags;
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
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());
    // Vanilla's literal is 0.2F, promoted to double by Vec3#add.
    private static final double LAUNCH_FORCE = 0.2F;
    private static final float BASE_LAUNCH_SPEED = 0.3F;
    private static final float LAUNCH_SPEED_PER_WATER_BLOCK = 0.1F;
    private static final int MAX_WATER_SCAN = 5;
    private static final int FORCE_HEIGHT_PER_WATER_BLOCK = 6;
    private static final int MAX_COLUMN_HEIGHT = FORCE_HEIGHT_PER_WATER_BLOCK * (MAX_WATER_SCAN - 1);

    private GeyserVelocity() {
    }

    public static Set<Vec3> applyToResult(CultPlayer player, PredictionResult result, Set<Vec3> velocities) {
        if (player == null
                || SERVER_VERSION.isOlderThan(ClientVersion.V_26_2)
                || player.getClientVersion().isOlderThan(ClientVersion.V_26_2)
                || player.bedrockState != null) {
            return velocities;
        }

        SimulationContext context = result.getSimulationContext();
        if (context == null) {
            return velocities;
        }

        // Entity#getRootVehicle is launched, unless that root is itself riding
        // something. That exactly matches the client ticker's isPassenger test.
        PacketEntity launchedEntity = context.getVehicle() == null
                ? context.getEntities().getSelf()
                : context.getVehicle();
        if (!canLaunch(launchedEntity, context.getVehicle() == null, player.isFlying, player.gamemode)) {
            return velocities;
        }

        SimpleCollisionBox playerBox = context.getToActualPose();
        List<Float> caps = findIntersectingColumnCaps(player, playerBox);
        if (caps.isEmpty()) {
            return velocities;
        }

        return applyLaunchCaps(velocities, caps);
    }

    /** The client caps fall distance before testing whether this ticker can launch the actor. */
    public static ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects.State applyToState(
            CultPlayer player, PredictionResult result, Vec3 end,
            ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects.State state) {
        if (SERVER_VERSION.isOlderThan(ClientVersion.V_26_2) || player.getClientVersion().isOlderThan(ClientVersion.V_26_2)) return state;
        SimulationContext context = result.getSimulationContext();
        PacketEntity actor = context.getVehicle() == null ? context.getEntities().getSelf() : context.getVehicle();
        if (actor.isDead || player.gamemode == GameMode.SPECTATOR) return state;
        SimpleCollisionBox box = context.getToActualPose().copy().offset(end.subtract(context.getEnd()));
        boolean launch = canLaunch(actor, context.getVehicle() == null, player.isFlying, player.gamemode);
        for (float cap : findIntersectingColumnCaps(player, box)) {
            double distance = capFallDistance(state.fallDistance(), state.velocity().y);
            Vec3 velocity = launch && state.velocity().y < cap ? state.velocity().add(0, LAUNCH_FORCE, 0) : state.velocity();
            state = new ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects.State(distance, velocity, state.stuckSpeed());
        }
        return state;
    }

    public static double capFallDistance(double distance, double velocityY) {
        return distance > 1 && velocityY > -0.5 ? 1 : distance;
    }

    private static List<Float> findIntersectingColumnCaps(CultPlayer player, SimpleCollisionBox box) {
        List<Float> caps = new ArrayList<>();
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);

        int lowestY = Math.max(minY - MAX_COLUMN_HEIGHT - 1, player.compensatedWorld.getMinHeight());
        for (BlockPos ticker : player.compensatedWorld.getGeysers()
                .getTickersInOrder(minX, lowestY, minZ, maxX, maxY, maxZ)) {
            int x = ticker.getX();
            int y = ticker.getY();
            int z = ticker.getZ();
            BlockState state = player.compensatedWorld.getBlockStateAt(ticker);
            if (!isEruptingPotentSulfur(state)) {
                continue;
            }

            int waterBlocks = waterBlocksAbove(player, x, y, z);
            if (waterBlocks < 0) {
                continue;
            }

            int forceHeight = unobstructedCount(player, x, y + 1, z, FORCE_HEIGHT_PER_WATER_BLOCK * waterBlocks);
            if (intersectsPlume(box, x, y, z, forceHeight)) {
                caps.add(launchCap(waterBlocks));
            }
        }
        return caps;
    }

    static SimpleCollisionBox plumeBox(int x, int y, int z, int forceHeight) {
        return new SimpleCollisionBox(x, y + 1, z, x + 1, y + 1 + forceHeight, z + 1);
    }

    static boolean intersectsPlume(SimpleCollisionBox box, int x, int y, int z, int forceHeight) {
        return forceHeight > 0 && box.isIntersected(plumeBox(x, y, z, forceHeight));
    }

    static float launchCap(int waterBlocks) {
        return BASE_LAUNCH_SPEED + LAUNCH_SPEED_PER_WATER_BLOCK * waterBlocks;
    }

    static Set<Vec3> applyLaunchCaps(Set<Vec3> velocities, List<Float> caps) {
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

    static boolean canLaunch(PacketEntity entity, boolean rootIsPlayer, boolean playerFlying, GameMode gameMode) {
        return entity != null && !entity.isDead && entity.riding == null
                && !BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.type).is(EntityTypeTags.NOT_AFFECTED_BY_GEYSERS)
                && gameMode != GameMode.SPECTATOR
                && (!rootIsPlayer || !playerFlying);
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
    private static int waterBlocksAbove(CultPlayer player, int x, int y, int z) {
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
    private static int unobstructedCount(CultPlayer player, int x, int startY, int z, int maxHeight) {
        for (int i = 0; i < maxHeight; i++) {
            BlockState state = player.compensatedWorld.getBlockStateAt(x, startY + i, z);
            if (!isPassable(player, state, x, startY + i, z)) {
                return i;
            }
        }
        return maxHeight;
    }

    // MCP-Reborn 26.2 PotentSulfurBlockEntity#isGeyserPassableBlock
    private static boolean isPassable(CultPlayer player, BlockState state, int x, int y, int z) {
        if (state.isAir() || state.is(Blocks.WATER)) {
            return true;
        }
        return state.getCollisionShape(player.compensatedWorld, new BlockPos(x, y, z),
                CollisionContext.positionContext(y - 1)).isEmpty();
    }
}
