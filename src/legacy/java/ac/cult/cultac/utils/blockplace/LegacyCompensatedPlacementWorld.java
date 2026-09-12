package ac.cult.cultac.utils.blockplace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.state.BlockState;
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
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.misc.Unsafe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Detached compensated Level implementation for Paper's protocol-768 ABI. */
public final class LegacyCompensatedPlacementWorld extends Level implements PlacementWorldAdapter {
    private static final int SEA_LEVEL = 63;
    private static final int MAX_NEIGHBOR_UPDATES = 512;
    private static final RegistryAccess.Frozen REGISTRY_ACCESS = RegistryAccess.EMPTY;
    private static final FeatureFlagSet ENABLED_FEATURES = FeatureFlags.DEFAULT_FLAGS;
    private static final Unsafe UNSAFE = resolveUnsafe();
    private static final long CLIENT_SIDE_OFFSET = levelFieldOffset("isClientSide");
    private static final long RANDOM_OFFSET = levelFieldOffset("random");
    private static final long SOUND_RANDOM_OFFSET = levelFieldOffset("threadSafeRandom");
    private static final Holder<Biome> DEFAULT_BIOME = Holder.direct(createDefaultBiome());
    private static final RecipeAccess EMPTY_RECIPE_ACCESS = new RecipeAccess() {
        @Override
        public RecipePropertySet propertySet(ResourceKey<RecipePropertySet> key) {
            return RecipePropertySet.EMPTY;
        }

        @Override
        public SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutterRecipes() {
            return SelectableRecipe.SingleInputSet.empty();
        }
    };

    private PlacementBlockAccess blockAccess;
    private PlacementSnapshot snapshot;
    private LegacyLevelData placementLevelData;
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
    private BiomeManager biomeManager;

    private LegacyCompensatedPlacementWorld(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        super(
                new LegacyLevelData(snapshot),
                Level.OVERWORLD,
                REGISTRY_ACCESS,
                Holder.direct(createDimensionType(snapshot)),
                false,
                false,
                0L,
                MAX_NEIGHBOR_UPDATES,
                null,
                null,
                World.Environment.NORMAL,
                ignored -> null,
                Runnable::run
        );
        this.blockAccess = blockAccess;
        this.snapshot = snapshot;
        initializeState();
    }

    public static LegacyCompensatedPlacementWorld create(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        try {
            LegacyCompensatedPlacementWorld world =
                    (LegacyCompensatedPlacementWorld) UNSAFE.allocateInstance(LegacyCompensatedPlacementWorld.class);
            world.blockAccess = blockAccess;
            world.snapshot = snapshot;
            world.initializeState();
            SmoketestPredictionSafety.detachedAdapter(world);
            return world;
        } catch (InstantiationException exception) {
            throw new IllegalStateException("Failed to allocate legacy compensated placement world", exception);
        }
    }

    private void initializeState() {
        placementLevelData = new LegacyLevelData(snapshot);
        dimensionType = createDimensionType(snapshot);
        dimensionTypeRegistration = Holder.direct(dimensionType);
        randomSource = RandomSource.create(0L);
        // Vanilla 1.21.3 block interactions read these public Level fields
        // directly. Unsafe allocation bypasses the Level constructor; virtual
        // isClientSide()/getRandom() overrides alone do not initialize them.
        UNSAFE.putBoolean(this, CLIENT_SIDE_OFFSET, true);
        UNSAFE.putObject(this, RANDOM_OFFSET, randomSource);
        UNSAFE.putObject(this, SOUND_RANDOM_OFFSET, RandomSource.createThreadSafe());
        changedBlocks = new LinkedHashMap<>();
        overlayStates = new LinkedHashMap<>();
        tickRateManager = new TickRateManager();
        scoreboard = new Scoreboard();
        worldBorder = new WorldBorder();
        entities = new EmptyLevelEntityGetter();
        biomeManager = new BiomeManager((x, y, z) -> DEFAULT_BIOME, 0L);
        chunkSource = new UnsupportedChunkSource();
    }

