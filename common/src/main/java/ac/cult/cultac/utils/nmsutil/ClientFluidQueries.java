package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.engine.FluidQueries;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.engine.shapes.VoxelShape;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Vanilla liquid-presence, body-depth, and eye queries have different bounds. */
public final class ClientFluidQueries {
    private static final String CAN_FLOAT_WHILE_RIDDEN = "minecraft:can_float_while_ridden";

    private ClientFluidQueries() {}

    /** Immutable generated fluid snapshots, shared by states with the same fluid. */
    private static final class ModelFluids {
        private static final SimFluidState[] STATES = snapshots();

        private static SimFluidState[] snapshots() {
            var registry = DataTables.defaults().registry();
            var states = new SimFluidState[registry.stateCount()];
            var shared = new HashMap<SimFluidState, SimFluidState>();
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
    public static VoxelShape raycastShape(CompensatedWorld world, BlockPos pos, int state) {
        return FluidQueries.shape(
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

    public static boolean usesLegacyFluidDepth(CultPlayer player) {
        ClientVersion version = player.getClientVersion();
        return !player.isBedrockMovement()
                && version.isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && version.isOlderThan(ClientVersion.V_26_1);
    }

    /** 1.21.2–1.21.11 add the block Y and float fluid height before widening to double. */
    public static double fluidSurfaceY(CultPlayer player, int blockY, double fluidHeight) {
        return usesLegacyFluidDepth(player) ? (double) ((float) blockY + (float) fluidHeight) : blockY + fluidHeight;
    }

    /** 26.1's EntityFluidInteraction queries actual chunks; older ClientLevel#hasChunk always succeeds. */
    public static boolean hasLoadedFluidNeighborhood(CultPlayer player, SimpleCollisionBox body) {
        ClientVersion version = player.getClientVersion();
        if (player.isBedrockMovement() || version.isOlderThan(ClientVersion.V_26_1)) return true;
        // hasFluidAndLoaded receives the deflated cell bounds expanded by one block, then calls
        // Level#getChunk(...FULL,false), bypassing ClientLevel's unconditional hasChunk override.
        int minX = (int) Math.floor(body.minX + .001D) - 1;
        int maxX = (int) Math.ceil(body.maxX - .001D);
        int minZ = (int) Math.floor(body.minZ + .001D) - 1;
        int maxZ = (int) Math.ceil(body.maxZ - .001D);
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (!player.compensatedWorld.isChunkLoaded(x, z)) return false;
            }
        }
        return true;
    }

    public static boolean is(CultPlayer player, SimFluidState state, FluidTags tag) {
        if (!player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            return clientTags(player)
                    .fluids()
                    .getOrDefault(tag.key(), List.of())
                    .contains(state.type());
        }
        // Older unsupported Java releases and Bedrock retain the existing default membership.
        return tag.test(state);
    }

    public static ClientComponentRegistries.Tags clientTags(CultPlayer player) {
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
        if (!player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            SimFluidState fluid = fluidAt(player.compensatedWorld, pos);
            if (fluid.isEmpty() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1))
                return Sample.EMPTY;
            return new Sample(
                    ClientFluidTagOrder.contactMatches(player, fluid, FluidTags.WATER),
                    ClientFluidTagOrder.contactMatches(player, fluid, FluidTags.LAVA),
                    height(player.compensatedWorld, pos, fluid));
        }
        // Older clients and Bedrock use the material and height rules,
        // including waterlogged blocks and the amount/9 height calculation.
        SimFluidState fluid = modelFluid(block);
        if (fluid.is("minecraft:water") || fluid.is("minecraft:flowing_water")) {
            return new Sample(true, false, player.compensatedWorld.getWaterFluidLevelAt(pos));
        }
        if (BlockIds.is(block, BlockIds.LAVA)) {
            return new Sample(false, true, player.compensatedWorld.getLavaFluidLevelAt(pos));
        }
        return Sample.EMPTY;
    }

