package ac.cult.cultac.utils.blockplace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.misc.Unsafe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.World;

// Per-place virtual world adapter. All reads come from the player's compensated world or this placement's overlay.
public final class CompensatedPlacementWorld extends Level implements PlacementWorldAdapter {
    private static final int SEA_LEVEL = 63;
    private static final int MAX_NEIGHBOR_UPDATES = 512;
    private static final RegistryAccess.Frozen REGISTRY_ACCESS = RegistryAccess.EMPTY;
    private static final FeatureFlagSet ENABLED_FEATURES = FeatureFlags.DEFAULT_FLAGS;
    private static final Holder<Biome> DEFAULT_BIOME = Holder.direct(createDefaultBiome());
    private static final Unsafe UNSAFE = resolveUnsafe();
    private static final RecipeAccess EMPTY_RECIPE_ACCESS = new RecipeAccess() {
        @Override
        public RecipePropertySet propertySet(ResourceKey<RecipePropertySet> propertySetKey) {
            return RecipePropertySet.EMPTY;
        }

        @Override
        public SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutterRecipes() {
            return SelectableRecipe.SingleInputSet.empty();
        }
    };

    private PlacementBlockAccess blockAccess;
    private PlacementSnapshot snapshot;
    private PlacementLevelData placementLevelData;
    private DimensionType dimensionType;
    private Holder<DimensionType> dimensionTypeRegistration;
    private RandomSource randomSource;
    private LinkedHashMap<Long, PlacementResult.ChangedBlock> changedBlocks;
    private LinkedHashMap<Long, BlockState> overlayStates;
    private ChunkSource chunkSource;
    private TickRateManager tickRateManager;
    private Scoreboard scoreboard;
    private WorldBorder worldBorder;
    private LevelEntityGetter<Entity> entities;
    private EnvironmentAttributeSystem environmentAttributeSystem;

    private CompensatedPlacementWorld(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        super(
                new PlacementLevelData(snapshot),
                Level.OVERWORLD,
                REGISTRY_ACCESS,
                Holder.direct(createDimensionType(snapshot)),
                false,
                false,
                0L,
                MAX_NEIGHBOR_UPDATES,
                "compensated-placement",
                null,
                null,
                World.Environment.NORMAL,
                ignored -> null
        );
        this.blockAccess = blockAccess;
        this.snapshot = snapshot;
        initializeState();
    }

    public static CompensatedPlacementWorld create(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        try {
            // Paper's Level constructor unconditionally creates CraftWorld and casts this to ServerLevel.
            // Allocate only the detached shell; unlike the old adapter, no NMS/Paper field is patched.
            CompensatedPlacementWorld world = (CompensatedPlacementWorld) UNSAFE.allocateInstance(CompensatedPlacementWorld.class);
            world.blockAccess = blockAccess;
            world.snapshot = snapshot;
            world.initializeState();
            SmoketestPredictionSafety.detachedAdapter(world);
            return world;
        } catch (InstantiationException exception) {
            throw new IllegalStateException("Failed to allocate compensated placement world", exception);
        }
    }

    @Override
    public Level level() {
        return this;
    }

    private void initializeState() {
        this.placementLevelData = new PlacementLevelData(snapshot);
        this.dimensionType = createDimensionType(snapshot);
        this.dimensionTypeRegistration = Holder.direct(dimensionType);
        this.randomSource = RandomSource.create(0L);
        this.changedBlocks = new LinkedHashMap<>();
        this.overlayStates = new LinkedHashMap<>();
        this.tickRateManager = new TickRateManager();
        this.scoreboard = new Scoreboard();
        this.worldBorder = new WorldBorder();
        this.entities = new EmptyLevelEntityGetter();
        this.environmentAttributeSystem = EnvironmentAttributeSystem.builder().build();
        this.chunkSource = this.new UnsupportedChunkSource();
    }

