package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;

/** Vanilla liquid-presence, body-depth, and eye queries deliberately have different bounds. */
public final class ClientFluidQueries {
    private static final String CAN_FLOAT_WHILE_RIDDEN = "minecraft:can_float_while_ridden";

    private ClientFluidQueries() {}

    /** Immutable generated fluid snapshots, shared by states with the same fluid. */
    private static final class ModelFluids {
        private static final SimFluidState[] STATES = snapshots();

        private static SimFluidState[] snapshots() {
            var registry = ac.cult.blocksim.data.DataTables.defaults().registry();
            var states = new SimFluidState[registry.stateCount()];
            var shared = new java.util.HashMap<SimFluidState, SimFluidState>();
            for (int id = 0; id < states.length; id++) {
                var fluid = SimFluidState.of(registry.facts(id));
                states[id] = shared.computeIfAbsent(fluid, value -> value);
            }
            return states;
        }
    }

    public static SimFluidState modelFluid(int state) {
        return ModelFluids.STATES[state];
    }

    public static SimFluidState fluidAt(CompensatedWorld world, BlockPos pos) {
        return modelFluid(world.getBlockStateIdAt(pos));
    }

    /** Uses the same owned shape cache as action raycasts. Ordinary fluid height queries stay uncached. */
    public static ac.cult.blocksim.engine.shapes.VoxelShape raycastShape(
            CompensatedWorld world, BlockPos pos, int state) {
        return ac.cult.blocksim.engine.FluidQueries.shape(
                modelFluid(state), () -> modelFluid(world.getBlockStateIdAt(pos.getX(), pos.getY() + 1, pos.getZ())));
    }

    public static float height(CompensatedWorld world, BlockPos pos, SimFluidState fluid) {
        return fluid.isEmpty()
                ? 0.0F
                : fluid.height(modelFluid(world.getBlockStateIdAt(pos.getX(), pos.getY() + 1, pos.getZ())));
    }