    public record Sample(boolean water, boolean lava, double height) {
        private static final Sample EMPTY = new Sample(false, false, 0.0D);
    }

    private static final String[] EYE_FLUIDS = {
        "minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava"
    };

    /** Compatibility construction of a declared WATER contact; production captures the actual holder. */
    public static int eyeContactFromWater(boolean water) {
        return water ? 3 : 0;
    }

    private static int eyeFluidBit(String type) {
        for (int i = 0; i < EYE_FLUIDS.length; i++) if (EYE_FLUIDS[i].equals(type)) return 2 << i;
        return 0;
    }

    private static boolean eyeHoldersInTag(CultPlayer player, int contact, FluidTags tag) {
        java.util.Collection<String> members =
                !player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                        ? clientTags(player).fluids().getOrDefault(tag.key(), List.of())
                        : DataTables.defaults().tags().getOrDefault("fluid:" + tag.key(), java.util.Set.of());
        for (int i = 0; i < EYE_FLUIDS.length; i++)
            if ((contact & (2 << i)) != 0 && members.contains(EYE_FLUIDS[i])) return true;
        return false;
    }

    /** The old tag/tracker result is a snapshot; 26.3 queries current tags on cached raw holders. */
    public static boolean eyeContactInWater(CultPlayer player, int contact) {
        return !player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                ? eyeHoldersInTag(player, contact, FluidTags.WATER)
                : (contact & 1) != 0;
    }

    public static boolean eyesInWater(CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY) {
        return eyeContactInWater(player, eyeFluidContact(player, body, feet, eyeY));
    }

