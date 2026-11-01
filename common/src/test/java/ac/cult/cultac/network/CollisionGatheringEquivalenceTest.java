package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.ReferenceCollisionGathering;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBoxPool;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The optimized collider gathering against the verbatim 515d08404 copy, on randomized worlds. */
class CollisionGatheringEquivalenceTest {
    private static final List<ClientVersion> VERSIONS = List.of(
            ClientVersion.V_1_21_2,
            ClientVersion.V_1_21_4,
            ClientVersion.V_1_21_5,
            ClientVersion.V_1_21_11,
            ClientVersion.V_26_2,
            ClientVersion.V_26_3);
    private static final List<List<Collisions.Axis>> ORDERS = List.of(
            List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z),
            List.of(Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X),
            List.of(Collisions.Axis.X, Collisions.Axis.Y, Collisions.Axis.Z));

    @Test
    void gatheredCollidersAndTheirClippingMatchTheReferenceBitForBit() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            CollisionWorlds.populate(player, 0x5eedL);
            Field version = player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            var random = new SplittableRandom(0xC011DEL);
            int compared = 0;
            int nonEmpty = 0;
            for (int iteration = 0; iteration < 6000; iteration++) {
                version.set(player, VERSIONS.get(random.nextInt(VERSIONS.size())));
                int platform = random.nextInt(12);
                player.movementPlatform =
                        platform == 0 || platform == 2 ? MovementPlatform.BEDROCK : MovementPlatform.JAVA;
                player.bedrockState = platform <= 1 ? new BedrockPlayerState(new UUID(0, 1)) : null;
                CollisionWorlds.randomBorder(player, random);
                SimpleCollisionBox query = CollisionWorlds.randomQuery(random, player);
                player.lastX = (query.minX + query.maxX) / 2;
                player.lastZ = (query.minZ + query.maxZ) / 2;
                double entityBottom = switch (random.nextInt(3)) {
                    case 0 -> query.minY;
                    case 1 -> Double.NaN;
                    default -> query.minY + random.nextDouble(-1.5, 1.5);
                };
                JavaCollisionState actor = CollisionWorlds.randomActor(random);

                var expected = new ArrayList<SimpleCollisionBox>();
                var actual = new ArrayList<SimpleCollisionBox>();
                String expectedOutcome = outcome(() -> ReferenceCollisionGathering.getCollisionBoxes(
                        player, query.copy(), expected, false, entityBottom, actor));
                String actualOutcome = outcome(
                        () -> Collisions.getCollisionBoxes(player, query.copy(), actual, false, entityBottom, actor));
                String context = iteration + " " + query + " " + player.getClientVersion() + " " + platform;
                assertEquals(expectedOutcome, actualOutcome, context);
                assertEquals(describe(expected, query), describe(actual, query), context);
                long clipSeed = random.nextLong();
                assertEquals(
                        clip(expected, query, new SplittableRandom(clipSeed)),
                        clip(actual, query, new SplittableRandom(clipSeed)),
                        context);
                var pool = CollisionBoxPool.current();
                int mark = pool.mark();
                try {
                    var pooled = new ArrayList<SimpleCollisionBox>();
                    assertEquals(
                            expectedOutcome,
                            outcome(() -> Collisions.getCollisionBoxes(
                                    player, query.copy(), pooled, false, entityBottom, actor, pool)),
                            context);
                    assertEquals(describe(expected, query), describe(pooled, query), context);
                    assertEquals(
                            clip(expected, query, new SplittableRandom(clipSeed)),
                            clip(pooled, query, new SplittableRandom(clipSeed)),
                            context);
                } finally {
                    pool.release(mark);
                }
                assertEquals(
                        outcome(() -> ReferenceCollisionGathering.getCollisionBoxes(
                                player, query.copy(), null, true, entityBottom, actor)),
                        outcome(() ->
                                Collisions.getCollisionBoxes(player, query.copy(), null, true, entityBottom, actor)),
                        context);
                compared++;
                if (!expected.isEmpty()) nonEmpty++;
            }
            assertEquals(6000, compared);
            assertTrue(nonEmpty > 1500, "too few queries reached colliders: " + nonEmpty);
        }
    }

    @Test
    void stepComparisonMatchesSeparateSteppingAndNonSteppingCalls() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            CollisionWorlds.populate(player, 0x5eedL);
            Field version = player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            player.movementPlatform = MovementPlatform.JAVA;
            player.bedrockState = null;
            var random = new SplittableRandom(0x57E9L);
            int stepped = 0;
            for (int iteration = 0; iteration < 6000; iteration++) {
                version.set(player, VERSIONS.get(random.nextInt(VERSIONS.size())));
                double x = random.nextInt(-30, 30) + random.nextDouble();
                double z = random.nextInt(-30, 30) + random.nextDouble();
                double y = random.nextBoolean() ? random.nextInt(44, 80) : random.nextInt(44, 80) + random.nextDouble();
                var box = new SimpleCollisionBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3, false);
                double dx = random.nextInt(4) == 0 ? 0.0 : random.nextDouble(-0.4, 0.4);
                double dy = random.nextInt(4) == 0 ? 0.0 : random.nextDouble(-0.5, 0.45);
                double dz = random.nextInt(4) == 0 ? 0.0 : random.nextDouble(-0.4, 0.4);
                var order = ORDERS.get(random.nextInt(ORDERS.size()));
                boolean onGround = random.nextBoolean();
                String context = iteration + " " + box + " " + dx + " " + dy + " " + dz + " " + onGround;

                var comparison = Collisions.collideWithStepComparison(
                        player, box.copy(), dx, dy, dz, order, List.of(), onGround);
                Vec3 expectedStepped = Collisions.collideWithAdditionalCollisionBoxes(
                        player, box.copy(), dx, dy, dz, order, true, List.of(), onGround);
                Vec3 expectedWithoutStep = Collisions.collideWithAdditionalCollisionBoxes(
                        player, box.copy(), dx, dy, dz, order, false, List.of(), onGround);
                assertEquals(bits(expectedStepped), bits(comparison.movement()), context);
                assertEquals(bits(expectedWithoutStep), bits(comparison.withoutStep()), context);
                assertEquals(0, CollisionBoxPool.current().mark(), context);
                if (!bits(expectedStepped).equals(bits(expectedWithoutStep))) stepped++;
            }
            assertTrue(stepped > 100, "too few queries stepped: " + stepped);
        }
    }

    @Test
    void intersectingNativeCollidersMatchTheirDownCastBoxes() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            CollisionWorlds.populate(player, 0x5eedL);
            var random = new SplittableRandom(0xA070L);
            int added = 0;
            int rejected = 0;
            for (int iteration = 0; iteration < 3000; iteration++) {
                int platform = random.nextInt(12);
                player.movementPlatform =
                        platform == 0 || platform == 2 ? MovementPlatform.BEDROCK : MovementPlatform.JAVA;
                player.bedrockState = platform <= 1 ? new BedrockPlayerState(new UUID(0, 1)) : null;
                SimpleCollisionBox query = CollisionWorlds.randomQuery(random, player);
                double entityBottom = random.nextBoolean() ? query.minY : Double.NaN;
                JavaCollisionState actor = CollisionWorlds.randomActor(random);
                var expected = new ArrayList<SimpleCollisionBox>();
                var actual = new ArrayList<SimpleCollisionBox>();
                var scratch = new ArrayList<SimpleCollisionBox>();
                for (int x = (int) Math.floor(query.minX) - 1; x <= (int) Math.floor(query.maxX) + 1; x++)
                    for (int y = (int) Math.floor(query.minY) - 1; y <= (int) Math.floor(query.maxY) + 1; y++)
                        for (int z = (int) Math.floor(query.minZ) - 1; z <= (int) Math.floor(query.maxZ) + 1; z++) {
                            int state = player.compensatedWorld.getBlockStateIdAt(x, y, z);
                            var boxes = new ArrayList<SimpleCollisionBox>();
                            NativeBlockCollisionHelper.getCollisionBox(player, state, x, y, z, entityBottom, actor)
                                    .downCast(boxes);
                            int before = expected.size();
                            if (boxes.stream().anyMatch(query::isIntersected)) expected.addAll(boxes);
                            NativeBlockCollisionHelper.addIfAnyIntersects(
                                    player, state, x, y, z, entityBottom, actor, query, scratch, actual);
                            if (expected.size() > before) added++;
                            else if (!boxes.isEmpty()) rejected++;
                        }
                String context = iteration + " " + query + " " + platform;
                assertEquals(describe(expected, query), describe(actual, query), context);
            }
            assertTrue(added > 3000 && rejected > 3000, "too few colliders: " + added + " " + rejected);
        }
    }

    private static String bits(Vec3 vector) {
        return bits(vector.x) + bits(vector.y) + bits(vector.z);
    }

    private interface Gather {
        boolean run();
    }

    private static String outcome(Gather gather) {
        try {
            return "returned " + gather.run();
        } catch (RuntimeException exception) {
            return "threw " + exception.getClass().getName() + ": " + exception.getMessage();
        }
    }

    static String describe(List<SimpleCollisionBox> boxes, SimpleCollisionBox query) {
        var out = new StringBuilder();
        for (SimpleCollisionBox box : boxes) {
            out.append(box.getClass().getSimpleName())
                    .append(' ')
                    .append(bits(box.minX))
                    .append(',')
                    .append(bits(box.minY))
                    .append(',')
                    .append(bits(box.minZ))
                    .append(',')
                    .append(bits(box.maxX))
                    .append(',')
                    .append(bits(box.maxY))
                    .append(',')
                    .append(bits(box.maxZ))
                    .append(' ')
                    .append(box.isFullBlock());
            if (box instanceof VoxelShapeCollisionBox voxel) {
                out.append(" strict=")
                        .append(voxel.usesStrictFullBlockIntersection())
                        .append(" steps=");
                for (int i = 0; i < voxel.stepCoordinateCount(); i++)
                    out.append(bits(voxel.stepCoordinateAt(i))).append(';');
            }
            out.append(" collided=").append(box.isCollided(query));
            out.append(" intersected=").append(box.isIntersected(query)).append('\n');
        }
        return out.toString();
    }

    /** Axis clipping through each list, the way the movement engine consumes it. */
    static String clip(List<SimpleCollisionBox> boxes, SimpleCollisionBox query, SplittableRandom random) {
        var out = new StringBuilder();
        for (int probe = 0; probe < 4; probe++) {
            double width = random.nextDouble(0.2, 0.9);
            double height = random.nextDouble(0.2, 1.9);
            double x = random.nextDouble(query.minX, Math.max(query.minX + 1.0E-9, query.maxX));
            double y = random.nextDouble(query.minY, Math.max(query.minY + 1.0E-9, query.maxY));
            double z = random.nextDouble(query.minZ, Math.max(query.minZ + 1.0E-9, query.maxZ));
            var moving = new SimpleCollisionBox(x, y, z, x + width, y + height, z + width, false);
            var movement =
                    new Vec3(random.nextDouble(-1.2, 1.2), random.nextDouble(-1.2, 1.2), random.nextDouble(-1.2, 1.2));
            Vec3 result = Collisions.collideBoundingBoxLegacy(
                    movement, moving, boxes, ORDERS.get(random.nextInt(ORDERS.size())));
            out.append(bits(result.x))
                    .append(',')
                    .append(bits(result.y))
                    .append(',')
                    .append(bits(result.z))
                    .append(';');
            for (Collisions.Axis axis : Collisions.Axis.values())
                out.append(bits(Collisions.collideAxis(
                                random.nextDouble(-0.6, 0.6),
                                moving,
                                boxes,
                                axis,
                                SimpleCollisionBox.AxisEpsilon.JAVA)))
                        .append(';');
        }
        return out.toString();
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    /** Deterministic compensated worlds and queries shared by the equivalence and CPU tests. */
    static final class CollisionWorlds {
        private static final List<String> PALETTE = List.of(
                "minecraft:stone",
                "minecraft:dirt",
                "minecraft:oak_slab",
                "minecraft:stone_brick_stairs",
                "minecraft:oak_stairs",
                "minecraft:oak_fence",
                "minecraft:nether_brick_fence",
                "minecraft:cobblestone_wall",
                "minecraft:glass_pane",
                "minecraft:iron_bars",
                "minecraft:scaffolding",
                "minecraft:powder_snow",
                "minecraft:honey_block",
                "minecraft:soul_sand",
                "minecraft:white_carpet",
                "minecraft:snow",
                "minecraft:chest",
                "minecraft:piston_head",
                "minecraft:moving_piston",
                "minecraft:red_bed",
                "minecraft:cauldron",
                "minecraft:hopper",
                "minecraft:lantern",
                "minecraft:bamboo",
                "minecraft:pointed_dripstone",
                "minecraft:cobweb",
                "minecraft:water",
                "minecraft:ladder",
                "minecraft:oak_trapdoor",
                "minecraft:oak_door",
                "minecraft:end_portal_frame",
                "minecraft:anvil",
                "minecraft:lectern",
                "minecraft:bell",
                "minecraft:chorus_plant",
                "minecraft:big_dripleaf",
                "minecraft:shulker_box",
                "minecraft:pitcher_crop",
                "minecraft:pale_moss_carpet",
                "minecraft:dragon_head",
                "minecraft:farmland",
                "minecraft:dirt_path",
                "minecraft:short_grass",
                "minecraft:oak_leaves",
                "minecraft:mud",
                "minecraft:grindstone",
                "minecraft:composter",
                "minecraft:brewing_stand",
                "minecraft:sculk_sensor",
                "minecraft:candle",
                "minecraft:sea_pickle",
                "minecraft:lily_pad",
                "minecraft:cactus",
                "minecraft:daylight_detector");
        /** Chunk -1,0 stays unloaded so queries cross into unknown terrain. */
        static final int MIN_CHUNK = -2, MAX_CHUNK = 1;

        static void populate(CultPlayer player, long seed) {
            var registry = DataTables.defaults().registry();
            var blocks = new ArrayList<Integer>();
            for (String key : PALETTE) {
                try {
                    var block = registry.block(key);
                    blocks.add(block.firstState());
                    blocks.add(block.stateCount());
                } catch (IllegalArgumentException missing) {
                    // Absent from this data version.
                }
            }
            var world = player.compensatedWorld;
            for (int cx = MIN_CHUNK; cx <= MAX_CHUNK; cx++)
                for (int cz = MIN_CHUNK; cz <= MAX_CHUNK; cz++)
                    if (cx != -1 || cz != 0) world.ensureValidationChunkLoaded(cx, cz);
            var random = new SplittableRandom(seed);
            int min = world.getMinHeight(), max = world.getMaxHeight();
            int[][] layers = {{min, min + 20}, {44, 84}, {max - 20, max - 1}};
            for (int[] layer : layers) {
                for (int y = layer[0]; y <= layer[1]; y++) {
                    // Sections 80..95 are left absent; 96..111 is touched and emptied again.
                    if (y >= 80 && y < 96) continue;
                    double density = y < 60 ? 0.55 : y < 70 ? 0.3 : 0.12;
                    for (int x = MIN_CHUNK * 16; x < (MAX_CHUNK + 1) * 16; x++)
                        for (int z = MIN_CHUNK * 16; z < (MAX_CHUNK + 1) * 16; z++) {
                            if (random.nextDouble() >= density) continue;
                            world.updateBlock(new BlockPos(x, y, z), randomState(random, registry, blocks));
                        }
                }
            }
            world.updateBlock(
                    new BlockPos(3, 100, 3), registry.block("minecraft:stone").defaultState());
            world.updateBlock(new BlockPos(3, 100, 3), 0);
        }

        private static int randomState(SplittableRandom random, BlockRegistry registry, List<Integer> blocks) {
            if (random.nextInt(12) == 0) return random.nextInt(registry.stateCount());
            if (random.nextInt(3) == 0) return registry.block("minecraft:stone").defaultState();
            int index = random.nextInt(blocks.size() / 2) * 2;
            return blocks.get(index) + random.nextInt(blocks.get(index + 1));
        }

        static SimpleCollisionBox randomQuery(SplittableRandom random, CultPlayer player) {
            var world = player.compensatedWorld;
            double x = random.nextDouble(MIN_CHUNK * 16 - 3, (MAX_CHUNK + 1) * 16 + 3);
            double z = random.nextDouble(MIN_CHUNK * 16 - 3, (MAX_CHUNK + 1) * 16 + 3);
            double y = switch (random.nextInt(6)) {
                case 0 -> random.nextDouble(world.getMinHeight() - 3, world.getMinHeight() + 22);
                case 1 -> random.nextDouble(world.getMaxHeight() - 22, world.getMaxHeight() + 3);
                case 2 -> random.nextDouble(78, 112);
                default -> random.nextDouble(42, 86);
            };
            switch (random.nextInt(5)) {
                case 0 -> x = Math.rint(x) + edge(random);
                case 1 -> y = Math.rint(y) + edge(random);
                case 2 -> z = Math.rint(z) + edge(random);
                case 3 -> x = Math.rint(x / 16) * 16 + edge(random);
                default -> {}
            }
            double width = random.nextInt(8) == 0 ? 0 : random.nextDouble(0.05, 2.4);
            double height = random.nextInt(8) == 0 ? 0 : random.nextDouble(0.05, 3.2);
            double depth = random.nextInt(4) == 0 ? width : random.nextDouble(0.05, 2.4);
            var box = new SimpleCollisionBox(x, y, z, x + width, y + height, z + depth, false);
            if (random.nextBoolean())
                box.expandToCoordinate(
                        random.nextDouble(-1.5, 1.5), random.nextDouble(-1.5, 1.5), random.nextDouble(-1.5, 1.5));
            return box;
        }

        private static double edge(SplittableRandom random) {
            return switch (random.nextInt(7)) {
                case 0 -> 0;
                case 1 -> 1.0E-7;
                case 2 -> -1.0E-7;
                case 3 -> 5.0E-8;
                case 4 -> -5.0E-8;
                case 5 -> 1.0E-5;
                default -> -(double) 1.0E-5F;
            };
        }

        static JavaCollisionState randomActor(SplittableRandom random) {
            if (random.nextInt(4) == 0) return null;
            return new JavaCollisionState(
                    random.nextBoolean() ? 0 : random.nextDouble(0, 5),
                    random.nextBoolean(),
                    random.nextBoolean(),
                    random.nextInt(6) == 0);
        }

        static void randomBorder(CultPlayer player, SplittableRandom random) throws ReflectiveOperationException {
            var border = player.checkManager.getListener(PacketWorldBorder.class);
            double diameter = random.nextInt(4) == 0 ? random.nextDouble(8, 60) : 59_999_968;
            set(border, "centerX", random.nextDouble(-20, 20));
            set(border, "centerZ", random.nextDouble(-20, 20));
            set(border, "oldDiameter", diameter);
            set(border, "newDiameter", diameter);
        }

        private static void set(PacketWorldBorder border, String name, double value)
                throws ReflectiveOperationException {
            Field field = PacketWorldBorder.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setDouble(border, value);
        }
    }
}
