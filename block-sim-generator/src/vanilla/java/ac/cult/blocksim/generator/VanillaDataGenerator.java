package ac.cult.blocksim.generator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Data only: no placement, use, breaking or shape-update logic is generated. */
public final class VanillaDataGenerator {
    private static final java.util.IdentityHashMap<net.minecraft.world.level.block.state.properties.Property<?>, Integer> PROPERTY_IDS = new java.util.IdentityHashMap<>();
    private static final BlockPos[] POSITIONS = {
        new BlockPos(0, 0, 0), new BlockPos(1, 64, -3), new BlockPos(123, -17, 456), new BlockPos(-29_999_000, 200, 12_345)
    };
    private static final List<ProbeGetter> WORLDS = List.of(new ProbeGetter(false, false), new ProbeGetter(true, false), new ProbeGetter(false, true));
    private static final Map<List<AABB>, Integer> SHAPE_IDS = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        SharedConstants.CHECK_DATA_FIXER_SCHEMA = false;
        Bootstrap.bootStrap();
        // Client predicates such as BlockBehaviour#isSuffocating read builtin
        // tag memberships. Bootstrap alone registers the holders without tags.
        var builtins = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var vanilla = net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource();
        try (var resources = new net.minecraft.server.packs.resources.MultiPackResourceManager(
                net.minecraft.server.packs.PackType.SERVER_DATA, java.util.List.of(vanilla.fullResources()))) {
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtins)
                    .forEach(net.minecraft.core.Registry.PendingTags::apply);
        }
        // Vault and other constructors create native stacks. Bootstrap registers
        // items before the client's registry-component initialization step.
        var worldLookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        if (args[0].equals("--world-registries-only")) {
            writeClientWorldDefaults(Path.of(args[1]), worldLookup, Path.of(args[2]));
            return;
        }
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(worldLookup).forEach(pending -> pending.apply());
        Path output = Path.of(args[0]), jar = Path.of(args[1]), reportRoot = Path.of(args[2]).resolve("reports");
        Files.createDirectories(output);
        byte[] blockReport = Files.readAllBytes(reportRoot.resolve("blocks.json"));
        JsonObject report = JsonParser.parseString(new String(blockReport, StandardCharsets.UTF_8)).getAsJsonObject();
        var blocks = new ArrayList<Block>();
        BuiltInRegistries.BLOCK.forEach(blocks::add);
        blocks.sort(Comparator.comparingInt(b -> Block.getId(b.getStateDefinition().getPossibleStates().getFirst())));
        var facts = new ArrayList<Facts>();
        int dynamicStates = 0;
        for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            Facts primary = evaluate(state, WORLDS.getFirst(), POSITIONS[0]);
            int dynamic = 0;
            for (ProbeGetter world : WORLDS) for (BlockPos pos : POSITIONS) {
                Facts probe = evaluate(state, world, pos);
                if (primary.flags != probe.flags || Float.floatToIntBits(primary.destroy) != Float.floatToIntBits(probe.destroy)) dynamic |= 1;
                if (primary.collision != probe.collision) dynamic |= 2;
                if (primary.outline != probe.outline) dynamic |= 4;
                if (primary.support != probe.support) dynamic |= 8;
                if (primary.interaction != probe.interaction) dynamic |= 16;
                if (primary.sturdy != probe.sturdy) dynamic |= 32;
            }
            // This is recorded separately, not mistaken for proof of context independence.
            if (state.getBlock().hasDynamicShape()) dynamic |= 2 | 4 | 8 | 16 | 32;
            if (dynamic != 0) dynamicStates++;
            facts.add(primary.withDynamic(dynamic));
        }
        Map<String, Set<String>> tags = tags(jar);
        Path dataFile = output.resolve("data.bin.gz");
        try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(dataFile))))) {
            out.writeInt(0x4253494d); out.writeInt(4);
            out.writeUTF("26.3"); out.writeUTF(sha256(Files.readAllBytes(jar))); out.writeUTF(sha256(blockReport));
            writeStrings(out, FeatureFlags.REGISTRY.toNames(FeatureFlags.DEFAULT_FLAGS).stream().map(Object::toString).sorted().toList());
            out.writeInt(ChunkPyramid.MAX_CHUNK_COORDINATE_VALUE);
            out.writeInt(SHAPE_IDS.size());
            for (List<AABB> shape : SHAPE_IDS.keySet()) {
                out.writeInt(shape.size());
                for (AABB box : shape) {
                    out.writeDouble(box.minX); out.writeDouble(box.minY); out.writeDouble(box.minZ);
                    out.writeDouble(box.maxX); out.writeDouble(box.maxY); out.writeDouble(box.maxZ);
                }
            }
            out.writeInt(blocks.size());
            for (Block block : blocks) writeBlock(out, block, report);
            out.writeInt(facts.size());
            for (Facts fact : facts) fact.write(out);
            out.writeInt(BuiltInRegistries.ITEM.size());
            for (Item item : BuiltInRegistries.ITEM) {
                String key = BuiltInRegistries.ITEM.getKey(item).toString();
                out.writeInt(BuiltInRegistries.ITEM.getId(item)); out.writeUTF(key); out.writeUTF(item.getClass().getName());
                out.writeUTF(item instanceof BlockItem blockItem ? BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString() : "");
                var itemBindings = bindings(item, Item.class);
                itemBindings.put("classHierarchy", hierarchy(item.getClass()));
                itemBindings.put("classInterfaces", interfaces(item.getClass()));
                itemBindings.put("components.defaultNbt", net.minecraft.core.component.DataComponentMap.CODEC.encodeStart(
                    net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, worldLookup), item.components()).getOrThrow().toString());
                itemBindings.put("requiredFeatures", String.join(",", FeatureFlags.REGISTRY.toNames(item.requiredFeatures()).stream().map(Object::toString).sorted().toList()));
                writeMap(out, itemBindings);
                Path components = reportRoot.resolve("minecraft/components/item/" + key.substring(key.indexOf(':') + 1) + ".json");
                if (!Files.isRegularFile(components)) throw new IllegalStateException("Missing component report " + key);
                writeText(out, Files.readString(components));
            }
            out.writeInt(tags.size());
            for (var tag : tags.entrySet()) { out.writeUTF(tag.getKey()); writeStrings(out, new TreeSet<>(tag.getValue())); }
        }
        try (var compressed = new GZIPOutputStream(Files.newOutputStream(output.resolve("blocks.json.gz")))) {
            compressed.write(blockReport);
        }
        Files.deleteIfExists(output.resolve("blocks.json"));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("version", "26.3"); provenance.put("vanillaJarSha256", sha256(Files.readAllBytes(jar)));
        provenance.put("blockReportSha256", sha256(blockReport)); provenance.put("dataSha256", sha256(Files.readAllBytes(dataFile)));
        provenance.put("blocks", blocks.size()); provenance.put("states", facts.size()); provenance.put("items", BuiltInRegistries.ITEM.size());
        provenance.put("shapes", SHAPE_IDS.size()); provenance.put("tags", tags.size()); provenance.put("dynamicStates", dynamicStates);
        provenance.put("probeWorlds", List.of("air", "stone", "air-with-open-shulker")); provenance.put("probePositions", List.of("0,0,0", "1,64,-3", "123,-17,456", "-29999000,200,12345"));
        provenance.put("declaredDynamicShapesRequirePort", true);
        provenance.put("maxValidChunkCoordinate", ChunkPyramid.MAX_CHUNK_COORDINATE_VALUE);
        var interactionRegistries = new JsonObject();
        try (var zip = new ZipFile(jar.toFile())) {
            for (String registry : List.of("block_transformer", "worldgen/block_state_provider", "enchantment", "jukebox_song", "instrument")) {
                var values = new JsonObject();
                String prefix = "data/minecraft/" + registry + "/";
                for (var entry : zip.stream().filter(e -> e.getName().startsWith(prefix) && e.getName().endsWith(".json")).sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
                    String key = "minecraft:" + entry.getName().substring(prefix.length(), entry.getName().length() - 5);
                    try (var stream = zip.getInputStream(entry)) {
                        values.add(key, JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
                    }
                }
                interactionRegistries.add(registry.substring(registry.lastIndexOf('/') + 1), values);
            }
        }
        Path interactionFile = output.resolve("interaction-registries.json");
        Files.writeString(interactionFile, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(interactionRegistries) + "\n");
        provenance.put("interactionRegistriesSha256", sha256(Files.readAllBytes(interactionFile)));
        writeClientWorldDefaults(output.resolve("client-world-defaults.bin.gz"), worldLookup, jar);
        writeEntityTypes(output.resolve("entity-types.tsv"));
        if (args.length > 3) writeBlockIds(Path.of(args[3]));
        if (args.length > 4) writeBlockProps(Path.of(args[4]));
        Files.writeString(reportRoot.resolve("block-sim-provenance.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(provenance) + "\n");
        System.out.println("Generated " + facts.size() + " states, " + SHAPE_IDS.size() + " shapes, " + tags.size() + " tags; " + dynamicStates + " dynamic states");
    }

    private static void writeClientWorldDefaults(Path output, net.minecraft.core.HolderLookup.Provider lookup, Path jar) throws Exception {
        Files.createDirectories(output.getParent());
        var definitions = net.minecraft.resources.RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream()
                .filter(definition -> Set.of("minecraft:dimension_type", "minecraft:worldgen/biome", "minecraft:timeline", "minecraft:world_clock", "minecraft:painting_variant")
                        .contains(definition.key().identifier().toString())).toList();
        try (var resources = new net.minecraft.server.packs.resources.MultiPackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA,
                     List.of(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources()));
             var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(output))))) {
            out.writeInt(0x43574446); out.writeInt(5); out.writeUTF("26.3"); out.writeUTF(sha256(Files.readAllBytes(jar)));
            out.writeInt(definitions.size());
            for (var definition : definitions) writeClientWorldRegistry(out, lookup, definition, resources);
            var attributes = net.minecraft.core.registries.BuiltInRegistries.ENVIRONMENT_ATTRIBUTE;
            var keys = attributes.keySet().stream().sorted(Comparator.comparing(Object::toString)).toList();
            out.writeInt(keys.size());
            for (var key : keys) {
                var attribute = attributes.getValue(key);
                out.writeUTF(key.toString());
                out.writeBoolean(attribute.isSyncable());
                out.writeBoolean(attribute.type() == net.minecraft.world.attribute.AttributeTypes.BOOLEAN);
            }
            // Exact initial model ID order, matching the former VanillaBootstrap resource load.
            var base = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, base).forEach(net.minecraft.core.Registry.PendingTags::apply);
            var initial = net.minecraft.resources.RegistryDataLoader.load(resources, base.listRegistries().toList(),
                    net.minecraft.resources.RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
            var model = new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(base.registries(), initial.registries())).freeze();
            var modelRegistries = model.registries().sorted(Comparator.comparing(entry -> entry.key().identifier().toString())).toList();
            out.writeInt(modelRegistries.size());
            for (var entry : modelRegistries) writeInitialWorldOrder(out, entry.value());
            writeStrings(out, base.listRegistries().map(registry -> registry.key().identifier().toString()).sorted().toList());
            writeStrings(out, net.minecraft.resources.RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream()
                    .map(definition -> definition.key().identifier().toString()).sorted().toList());

        }
    }

    private static <T> void writeInitialWorldOrder(DataOutputStream out, net.minecraft.core.Registry<T> registry) throws Exception {
        out.writeUTF(registry.key().identifier().toString());
        writeStrings(out, registry.listElements().sorted(Comparator.comparingInt(holder -> registry.getId(holder.value())))
                .map(holder -> holder.key().identifier().toString()).toList());
    }

    private static <T> void writeClientWorldRegistry(DataOutputStream out, net.minecraft.core.HolderLookup.Provider lookup,
                                                    net.minecraft.resources.RegistryDataLoader.RegistryData<T> definition,
                                                    net.minecraft.server.packs.resources.ResourceManager resources) throws Exception {
        out.writeUTF(definition.key().identifier().toString());
        var entries = lookup.lookupOrThrow(definition.key()).listElements()
                .sorted(Comparator.comparing(holder -> holder.key().identifier().toString())).toList();
        out.writeInt(entries.size());
        var nbtOps = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, lookup);
        var jsonOps = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lookup);
        for (var entry : entries) {
            out.writeUTF(entry.key().identifier().toString());
            // StringTagVisitor sorts compound keys, preserving typed values and list order.
            byte[] nbt = definition.elementCodec().encodeStart(nbtOps, entry.value()).getOrThrow().toString().getBytes(StandardCharsets.UTF_8);
            out.writeInt(nbt.length); out.write(nbt);
            byte[] json = sortedJson(definition.elementCodec().encodeStart(jsonOps, entry.value()).getOrThrow()).toString().getBytes(StandardCharsets.UTF_8);
            out.writeInt(json.length); out.write(json);
        }
        // Native TagLoader preserves encounter order while removing duplicates.
        // Timeline layer order is significant, so these are lists rather than the membership-only block tag sets.
        var registry = lookup.lookupOrThrow(definition.key());
        var tags = net.minecraft.tags.TagLoader.loadTagsForRegistry(resources, definition.key(),
                (id, required) -> registry.get(net.minecraft.resources.ResourceKey.create(definition.key(), id)));
        out.writeInt(tags.size());
        for (var tag : tags.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().location().toString())).toList()) {
            out.writeUTF(tag.getKey().location().toString());
            writeStrings(out, tag.getValue().stream().map(holder -> holder.unwrapKey().orElseThrow().identifier().toString()).toList());
        }
    }

    private static JsonElement sortedJson(JsonElement value) {
        if (value.isJsonObject()) {
            var object = new JsonObject();
            value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> object.add(entry.getKey(), sortedJson(entry.getValue())));
            return object;
        }
        if (value.isJsonArray()) {
            var array = new com.google.gson.JsonArray(); value.getAsJsonArray().forEach(entry -> array.add(sortedJson(entry))); return array;
        }
        return value;
    }

    private static int propertyIdentity(net.minecraft.world.level.block.state.properties.Property<?> property) {
        return PROPERTY_IDS.computeIfAbsent(property, ignored -> PROPERTY_IDS.size());
    }

    private static void writeBlockProps(Path output) throws Exception {
        var source = new StringBuilder("""
                package ac.cult.blocksim.data;

                /** Generated pinned property identities; values use integers, booleans or enum ordinals. */
                public final class BlockProps {
                """);
        for (String name : List.of(
                "AGE_25", "AGE_4", "BELL_ATTACHMENT", "BOTTOM", "CANDLES", "CHEST_TYPE", "DOWN",
                "DOUBLE_BLOCK_HALF", "DRAG", "EAST", "EGGS", "EXTENDED", "FACING", "FALLING",
                "HALF", "HORIZONTAL_FACING", "LAYERS", "NORTH", "OPEN", "PICKLES", "PISTON_TYPE",
                "POTENT_SULFUR_STATE", "ROTATION_16", "SHORT", "SLAB_TYPE", "SOUTH", "STABILITY_DISTANCE", "TILT", "UP",
                "WATERLOGGED", "WEST")) {
            var property = (net.minecraft.world.level.block.state.properties.Property<?>)
                net.minecraft.world.level.block.state.properties.BlockStateProperties.class.getField(name).get(null);
            source.append("    public static final StateProperty ").append(name).append(" = new StateProperty(\"")
                .append(property.getName()).append("\", ").append(propertyIdentity(property)).append(");\n");
        }
        source.append("""

                    private BlockProps() {}
                }
                """);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, source);
    }

    private static void writeBlockIds(Path output) throws Exception {
        var source = new StringBuilder("""
                package ac.cult.blocksim.data;

                /** Generated 26.3 block handles; state lookup never resolves names. */
                public final class BlockIds {
                    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
                """);
        for (String name : List.of(
                "AIR", "ANVIL", "BAMBOO", "BEACON", "BUBBLE_COLUMN", "CAULDRON", "CAVE_VINES", "CAVE_VINES_PLANT",
                "CHEST", "CHIPPED_ANVIL", "CHORUS_PLANT", "COBWEB", "CRYING_OBSIDIAN", "DAMAGED_ANVIL",
                "END_GATEWAY", "END_PORTAL", "FARMLAND", "FIRE", "GLASS", "GLASS_PANE",
                "GLOWSTONE", "GLOW_LICHEN", "HONEY_BLOCK", "IRON_BARS", "KELP", "KELP_PLANT",
                "LADDER", "LAVA", "LECTERN", "LIGHT", "LILY_PAD", "MOVING_PISTON",
                "NETHER_PORTAL", "OBSIDIAN", "PALE_MOSS_CARPET", "PISTON", "PISTON_HEAD", "PITCHER_CROP", "PLAYER_HEAD",
                "PLAYER_WALL_HEAD", "POINTED_DRIPSTONE", "POTENT_SULFUR", "POWDER_SNOW", "REDSTONE_WIRE", "REINFORCED_DEEPSLATE", "RESPAWN_ANCHOR",
                "SCAFFOLDING", "SEA_LANTERN", "SEA_PICKLE", "SHELF_MUSHROOM", "SLIME_BLOCK", "SNOW", "SOUL_SAND",
                "SOUL_SOIL", "STICKY_PISTON", "STONE", "STRAW_BED", "SWEET_BERRY_BUSH", "TRAPPED_CHEST", "TURTLE_EGG",
                "TWISTING_VINES", "TWISTING_VINES_PLANT", "VINE", "WATER", "WEEPING_VINES", "WEEPING_VINES_PLANT")) {
            Block block = (Block) net.minecraft.world.level.block.Blocks.class.getField(name).get(null);
            source.append("    public static final BlockDefinition ").append(name).append(" = REGISTRY.block(")
                .append(Block.getId(block.defaultBlockState())).append(");\n");
        }
        source.append("""

                    private BlockIds() {}
                    public static boolean is(int state, BlockDefinition block) { return REGISTRY.block(state) == block; }
                }
                """);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, source);
    }

    private static void writeEntityTypes(Path output) throws Exception {
        var classes = new java.util.IdentityHashMap<net.minecraft.world.entity.EntityType<?>, Class<?>>();
        for (var field : net.minecraft.world.entity.EntityTypes.class.getFields()) {
            if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType generic
                    && generic.getRawType() == net.minecraft.world.entity.EntityType.class
                    && generic.getActualTypeArguments()[0] instanceof Class<?> entityClass)
                classes.put((net.minecraft.world.entity.EntityType<?>) field.get(null), entityClass);
        }
        var rows = new ArrayList<String>();
        rows.add("id\tkey\tcategory\twidth\theight\teyeHeight\tfixed\tfamilies\tpeaceful\tfeatures\tpassenger\tvehicle\tspawnScale\tupdateInterval");
        var spawnScale = net.minecraft.world.entity.EntityType.class.getDeclaredField("spawnDimensionsScale");
        spawnScale.setAccessible(true);
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            var entityClass = java.util.Objects.requireNonNull(classes.get(type), "Missing declared entity family " + type);
            var ancestry = new java.util.HashSet<String>();
            for (Class<?> parent = entityClass; parent != null; parent = parent.getSuperclass()) ancestry.add(parent.getSimpleName());
            int families = 0;
            String[] familyNames = {"LivingEntity", "Animal", "AgeableMob", "Projectile", "AbstractHorse", "AbstractBoat", "AbstractMinecart", "BlockAttachedEntity", "AbstractArrow", "AbstractChestedHorse"};
            for (int family = 0; family < familyNames.length; family++) if (ancestry.contains(familyNames[family])) families |= 1 << family;
            var dimensions = type.getDimensions();
            rows.add(String.join("\t", Integer.toString(BuiltInRegistries.ENTITY_TYPE.getId(type)), BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
                type.getCategory().getName(), Float.toString(dimensions.width()), Float.toString(dimensions.height()), Float.toString(dimensions.eyeHeight()),
                Boolean.toString(dimensions.fixed()), Integer.toString(families), Boolean.toString(type.isAllowedInPeaceful()),
                String.join(",", FeatureFlags.REGISTRY.toNames(type.requiredFeatures()).stream().map(Object::toString).sorted().toList()),
                attachments(dimensions, net.minecraft.world.entity.EntityAttachment.PASSENGER), attachments(dimensions, net.minecraft.world.entity.EntityAttachment.VEHICLE),
                Float.toString(spawnScale.getFloat(type)), Integer.toString(type.updateInterval())));
        }
        Files.writeString(output, String.join("\n", rows) + "\n");
    }

    private static String attachments(net.minecraft.world.entity.EntityDimensions dimensions, net.minecraft.world.entity.EntityAttachment attachment) {
        var points = new ArrayList<String>();
        for (int index = 0; ; index++) {
            var point = dimensions.attachments().getNullable(attachment, index, 0);
            if (point == null) return String.join(";", points);
            points.add(point.x + "," + point.y + "," + point.z);
        }
    }

    private static Facts evaluate(BlockState state, BlockGetter world, BlockPos pos) {
        if (world instanceof ProbeGetter probe) probe.queryState = state;
        int flags = (state.isAir() ? 1 : 0) | (state.liquid() ? 2 : 0)
            | (state.canBeReplaced() ? 4 : 0) | (state.isSolid() ? 8 : 0)
            | (state.isRedstoneConductor(world, pos) ? 32 : 0) | (state.isSignalSource() ? 64 : 0)
            | (state.requiresCorrectToolForDrops() ? 128 : 0) | (state.isSolidRender() ? 512 : 0)
            | (state.hasBlockEntity() ? 1024 : 0) | (state.isCollisionShapeFullBlock(world, pos) ? 2048 : 0)
            | (state.propagatesSkylightDown() ? 4096 : 0) | (state.hasLargeCollisionShape() ? 8192 : 0)
            | (state.isSuffocating(world, pos) ? 16384 : 0);
        int sturdy = 0;
        for (Direction face : Direction.values()) for (SupportType type : SupportType.values()) {
            if (state.isFaceSturdy(world, pos, face, type)) sturdy |= 1 << (face.ordinal() * 3 + type.ordinal());
        }
        if (state.canBeReplaced(net.minecraft.world.level.material.Fluids.WATER)) flags |= 16;
        if (state.canBeReplaced(net.minecraft.world.level.material.Fluids.WATER) != state.canBeReplaced(net.minecraft.world.level.material.Fluids.LAVA))
            throw new IllegalStateException("Fluid-specific replacement requires a binding: " + state);
        FluidState fluid = state.getFluidState();
        if (fluid.isSource()) flags |= 256;
        Vec3 offset = state.getOffset(pos);
        return new Facts(state.getDestroySpeed(world, pos), flags, BuiltInRegistries.FLUID.getKey(fluid.getType()).toString(),
            fluid.getAmount(), fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING),
            Block.getId(fluid.createLegacyBlock()), sturdy, 0,
            shape(state.getCollisionShape(world, pos, CollisionContext.empty())),
            shape(outlineUsesOffset(state.getBlock()) ? state.getShape(world, pos, CollisionContext.empty()).move(-offset.x, -offset.y, -offset.z)
                : state.getShape(world, pos, CollisionContext.empty())),
            shape(state.getBlockSupportShape(world, pos)), shape(state.getInteractionShape(world, pos)),
            state.getBlock().getFriction(), state.getBlock().getSpeedFactor(), state.getBlock().getJumpFactor(), state.getPistonPushReaction().name());
    }
    private static int shape(VoxelShape shape) {
        List<AABB> boxes = List.copyOf(shape.toAabbs());
        return SHAPE_IDS.computeIfAbsent(boxes, ignored -> SHAPE_IDS.size());
    }

    /** These pinned getShape implementations explicitly move by getOffset; having an offset function alone does not. */
    private static boolean outlineUsesOffset(Block block) {
        return block instanceof net.minecraft.world.level.block.FlowerBlock
            || block instanceof net.minecraft.world.level.block.BambooSaplingBlock
            || block instanceof net.minecraft.world.level.block.BambooStalkBlock
            || block instanceof net.minecraft.world.level.block.MangrovePropaguleBlock
            || block instanceof net.minecraft.world.level.block.SpeleothemBlock;
    }

    private static void writeBlock(DataOutputStream out, Block block, JsonObject report) throws Exception {
        String key = BuiltInRegistries.BLOCK.getKey(block).toString();
        var states = block.getStateDefinition().getPossibleStates();
        int first = Block.getId(states.getFirst());
        JsonObject entry = report.getAsJsonObject(key);
        if (entry == null || entry.getAsJsonArray("states").size() != states.size()) throw new IllegalStateException("Registry report mismatch " + key);
        Map<String, LinkedHashSet<String>> propertyValues = new LinkedHashMap<>();
        for (var property : block.getStateDefinition().getProperties()) propertyValues.put(property.getName(), new LinkedHashSet<>());
        int defaultId = -1;
        for (JsonElement element : entry.getAsJsonArray("states")) {
            JsonObject value = element.getAsJsonObject();
            int id = value.get("id").getAsInt();
            if (id < first || id >= first + states.size()) throw new IllegalStateException("Noncontiguous states " + key);
            if (value.has("default") && value.get("default").getAsBoolean()) defaultId = id;
            if (value.has("properties")) for (var property : value.getAsJsonObject("properties").entrySet()) {
                propertyValues.get(property.getKey()).add(property.getValue().getAsString());
            }
        }
        if (defaultId != Block.getId(block.defaultBlockState())) throw new IllegalStateException("Default report mismatch " + key);
        Map<String, Integer> strides = new LinkedHashMap<>();
        int stride = 1;
        var names = new ArrayList<>(propertyValues.keySet());
        for (int i = names.size() - 1; i >= 0; i--) { String name = names.get(i); strides.put(name, stride); stride *= propertyValues.get(name).size(); }
        // Verify every state against the independent vanilla blocks.json report.
        for (JsonElement element : entry.getAsJsonArray("states")) {
            JsonObject value = element.getAsJsonObject();
            int expected = first;
            for (String name : names) {
                var values = new ArrayList<>(propertyValues.get(name));
                expected += strides.get(name) * values.indexOf(value.getAsJsonObject("properties").get(name).getAsString());
            }
            if (expected != value.get("id").getAsInt()) throw new IllegalStateException("Property stride mismatch " + key);
        }
        out.writeUTF(key); out.writeUTF(block.getClass().getName()); out.writeInt(first); out.writeInt(states.size()); out.writeInt(defaultId);
        out.writeInt(names.size());
        for (String name : names) { out.writeUTF(name); out.writeInt(strides.get(name)); writeStrings(out, propertyValues.get(name)); }
        var bindings = bindings(block, Block.class);
        bindings.put("asItem", BuiltInRegistries.ITEM.getKey(block.asItem()).toString());
        bindings.put("explosionResistance", Float.toString(block.getExplosionResistance()));
        bindings.put("classHierarchy", hierarchy(block.getClass()));
        bindings.put("classInterfaces", interfaces(block.getClass()));
        var instrument = block.defaultBlockState().instrument();
        if (states.stream().anyMatch(state -> state.instrument() != instrument)) throw new IllegalStateException("Dynamic instrument for " + key);
        bindings.put("instrument", instrument.getSerializedName());
        bindings.put("instrument.worksAboveNoteBlock", Boolean.toString(instrument.worksAboveNoteBlock()));
        Block waxed = net.minecraft.world.item.HoneycombItem.WAXABLES.get().get(block);
        if (waxed != null) bindings.put("waxed", BuiltInRegistries.BLOCK.getKey(waxed).toString());
        for (var property : block.getStateDefinition().getProperties()) {
            bindings.put("property." + property.getName() + ".type", property.getClass().getName() + ":" + property.getValueClass().getName());
            bindings.put("property." + property.getName() + ".identity", Integer.toString(propertyIdentity(property)));
            for (var value : property.getPossibleValues()) {
                if (value instanceof Enum<?> enumeration) {
                    bindings.put("property." + property.getName() + ".ordinal." + ((net.minecraft.util.StringRepresentable)enumeration).getSerializedName(), Integer.toString(enumeration.ordinal()));
                }
            }
        }
        if (block instanceof net.minecraft.world.level.block.EntityBlock factory) {
            var access = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var prototype = factory.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
            bindings.put("blockEntity.type", prototype == null ? "none" : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(prototype.getType()).toString());
            if (prototype != null) {
                String defaultNbt = prototype.saveWithoutMetadata(access).toString();
                bindings.put("blockEntity.defaultNbt", defaultNbt);
                bindings.put("blockEntity.classHierarchy", hierarchy(prototype.getClass()));
                if (prototype instanceof net.minecraft.world.Container container) bindings.put("blockEntity.containerSize", Integer.toString(container.getContainerSize()));
                else if (prototype instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity campfire) bindings.put("blockEntity.containerSize", Integer.toString(campfire.getItems().size()));
                for (var state : states) {
                    var entity = factory.newBlockEntity(BlockPos.ZERO, state);
                    if (entity == null || entity.getType() != prototype.getType()) throw new IllegalStateException("State-dependent block entity type for " + state);
                    String nbt = entity.saveWithoutMetadata(access).toString();
                    if (!nbt.equals(defaultNbt)) bindings.put("blockEntity.stateNbt." + Block.getId(state), nbt);
                    var moved = factory.newBlockEntity(new BlockPos(1, 64, -3), state);
                    if (!moved.saveWithoutMetadata(access).toString().equals(nbt)) throw new IllegalStateException("Position-dependent constructor NBT requires a port for " + state);
                }
            }
        }
        if (block instanceof net.minecraft.world.level.block.CopperChestBlock chest) {
            bindings.put("CopperChestBlock.isWaxed", Boolean.toString(chest.isWaxed()));
            Block unwaxed = net.minecraft.world.item.HoneycombItem.WAX_OFF_BY_BLOCK.get().get(block);
            if (unwaxed != null) bindings.put("CopperChestBlock.unwaxed", BuiltInRegistries.BLOCK.getKey(unwaxed).toString());
        }
        if (block instanceof LiquidBlock) bindings.put("fluidBucket", BuiltInRegistries.ITEM.getKey(block.defaultBlockState().getFluidState().getType().getBucket()).toString());
        bindings.put("hasOffsetFunction", Boolean.toString(block.defaultBlockState().hasOffsetFunction()));
        bindings.put("outlineUsesOffset", Boolean.toString(outlineUsesOffset(block)));
        bindings.put("offsetType", !block.defaultBlockState().hasOffsetFunction() ? "NONE"
            : block.defaultBlockState().getOffset(new BlockPos(1, 64, -3)).y == 0.0 ? "XZ" : "XYZ");
        for (String method : List.of("getMaxHorizontalOffset", "getMaxVerticalOffset")) {
            Class<?> type = block.getClass();
            while (type != null) {
                try { var m = type.getDeclaredMethod(method); m.setAccessible(true); bindings.put(method, String.valueOf(m.invoke(block))); break; }
                catch (NoSuchMethodException missing) { type = type.getSuperclass(); }
            }
        }
        if (block instanceof net.minecraft.world.level.block.GrowingPlantBlock) {
            for (String method : List.of("getHeadBlock", "getBodyBlock")) {
                Class<?> type = block.getClass();
                while (type != null) {
                    try {
                        var m = type.getDeclaredMethod(method); m.setAccessible(true);
                        bindings.put(method, binding(m.invoke(block))); break;
                    } catch (NoSuchMethodException missing) { type = type.getSuperclass(); }
                }
            }
        }
        writeMap(out, bindings);
        writeStrings(out, FeatureFlags.REGISTRY.toNames(block.requiredFeatures()).stream().map(Object::toString).sorted().toList());
    }

    private static String hierarchy(Class<?> type) {
        var names = new ArrayList<String>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) names.add(current.getName());
        return String.join(",", names);
    }

    private static String interfaces(Class<?> type) {
        Set<String> names = new TreeSet<>();
        collectInterfaces(type, names);
        return String.join(",", names);
    }
    private static void collectInterfaces(Class<?> type, Set<String> names) {
        if (type == null) return;
        for (Class<?> contract : type.getInterfaces()) {
            names.add(contract.getName());
            collectInterfaces(contract, names);
        }
        collectInterfaces(type.getSuperclass(), names);
    }

    private static Map<String, String> bindings(Object object, Class<?> base) throws IllegalAccessException {
        Map<String, String> values = new TreeMap<>();
        for (Class<?> type = object.getClass(); type != null && type != base; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic()) continue;
                field.setAccessible(true);
                String value = binding(field.get(object));
                if (value != null) values.put(type.getSimpleName() + "." + field.getName(), value);
            }
        }
        return values;
    }
    private static String binding(Object value) {
        if (value == null) return null;
        if (value instanceof Block block) return BuiltInRegistries.BLOCK.getKey(block).toString();
        if (value instanceof BlockState state) return Integer.toString(Block.getId(state));
        if (value instanceof Item item) return BuiltInRegistries.ITEM.getKey(item).toString();
        if (value instanceof net.minecraft.world.entity.EntityType<?> type) return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        if (value instanceof Fluid fluid) return BuiltInRegistries.FLUID.getKey(fluid).toString();
        if (value instanceof net.minecraft.resources.ResourceKey<?> key) return key.identifier().toString();
        if (value instanceof net.minecraft.tags.TagKey<?> tag) return tag.location().toString();
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof net.minecraft.core.cauldron.CauldronInteraction.Dispatcher dispatcher)
            return net.minecraft.core.cauldron.CauldronInteractions.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, dispatcher).getOrThrow().getAsString();
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value.toString();
        if (value instanceof net.minecraft.world.level.block.state.properties.BlockSetType type) {
            return type.name() + ":canOpenByHand=" + type.canOpenByHand();
        }
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, String>();
            for (var entry : map.entrySet()) {
                String key = binding(entry.getKey()), member = binding(entry.getValue());
                if (key == null || member == null) return null;
                result.put(key, member);
            }
            return new com.google.gson.Gson().toJson(result);
        }
        return null;
    }

    private static Map<String, Set<String>> tags(Path jar) throws Exception {
        Map<String, List<String>> raw = new TreeMap<>();
        try (var zip = new ZipFile(jar.toFile())) {
            for (var entry : zip.stream().filter(e -> e.getName().startsWith("data/minecraft/tags/") && e.getName().endsWith(".json")).toList()) {
                String path = entry.getName().substring("data/minecraft/tags/".length());
                String kind = path.startsWith("worldgen/biome/") ? "worldgen/biome" : path.substring(0, path.indexOf('/'));
                if (!Set.of("block", "item", "fluid", "enchantment", "entity_type", "worldgen/biome").contains(kind)) continue;
                String name = kind + ":minecraft:" + path.substring(kind.length() + 1, path.length() - 5);
                JsonObject json = JsonParser.parseString(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                List<String> members = new ArrayList<>();
                for (JsonElement member : json.getAsJsonArray("values")) {
                    members.add(member.isJsonPrimitive() ? member.getAsString() : member.getAsJsonObject().get("id").getAsString());
                }
                raw.put(name, members);
            }
        }
        Map<String, Set<String>> resolved = new TreeMap<>();
        for (String name : raw.keySet()) resolveTag(name, raw, resolved, new HashMap<>());
        if (resolved.isEmpty()) throw new IllegalStateException("Vanilla jar contains no tags");
        return resolved;
    }
    private static Set<String> resolveTag(String name, Map<String, List<String>> raw, Map<String, Set<String>> resolved, Map<String, Boolean> visiting) {
        if (resolved.containsKey(name)) return resolved.get(name);
        if (visiting.put(name, true) != null) throw new IllegalStateException("Tag cycle " + name);
        List<String> members = raw.get(name);
        if (members == null) throw new IllegalStateException("Missing nested tag " + name);
        Set<String> result = new TreeSet<>();
        String kind = name.substring(0, name.indexOf(':'));
        for (String value : members) {
            if (value.startsWith("#")) result.addAll(resolveTag(kind + ":" + value.substring(1), raw, resolved, visiting));
            else result.add(value);
        }
        visiting.remove(name); resolved.put(name, Set.copyOf(result)); return result;
    }
    private static void writeStrings(DataOutputStream out, Iterable<String> strings) throws Exception {
        List<String> values = new ArrayList<>(); strings.forEach(values::add);
        out.writeInt(values.size()); for (String value : values) out.writeUTF(value);
    }
    private static void writeMap(DataOutputStream out, Map<String, String> values) throws Exception {
        out.writeInt(values.size()); for (var entry : new TreeMap<>(values).entrySet()) { out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue()); }
    }
    private static void writeText(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private record Facts(float destroy, int flags, String fluid, int amount, boolean falling, int fluidBlock,
                         int sturdy, int dynamic, int collision, int outline, int support, int interaction,
                         float friction, float speedFactor, float jumpFactor, String pushReaction) {
        Facts withDynamic(int value) { return new Facts(destroy, flags, fluid, amount, falling, fluidBlock, sturdy, value, collision, outline, support, interaction, friction, speedFactor, jumpFactor, pushReaction); }
        void write(DataOutputStream out) throws Exception {
            out.writeFloat(destroy); out.writeInt(flags); out.writeUTF(fluid); out.writeInt(amount); out.writeBoolean(falling);
            out.writeInt(fluidBlock); out.writeInt(sturdy); out.writeInt(dynamic);
            out.writeInt(collision); out.writeInt(outline); out.writeInt(support); out.writeInt(interaction);
            out.writeFloat(friction); out.writeFloat(speedFactor); out.writeFloat(jumpFactor); out.writeUTF(pushReaction);
        }
    }
    private static final class ProbeGetter implements BlockGetter {
        private final boolean solid, openShulkers;
        private BlockState queryState;
        ProbeGetter(boolean solid, boolean openShulkers) { this.solid = solid; this.openShulkers = openShulkers; }
        public BlockState getBlockState(BlockPos pos) { return (solid ? Blocks.STONE : Blocks.AIR).defaultBlockState(); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (!openShulkers || queryState == null || !(queryState.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock block)) return null;
            var entity = (net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity) block.newBlockEntity(pos, queryState);
            try {
                var status = entity.getClass().getDeclaredField("animationStatus");
                var progress = entity.getClass().getDeclaredField("progress");
                var previous = entity.getClass().getDeclaredField("progressOld");
                status.setAccessible(true); progress.setAccessible(true); previous.setAccessible(true);
                status.set(entity, net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity.AnimationStatus.OPENED);
                progress.setFloat(entity, 1.0F); previous.setFloat(entity, 1.0F);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Pinned shulker probe changed", e); }
            return entity;
        }
        public int getHeight() { return 384; }
        public int getMinY() { return -64; }
    }
}