    /** A bounded cache: the WATER snapshot bit and the four non-empty static fluid holders. */
    public static int eyeFluidContact(CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY) {
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2))
            return eyeContactFromWater(legacyEyesInWater(player, feet, eyeY));
        int holders;
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)) {
            holders = eyeFluidHolders(player, body, feet, eyeY);
        } else {
            BlockPos pos = BlockPos.containing(feet.x, eyeY, feet.z);
            SimFluidState fluid = fluidAt(player.compensatedWorld, pos);
            double top = (double) ((float) pos.getY() + height(player.compensatedWorld, pos, fluid));
            holders = top > eyeY ? eyeFluidBit(fluid.type()) : 0;
        }
        boolean water = eyeHoldersInTag(player, holders, FluidTags.WATER);
        if (water
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                && player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) {
            water = false;
            for (int i = 0; i < EYE_FLUIDS.length; i++) {
                if ((holders & (2 << i)) != 0)
                    water |= ClientFluidTagOrder.contactMatches(
                            player, new SimFluidState(EYE_FLUIDS[i], 8, false, 0, true), FluidTags.WATER);
            }
        }
        return holders | (water ? 1 : 0);
    }

    private static boolean legacyEyesInWater(CultPlayer player, Vec3 feet, double eyeY) {
        if (!player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_20_5)) {
            float level = (float) player.compensatedWorld.getWaterFluidLevelAt(feet.x, eyeY, feet.z);
            return (float) Math.floor(eyeY) + level > eyeY;
        }
        double legacyEyeY = eyeY - 0.1111111119389534D;
        return legacyEyeY
                < (float) Math.floor(legacyEyeY)
                        + player.compensatedWorld.getWaterFluidLevelAt(feet.x, legacyEyeY, feet.z);
    }

    public static boolean floatWhileRidden(
            CultPlayer player, SimulationContext context, boolean inWater, double waterDepth) {
        // LivingEntity#travelInFluid adds floatInWaterWhileRidden in 1.21.11.
        if (context.getVersion().isOlderThan(ClientVersion.V_1_21_11)) return false;
        var vehicle = context.getVehicle();
        if (vehicle == null) return false;
        // LivingEntity#floatInLiquidWhileRidden reads the pre-travel body depth.
        // 26.3 selects both the entity and the fluid through synchronized tags.
        if (usesTagBasedFluidRules(player)) {
            return clientTags(player)
                            .entities()
                            .getOrDefault(CAN_FLOAT_WHILE_RIDDEN, List.of())
                            .contains(EntityTypeUtil.modelType(vehicle.type).key())
                    && depth(player, context.getFromMinimumExtent(), FluidTags.ENTITY_FLOATABLE) > 0.4D;
        }
        // The received entity tag has controlled this helper since its introduction in 1.21.11.
        if (!clientTags(player)
                        .entities()
                        .getOrDefault(CAN_FLOAT_WHILE_RIDDEN, List.of())
                        .contains(EntityTypeUtil.modelType(vehicle.type).key())
                || !(waterDepth > 0.4D)) return false;
        return inWater;
    }

    public static double depth(CultPlayer player, SimpleCollisionBox entityBox, FluidTags tag) {
        if (!hasLoadedFluidNeighborhood(player, entityBox)) return 0.0D;
        SimpleCollisionBox box = entityBox.copy().expand(-0.001D);
        // Entity#updateFluidHeightAndDoFluidPushing uses the deflated minimum;
        // 26.1's EntityFluidInteraction instead measures from the original entity box.
        double depthMinY = usesLegacyFluidDepth(player) ? box.minY : entityBox.minY;
        double depth = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    SimFluidState fluid = fluidAt(player.compensatedWorld, pos.set(x, y, z));
                    if (fluid.isEmpty() || !ClientFluidTagOrder.contactMatches(player, fluid, tag)) continue;
                    double top = fluidSurfaceY(player, y, height(player.compensatedWorld, pos, fluid));
                    if (top >= box.minY) depth = Math.max(depth, top - depthMinY);
                }
            }
        }
        return depth;
    }

    /** 26.3 EntityFluidInteraction caches heights by raw holder; later tag changes query that saved contact. */
    public static Map<String, Double> rawHeights(CultPlayer player, SimpleCollisionBox body) {
        if (!hasLoadedFluidNeighborhood(player, body)) return Map.of();
        var heights = new HashMap<String, Double>();
        SimpleCollisionBox box = body.copy().expand(-0.001D);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    SimFluidState fluid = fluidAt(player.compensatedWorld, pos.set(x, y, z));
                    if (fluid.isEmpty()) continue;
                    double top = (double) y + height(player.compensatedWorld, pos, fluid);
                    if (top >= box.minY) heights.merge(fluid.type(), Math.max(0.0D, top - body.minY), Math::max);
                }
            }
        }
        return heights.isEmpty() ? Map.of() : Map.copyOf(heights);
    }

    public static boolean eyesInside(
            CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY, FluidTags tag) {
        return eyeHoldersInTag(player, eyeFluidHolders(player, body, feet, eyeY), tag);
    }

    private static int eyeFluidHolders(CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                && !hasLoadedFluidNeighborhood(player, body)) return 0;
        SimpleCollisionBox box = body.copy().expand(-0.001D);
        int x = (int) Math.floor(feet.x), z = (int) Math.floor(feet.z);
        if (x < Math.floor(box.minX)
                || x >= Math.ceil(box.maxX)
                || z < Math.floor(box.minZ)
                || z >= Math.ceil(box.maxZ)) return 0;
        int holders = 0;
        for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            SimFluidState fluid = fluidAt(player.compensatedWorld, pos);
            if (fluid.isEmpty()) continue;
            float ordinaryHeight = height(player.compensatedWorld, pos, fluid);
            if ((double) y + ordinaryHeight < box.minY || eyeY < y) continue;
            BlockPos above = pos.above();
            // 26.3 measures the eye against FluidState#getHeightForCamera, 26.1 and 26.2 against FluidState#getHeight.
            float eyeHeight = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                            && fluid.isSource()
                            && player.compensatedWorld
                                    .geometry()
                                    .isFaceSturdy(
                                            player.compensatedWorld.getBlockStateIdAt(above), above, Direction.DOWN)
                    ? 1.0F
                    : ordinaryHeight;
            if (eyeY <= (double) y + eyeHeight) holders |= eyeFluidBit(fluid.type());
        }
        return holders;
    }
}