    public static boolean usesTagBasedFluidRules(CultPlayer player) {
        return !player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    public static boolean is(CultPlayer player, SimFluidState state, FluidTags tag) {
        if (usesTagBasedFluidRules(player)) {
            return clientTags(player)
                    .fluids()
                    .getOrDefault(tag.key(), java.util.List.of())
                    .contains(state.type());
        }
        // Older clients retain the model's default fluid-tag membership.
        return tag.test(state);
    }

    private static ClientComponentRegistries.Tags clientTags(CultPlayer player) {
        if (player.registryState == null) player.registryState = new ClientComponentRegistries();
        // RegistryTags is decoded into dispatcher MODEL values on both platforms.
        // The observed wire can be older than that native representation.
        var connection = player.user.getCultConnection();
        var model = connection.dispatcher().runtime().data().version();
        var client = ProtocolVersion.of(player.getClientVersion().getProtocolVersion());
        return player.registryState.clientTags(model, client);
    }

    /** Classify one compensated cell without changing the caller's body-query bounds. */
    public static Sample sample(CultPlayer player, int block, BlockPos pos) {
        if (usesTagBasedFluidRules(player)
                || (!player.isBedrockMovement() && player.getClientVersion().isOlderThan(ClientVersion.V_1_13))) {
            SimFluidState fluid = fluidAt(player.compensatedWorld, pos);
            if (fluid.isEmpty()) return Sample.EMPTY;
            return new Sample(
                    is(player, fluid, FluidTags.WATER),
                    is(player, fluid, FluidTags.LAVA),
                    height(player.compensatedWorld, pos, fluid));
        }
        // Older clients and Bedrock retain the existing material and height rules,
        // including waterlogged blocks and the amount/9 height calculation.
        SimFluidState fluid = modelFluid(block);
        if (fluid.is("minecraft:water") || fluid.is("minecraft:flowing_water")) {
            return new Sample(true, false, player.compensatedWorld.getWaterFluidLevelAt(pos));
        }
        if (ac.cult.blocksim.data.BlockIds.is(block, ac.cult.blocksim.data.BlockIds.LAVA)) {
            return new Sample(false, true, player.compensatedWorld.getLavaFluidLevelAt(pos));
        }
        return Sample.EMPTY;
    }

    public record Sample(boolean water, boolean lava, double height) {
        private static final Sample EMPTY = new Sample(false, false, 0.0D);
    }

    public static boolean eyesInWater(CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY) {
        if (usesTagBasedFluidRules(player)) {
            return eyesInside(player, body, feet, eyeY, FluidTags.WATER);
        }
        double legacyEyeY = eyeY - 0.1111111119389534D;
        return legacyEyeY
                < (float) Math.floor(legacyEyeY)
                        + player.compensatedWorld.getWaterFluidLevelAt(feet.x, legacyEyeY, feet.z);
    }

    public static DesyncStatus floatWhileRidden(
            CultPlayer player,
            SimulationContext context,
            boolean inWater,
            double waterDepth,
            boolean waterTimingUncertain) {
        var vehicle = context.getVehicle();
        if (vehicle == null) return DesyncStatus.FALSE;
        // LivingEntity#floatInLiquidWhileRidden reads the pre-travel body depth.
        // 26.3 selects both the entity and the fluid through synchronized tags.
        if (usesTagBasedFluidRules(player)) {
            return DesyncStatus.fromBoolean(clientTags(player)
                            .entities()
                            .getOrDefault(CAN_FLOAT_WHILE_RIDDEN, java.util.List.of())
                            .contains(EntityTypeUtil.modelType(vehicle.type).key())
                    && depth(player, context.getFromMinimumExtent(), FluidTags.ENTITY_FLOATABLE) > 0.4D);
        }
        if (!EntityTypeUtil.canFloatWhileRidden(vehicle.type) || !(waterDepth > 0.4D)) return DesyncStatus.FALSE;
        return waterTimingUncertain ? DesyncStatus.UNKNOWN : DesyncStatus.fromBoolean(inWater);
    }

    public static int liquidMaximum(ClientVersion version, double maximum) {
        return version.isNewerThanOrEquals(ClientVersion.V_26_3)
                ? (int) Math.floor(maximum)
                : (int) Math.ceil(maximum) - 1;
    }

    public static boolean containsAnyLiquid(CompensatedWorld world, SimpleCollisionBox box, ClientVersion version) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x <= liquidMaximum(version, box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y <= liquidMaximum(version, box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z <= liquidMaximum(version, box.maxZ); z++) {
                    if (!fluidAt(world, pos.set(x, y, z)).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    public static double depth(CultPlayer player, SimpleCollisionBox entityBox, FluidTags tag) {
        SimpleCollisionBox box = entityBox.copy().expand(-0.001D);
        double depth = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    SimFluidState fluid = fluidAt(player.compensatedWorld, pos.set(x, y, z));
                    if (fluid.isEmpty() || !is(player, fluid, tag)) continue;
                    double top = (double) y + height(player.compensatedWorld, pos, fluid);
                    if (top >= box.minY) depth = Math.max(depth, top - entityBox.minY);
                }
            }
        }
        return depth;
    }

    public static boolean eyesInside(
            CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY, FluidTags tag) {
        SimpleCollisionBox box = body.copy().expand(-0.001D);
        int x = (int) Math.floor(feet.x), z = (int) Math.floor(feet.z);
        if (x < Math.floor(box.minX)
                || x >= Math.ceil(box.maxX)
                || z < Math.floor(box.minZ)
                || z >= Math.ceil(box.maxZ)) return false;
        for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            SimFluidState fluid = fluidAt(player.compensatedWorld, pos);
            if (fluid.isEmpty() || !is(player, fluid, tag)) continue;
            float ordinaryHeight = height(player.compensatedWorld, pos, fluid);
            if ((double) y + ordinaryHeight < box.minY || eyeY < y) continue;
            BlockPos above = pos.above();
            float eyeHeight = fluid.isSource()
                            && player.compensatedWorld
                                    .geometry()
                                    .isFaceSturdy(
                                            player.compensatedWorld.getBlockStateIdAt(above), above, Direction.DOWN)
                    ? 1.0F
                    : ordinaryHeight;
            if (eyeY <= (double) y + eyeHeight) return true;
        }
        return false;
    }
}