    private static Unsafe resolveUnsafe() {
        try {
            java.lang.reflect.Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            return (Unsafe) unsafeField.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public BlockState getBaseBlockState(BlockPos pos) {
        return blockAccess.getBlockStateAt(pos);
    }

    @Override
    public ResourceKey<LevelStem> getTypeKey() {
        return LevelStem.OVERWORLD;
    }

    @Override
    public boolean isClientSide() {
        return true;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return ENABLED_FEATURES;
    }

    public PlacementResult buildResult(BlockPos primaryPlacedPosition) {
        List<PlacementResult.ChangedBlock> ordered = new ArrayList<>(changedBlocks.values());
        return PlacementResult.success(ordered, primaryPlacedPosition.immutable(), true);
    }

    private void recordChange(BlockPos pos, BlockState state) {
        changedBlocks.put(pos.asLong(), new PlacementResult.ChangedBlock(pos.immutable(), state));
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        // All reads in this adapter must come from the player's compensated world or this placement's overlay.
        BlockState overlay = overlayStates.get(pos.asLong());
        return overlay != null ? overlay : blockAccess.getBlockStateAt(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        if (isOutsideBuildHeight(pos)) {
            return false;
        }

        BlockState oldState = getBlockState(pos);
        if (oldState == state) {
            return true;
        }

        overlayStates.put(pos.asLong(), state);
        recordChange(pos, state);

        if ((flags & Block.UPDATE_NEIGHBORS) != 0) {
            updateNeighborsAt(pos, oldState.getBlock());
        }

        // Match Level#setBlock's vanilla shape propagation without touching chunks, Bukkit events,
        // block entities, or live-world neighbor updates.
        if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0 && recursionLeft > 0) {
            int neighborFlags = flags & ~(Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_CLIENTS);
            oldState.updateIndirectNeighbourShapes(this, pos, neighborFlags, recursionLeft - 1);
            state.updateNeighbourShapes(this, pos, neighborFlags, recursionLeft - 1);
            state.updateIndirectNeighbourShapes(this, pos, neighborFlags, recursionLeft - 1);
        }
        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moved) {
        return setBlock(pos, Block.stateById(0), 3, MAX_NEIGHBOR_UPDATES);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
        return setBlock(pos, Block.stateById(0), 3, recursionLeft);
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        return predicate.test(getFluidState(pos));
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        // Block update broadcasts are intentionally suppressed during prediction simulation.
    }

    @Override
    public void neighborShapeChanged(Direction direction, BlockPos pos, BlockPos neighborPos, BlockState neighborState, int updateFlags, int updateLimit) {
        if (updateLimit <= 0) {
            return;
        }

        NeighborUpdater.executeShapeUpdate(this, direction, pos, neighborPos, neighborState, updateFlags, updateLimit - 1);
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block) {
        updateNeighborsAt(pos, block, null);
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block, @Nullable Orientation orientation) {
        updateNeighborsAtExceptFromFacing(pos, block, null, orientation);
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, @Nullable Direction skippedDirection, @Nullable Orientation orientation) {
        for (Direction direction : NeighborUpdater.UPDATE_ORDER) {
            if (direction != skippedDirection) {
                neighborChanged(pos.relative(direction), block, orientation);
            }
        }
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, @Nullable Orientation orientation) {
        if (isOutsideBuildHeight(pos)) {
            return;
        }

        neighborChanged(getBlockState(pos), pos, block, orientation, false);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        applyPaperPatchedRedstoneOpenableUpdate(pos, state);
    }

    private void applyPaperPatchedRedstoneOpenableUpdate(BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (!(block instanceof TrapDoorBlock || block instanceof DoorBlock || block instanceof FenceGateBlock)) {
            return;
        }
        if (!state.hasProperty(BlockStateProperties.POWERED) || !state.hasProperty(BlockStateProperties.OPEN)) {
            return;
        }

        boolean powered = hasNeighborSignal(pos);
        if (powered == state.getValue(BlockStateProperties.POWERED)) {
            return;
        }

        BlockState updatedState = state
                .setValue(BlockStateProperties.POWERED, powered)
                .setValue(BlockStateProperties.OPEN, powered);
        setBlock(pos, updatedState, Block.UPDATE_CLIENTS, MAX_NEIGHBOR_UPDATES);
    }

    @Override
    public void playSeededSound(@Nullable Entity entity, double x, double y, double z, Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source, float volume, float pitch, long seed) {
        // Sound emission is intentionally ignored during prediction simulation.
    }

    @Override
    public void playSeededSound(@Nullable Entity sourceEntity, @Nullable Entity targetEntity, Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source, float volume, float pitch, long seed) {
        // Sound emission is intentionally ignored during prediction simulation.
    }

    @Override
    public void explode(@Nullable Entity entity, @Nullable net.minecraft.world.damagesource.DamageSource damageSource, @Nullable net.minecraft.world.level.ExplosionDamageCalculator explosionDamageCalculator, double x, double y, double z, float power, boolean fire, ExplosionInteraction interaction, net.minecraft.core.particles.ParticleOptions smallExplosionParticles, net.minecraft.core.particles.ParticleOptions largeExplosionParticles, net.minecraft.util.random.WeightedList<net.minecraft.core.particles.ExplosionParticleInfo> explosionParticles, Holder<net.minecraft.sounds.SoundEvent> sound) {
        SmoketestPredictionSafety.forbiddenAccess("Level#explode");
        throw new UnsupportedOperationException("Explosions are forbidden in compensated placement simulation");
    }

    @Override
    public String gatherChunkSourceStats() {
        return "compensated-placement";
    }

    @Override
    public @Nullable Entity getEntity(int id) {
        return null;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Collection dragonParts() {
        return Collections.emptyList();
    }

    @Override
    public TickRateManager tickRateManager() {
        return tickRateManager;
    }

    @Override
    public LevelData.RespawnData getRespawnData() {
        return placementLevelData.getRespawnData();
    }

    @Override
    public void setRespawnData(LevelData.RespawnData respawnData) {
        placementLevelData.setSpawn(respawnData);
    }

    @Override
    public net.minecraft.world.clock.ClockManager clockManager() {
        return null;
    }

    @Override
    public @Nullable MapItemSavedData getMapData(MapId mapId) {
        return null;
    }

    @Override
    public LevelData getLevelData() {
        return placementLevelData;
    }

    @Override
    public void destroyBlockProgress(int entityId, BlockPos pos, int progress) {
        // Block break progress is intentionally ignored during prediction simulation.
    }

    @Override
    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    @Override
    public RecipeAccess recipeAccess() {
        return EMPTY_RECIPE_ACCESS;
    }

    @Override
    public LevelEntityGetter<Entity> getEntities() {
        return entities;
    }

    @Override
    public ChunkSource getChunkSource() {
        return chunkSource;
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus requiredStatus, boolean nonnull) {
        SmoketestPredictionSafety.forbiddenAccess("Level#getChunk");
        throw new UnsupportedOperationException("Chunk access is forbidden in compensated placement simulation");
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return blockAccess.isChunkLoaded(x, z);
    }

    @Override
    public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types heightmapType, int x, int z) {
        return snapshot.getMaxY();
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return DEFAULT_BIOME;
    }

    @Override
    public RegistryAccess registryAccess() {
        return REGISTRY_ACCESS;
    }

    @Override
    public DimensionType dimensionType() {
        return dimensionType;
    }

    @Override
    public Holder<DimensionType> dimensionTypeRegistration() {
        return dimensionTypeRegistration;
    }

    @Override
    public ResourceKey<Level> dimension() {
        return Level.OVERWORLD;
    }

    @Override
    public RandomSource getRandom() {
        return randomSource;
    }

    @Override
    public int getSeaLevel() {
        return SEA_LEVEL;
    }

    @Override
    public net.minecraft.world.attribute.EnvironmentAttributeSystem environmentAttributes() {
        return environmentAttributeSystem;
    }

    @Override
    public @NotNull LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public BlockGetter getChunkForCollisions(int x, int z) {
        return this;
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB aabb) {
        return Collections.emptyList();
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity entity, AABB aabb, Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> entityTypeTest, AABB aabb, Predicate<? super T> predicate) {
        return Collections.emptyList();
    }

    @Override
    public List<? extends Player> players() {
        return Collections.emptyList();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public void setBlockEntity(BlockEntity blockEntity) {
        SmoketestPredictionSafety.forbiddenAccess("Level#setBlockEntity");
        throw new UnsupportedOperationException("Block entities are forbidden in compensated placement simulation");
    }

    @Override
    public int getHeight() {
        return snapshot.getMaxY() - snapshot.getMinY();
    }

    @Override
    public int getMaxY() {
        return snapshot.getMaxY() - 1;
    }

    @Override
    public int getMinY() {
        return snapshot.getMinY();
    }

    @Override
    public boolean isInsideBuildHeight(int blockY) {
        return blockY >= snapshot.getMinY() && blockY < snapshot.getMaxY();
    }

    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        return isOutsideBuildHeight(pos.getY());
    }

    @Override
    public boolean isOutsideBuildHeight(int blockY) {
        return blockY < snapshot.getMinY() || blockY >= snapshot.getMaxY();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return worldBorder;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public void playSound(@Nullable Entity entity, BlockPos pos, net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        // Sound emission is intentionally ignored during prediction simulation.
    }

    @Override
    public void playSound(@Nullable Entity except, double x, double y, double z, net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        // Sound emission is intentionally ignored during prediction simulation.
    }

    @Override
    public void playSound(@Nullable Entity except, double x, double y, double z, Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        // Sound emission is intentionally ignored during prediction simulation.
    }

    @Override
    public void playSound(@Nullable Entity except, Entity sourceEntity, net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        // Sound emission is intentionally ignored during prediction simulation.
    }

    @Override
    public void addParticle(net.minecraft.core.particles.ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
        // Particle emission is intentionally ignored during prediction simulation.
    }

    @Override
    public void levelEvent(@Nullable Entity entity, int type, BlockPos pos, int data) {
        // World events are intentionally ignored during prediction simulation.
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
        // Game events are intentionally ignored during prediction simulation.
    }

    @Override
    public net.minecraft.server.level.ServerLevel getMinecraftWorld() {
        SmoketestPredictionSafety.forbiddenAccess("Level#getMinecraftWorld");
        return null;
    }

    @Override
    public org.bukkit.craftbukkit.CraftWorld getWorld() {
        SmoketestPredictionSafety.forbiddenAccess("Level#getWorld");
        return null;
    }

    @Override
    public net.minecraft.server.MinecraftServer getServer() {
        SmoketestPredictionSafety.forbiddenAccess("Level#getServer");
        return null;
    }

    private static DimensionType createDimensionType(PlacementSnapshot snapshot) {
        int height = snapshot.getMaxY() - snapshot.getMinY();
        DimensionType.MonsterSettings monsterSettings =
                new DimensionType.MonsterSettings(ConstantInt.of(0), 0);
        for (java.lang.reflect.Constructor<?> constructor : DimensionType.class.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            try {
                if (parameterTypes.length == 14 && parameterTypes[0] == boolean.class
                        && parameterTypes[11].isEnum()) {
                    Object cardinalLight = enumConstant(parameterTypes[11], "DEFAULT");
                    return (DimensionType) constructor.newInstance(
                            false, true, false, 1.0D,
                            snapshot.getMinY(), height, height,
                            BlockTags.INFINIBURN_OVERWORLD, 0.0F, monsterSettings,
                            DimensionType.Skybox.OVERWORLD, cardinalLight,
                            EnvironmentAttributeMap.EMPTY, HolderSet.empty()
                    );
                }
                if (parameterTypes.length == 16 && parameterTypes[0] == boolean.class
                        && parameterTypes[12].isEnum() && parameterTypes[15] == Optional.class) {
                    Object cardinalLight = enumConstant(parameterTypes[12], "DEFAULT");
                    // 26.2 passes infiniburn as a HolderSet instead of a TagKey.
                    Object infiniburn = TagKey.class.isAssignableFrom(parameterTypes[8])
                            ? BlockTags.INFINIBURN_OVERWORLD
                            : HolderSet.empty();
                    return (DimensionType) constructor.newInstance(
                            false, true, false, false, 1.0D,
                            snapshot.getMinY(), height, height,
                            infiniburn, 0.0F, monsterSettings,
                            DimensionType.Skybox.OVERWORLD, cardinalLight,
                            EnvironmentAttributeMap.EMPTY, HolderSet.empty(), Optional.empty()
                    );
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to create compensated dimension type", exception);
            }
        }
        throw new IllegalStateException("Unsupported modern DimensionType ABI");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> enumType, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumType.asSubclass(Enum.class), name);
    }

    private static Biome createDefaultBiome() {
        return new Biome.BiomeBuilder()
                .hasPrecipitation(false)
                .temperature(0.8F)
                .downfall(0.4F)
                .temperatureAdjustment(Biome.TemperatureModifier.NONE)
                .putAttributes(EnvironmentAttributeMap.EMPTY)
                .specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects(
                        0x3F76E4,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        net.minecraft.world.level.biome.BiomeSpecialEffects.GrassColorModifier.NONE
                ))
                .mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
                .generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
                .build();
    }

    private static final class PlacementLevelData implements WritableLevelData {
        private LevelData.RespawnData respawnData = LevelData.RespawnData.DEFAULT;

        private PlacementLevelData(PlacementSnapshot snapshot) {
            BlockPos spawnPos = new BlockPos(0, Math.max(snapshot.getMinY(), Math.min(snapshot.getMaxY() - 1, SEA_LEVEL)), 0);
            this.respawnData = new LevelData.RespawnData(net.minecraft.core.GlobalPos.of(Level.OVERWORLD, spawnPos), 0.0F, 0.0F);
        }

        @Override
        public LevelData.RespawnData getRespawnData() {
            return respawnData;
        }

        @Override
        public long getGameTime() {
            return 0L;
        }

        @Override
        public boolean isHardcore() {
            return false;
        }

        @Override
        public Difficulty getDifficulty() {
            return Difficulty.NORMAL;
        }

        @Override
        public boolean isDifficultyLocked() {
            return false;
        }

        @Override
        public void setSpawn(LevelData.RespawnData respawnData) {
            this.respawnData = respawnData;
        }
    }

    private final class UnsupportedChunkSource extends ChunkSource {
        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus requiredStatus, boolean load) {
            throw new UnsupportedOperationException("Chunk access is forbidden in compensated placement simulation");
        }

        @Override
        public void tick(java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks) {
        }

        @Override
        public String gatherStats() {
            return "compensated-placement";
        }

        @Override
        public int getLoadedChunksCount() {
            return 0;
        }

        @Override
        public @NotNull LevelLightEngine getLightEngine() {
            return CompensatedPlacementWorld.this.getLightEngine();
        }

        @Override
        public BlockGetter getLevel() {
            return CompensatedPlacementWorld.this;
        }
    }

    private static final class EmptyLevelEntityGetter implements LevelEntityGetter<Entity> {
        @Override
        public @Nullable Entity get(int id) {
            return null;
        }

        @Override
        public @Nullable Entity get(UUID uuid) {
            return null;
        }

        @Override
        public Iterable<Entity> getAll() {
            return Collections.emptyList();
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> entityTypeTest, AbortableIterationConsumer<U> consumer) {
        }

        @Override
        public void get(AABB aabb, Consumer<Entity> consumer) {
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> entityTypeTest, AABB aabb, AbortableIterationConsumer<U> consumer) {
        }
    }
}