    @Override
    public Level level() {
        return this;
    }

    @Override
    public PlacementResult buildResult(BlockPos primaryPlacedPosition) {
        return PlacementResult.success(new ArrayList<>(changedBlocks.values()), primaryPlacedPosition.immutable(), true);
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

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState overlay = overlayStates.get(pos.asLong());
        return overlay != null ? overlay : blockAccess.getBlockStateAt(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        if (isOutsideBuildHeight(pos)) return false;
        BlockState oldState = getBlockState(pos);
        if (oldState == state) return true;
        overlayStates.put(pos.asLong(), state);
        changedBlocks.put(pos.asLong(), new PlacementResult.ChangedBlock(pos.immutable(), state));
        if ((flags & Block.UPDATE_NEIGHBORS) != 0) updateNeighborsAt(pos, oldState.getBlock());
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
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void neighborShapeChanged(Direction direction, BlockPos pos, BlockPos neighborPos,
                                     BlockState neighborState, int updateFlags, int updateLimit) {
        if (updateLimit > 0) {
            NeighborUpdater.executeShapeUpdate(this, direction, pos, neighborPos, neighborState, updateFlags, updateLimit - 1);
        }
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
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, @Nullable Direction skipped, @Nullable Orientation orientation) {
        for (Direction direction : NeighborUpdater.UPDATE_ORDER) {
            if (direction != skipped) neighborChanged(pos.relative(direction), block, orientation);
        }
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, @Nullable Orientation orientation) {
        // Neighbor shape propagation is handled above; live-world redstone work is forbidden here.
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, double x, double y, double z,
                                Holder<net.minecraft.sounds.SoundEvent> sound,
                                net.minecraft.sounds.SoundSource source, float volume, float pitch, long seed) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, @Nullable Entity entity,
                                Holder<net.minecraft.sounds.SoundEvent> sound,
                                net.minecraft.sounds.SoundSource source, float volume, float pitch, long seed) {
    }

    @Override
    public void explode(@Nullable Entity entity, @Nullable net.minecraft.world.damagesource.DamageSource damageSource,
                        @Nullable net.minecraft.world.level.ExplosionDamageCalculator calculator,
                        double x, double y, double z, float power, boolean fire, ExplosionInteraction interaction,
                        net.minecraft.core.particles.ParticleOptions small,
                        net.minecraft.core.particles.ParticleOptions large,
                        Holder<net.minecraft.sounds.SoundEvent> sound) {
        SmoketestPredictionSafety.forbiddenAccess("Level#explode");
        throw new UnsupportedOperationException("Explosions are forbidden in compensated placement simulation");
    }

    @Override public String gatherChunkSourceStats() { return "compensated-placement"; }
    @Override public @Nullable Entity getEntity(int id) { return null; }
    @Override public TickRateManager tickRateManager() { return tickRateManager; }
    @Override public @Nullable MapItemSavedData getMapData(MapId id) { return null; }
    @Override public void setMapData(MapId id, MapItemSavedData data) { }
    @Override public MapId getFreeMapId() { return new MapId(0); }
    @Override public void destroyBlockProgress(int entityId, BlockPos pos, int progress) { }
    @Override public Scoreboard getScoreboard() { return scoreboard; }
    @Override public RecipeAccess recipeAccess() { return EMPTY_RECIPE_ACCESS; }
    @Override public LevelEntityGetter<Entity> getEntities() { return entities; }
    @Override public PotionBrewing potionBrewing() { return PotionBrewing.EMPTY; }

    @Override
    public FuelValues fuelValues() {
        SmoketestPredictionSafety.forbiddenAccess("Level#fuelValues");
        throw new UnsupportedOperationException("Fuel access is forbidden in compensated placement simulation");
    }

    @Override public long nextSubTickCount() { return 0L; }
    @Override public LevelData getLevelData() { return placementLevelData; }
    @Override public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) { return new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0.0F); }
    @Override public @Nullable net.minecraft.server.MinecraftServer getServer() { return null; }
    @Override public ChunkSource getChunkSource() { return chunkSource; }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load) {
        SmoketestPredictionSafety.forbiddenAccess("Level#getChunk");
        throw new UnsupportedOperationException("Chunk access is forbidden in compensated placement simulation");
    }

    @Override public boolean hasChunk(int x, int z) { return blockAccess.isChunkLoaded(x, z); }
    @Override public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) { return snapshot.getMaxY(); }
    @Override public int getSkyDarken() { return 0; }
    @Override public BiomeManager getBiomeManager() { return biomeManager; }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { return DEFAULT_BIOME; }
    @Override public RegistryAccess registryAccess() { return REGISTRY_ACCESS; }
    @Override public DimensionType dimensionType() { return dimensionType; }
    @Override public Holder<DimensionType> dimensionTypeRegistration() { return dimensionTypeRegistration; }
    @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    @Override public RandomSource getRandom() { return randomSource; }
    @Override public int getSeaLevel() { return SEA_LEVEL; }
    @Override public float getShade(Direction direction, boolean shade) {
        if (!shade) return 1.0F;
        return switch (direction) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
    }
    @Override public WorldBorder getWorldBorder() { return worldBorder; }
    @Override public BlockGetter getChunkForCollisions(int x, int z) { return this; }
    @Override public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) { return Collections.emptyList(); }
    @Override public List<Entity> getEntities(@Nullable Entity entity, AABB box, Predicate<? super Entity> predicate) { return Collections.emptyList(); }
    @Override public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB box, Predicate<? super T> predicate) { return Collections.emptyList(); }
    @Override public List<? extends Player> players() { return Collections.emptyList(); }
    @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }

    @Override
    public void setBlockEntity(BlockEntity blockEntity) {
        SmoketestPredictionSafety.forbiddenAccess("Level#setBlockEntity");
        throw new UnsupportedOperationException("Block entities are forbidden in compensated placement simulation");
    }

    @Override public int getHeight() { return snapshot.getMaxY() - snapshot.getMinY(); }
    @Override public int getMaxY() { return snapshot.getMaxY() - 1; }
    @Override public int getMinY() { return snapshot.getMinY(); }
    @Override public boolean isInsideBuildHeight(int y) { return y >= snapshot.getMinY() && y < snapshot.getMaxY(); }
    @Override public boolean isOutsideBuildHeight(BlockPos pos) { return isOutsideBuildHeight(pos.getY()); }
    @Override public boolean isOutsideBuildHeight(int y) { return y < snapshot.getMinY() || y >= snapshot.getMaxY(); }
    @Override public LevelTickAccess<Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public @NotNull LevelLightEngine getLightEngine() { return LevelLightEngine.EMPTY; }

    @Override public void playSound(@Nullable Player player, BlockPos pos, net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source, float volume, float pitch) { }
    @Override public void addParticle(net.minecraft.core.particles.ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) { }
    @Override public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) { }
    @Override public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) { }
    @Override public @Nullable net.minecraft.server.level.ServerLevel getMinecraftWorld() { return null; }
    @Override public @Nullable org.bukkit.craftbukkit.CraftWorld getWorld() { return null; }

    private static long levelFieldOffset(String name) {
        try {
            return UNSAFE.objectFieldOffset(Level.class.getDeclaredField(name));
        } catch (NoSuchFieldException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Unsafe resolveUnsafe() {
        try {
            java.lang.reflect.Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static DimensionType createDimensionType(PlacementSnapshot snapshot) {
        Object[] commonArguments = {
                OptionalLong.empty(), false, true, false, false, 1.0D, true, false,
                snapshot.getMinY(), snapshot.getMaxY() - snapshot.getMinY(), snapshot.getMaxY() - snapshot.getMinY(),
                BlockTags.INFINIBURN_OVERWORLD, ResourceLocation.withDefaultNamespace("overworld"), 0.0F
        };
        DimensionType.MonsterSettings monsterSettings =
                new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0);
        for (java.lang.reflect.Constructor<?> constructor : DimensionType.class.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            try {
                if (parameterTypes.length == 15
                        && parameterTypes[0] == OptionalLong.class
                        && parameterTypes[14] == DimensionType.MonsterSettings.class) {
                    Object[] arguments = java.util.Arrays.copyOf(commonArguments, 15);
                    arguments[14] = monsterSettings;
                    return (DimensionType) constructor.newInstance(arguments);
                }
                if (parameterTypes.length == 16
                        && parameterTypes[0] == OptionalLong.class
                        && parameterTypes[14] == Optional.class
                        && parameterTypes[15] == DimensionType.MonsterSettings.class) {
                    Object[] arguments = java.util.Arrays.copyOf(commonArguments, 16);
                    arguments[14] = Optional.empty();
                    arguments[15] = monsterSettings;
                    return (DimensionType) constructor.newInstance(arguments);
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to create compensated dimension type", exception);
            }
        }
        throw new IllegalStateException("Unsupported legacy DimensionType ABI");
    }

    private static Biome createDefaultBiome() {
        return new Biome.BiomeBuilder()
                .hasPrecipitation(false).temperature(0.8F).downfall(0.4F)
                .temperatureAdjustment(Biome.TemperatureModifier.NONE)
                .specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder()
                        .fogColor(0xC0D8FF).waterColor(0x3F76E4).waterFogColor(0x050533).skyColor(0x78A7FF).build())
                .mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
                .generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
                .build();
    }

    private static final class LegacyLevelData implements WritableLevelData {
        private BlockPos spawnPos;

        private LegacyLevelData(PlacementSnapshot snapshot) {
            spawnPos = new BlockPos(0, Math.max(snapshot.getMinY(), Math.min(snapshot.getMaxY() - 1, SEA_LEVEL)), 0);
        }

        @Override public BlockPos getSpawnPos() { return spawnPos; }
        @Override public float getSpawnAngle() { return 0.0F; }
        @Override public long getGameTime() { return 0L; }
        @Override public long getDayTime() { return 0L; }
        @Override public boolean isThundering() { return false; }
        @Override public boolean isRaining() { return false; }
        @Override public void setRaining(boolean raining) { }
        @Override public boolean isHardcore() { return false; }
        @Override public Difficulty getDifficulty() { return Difficulty.NORMAL; }
        @Override public boolean isDifficultyLocked() { return false; }
        @Override public void setSpawn(BlockPos pos, float angle) { spawnPos = pos.immutable(); }
    }

    private final class UnsupportedChunkSource extends ChunkSource {
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load) { throw new UnsupportedOperationException("Chunk access is forbidden"); }
        @Override public void tick(java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks) { }
        @Override public String gatherStats() { return "compensated-placement"; }
        @Override public int getLoadedChunksCount() { return 0; }
        @Override public @NotNull LevelLightEngine getLightEngine() { return LevelLightEngine.EMPTY; }
        @Override public BlockGetter getLevel() { return LegacyCompensatedPlacementWorld.this; }
    }

    private static final class EmptyLevelEntityGetter implements LevelEntityGetter<Entity> {
        @Override public @Nullable Entity get(int id) { return null; }
        @Override public @Nullable Entity get(UUID uuid) { return null; }
        @Override public Iterable<Entity> getAll() { return Collections.emptyList(); }
        @Override public <U extends Entity> void get(EntityTypeTest<Entity, U> type, AbortableIterationConsumer<U> consumer) { }
        @Override public void get(AABB box, Consumer<Entity> consumer) { }
        @Override public <U extends Entity> void get(EntityTypeTest<Entity, U> type, AABB box, AbortableIterationConsumer<U> consumer) { }
    }
}
