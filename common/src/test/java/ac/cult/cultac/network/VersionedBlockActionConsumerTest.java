package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.SimAction;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.blocksim.interaction.InteractionKind;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

/** Tests the versioned actions through their real compensated world/inventory consumers. */
class VersionedBlockActionConsumerTest {
    private static final int[] PROTOCOLS = {768, 769, 770, 771, 772, 773, 774, 775, 776, 777};
    private static final List<Collisions.Axis> AXES = List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z);
    private static final ac.cult.blocksim.data.BlockRegistry REGISTRY =
            DataTables.defaults().registry();

    @Test
    void bedHeadCollisionStartsWhenTheClientActuallyPlacesTheHead() throws Exception {
        for (int protocol : PROTOCOLS)
            try (var setup = new Setup(protocol)) {
                setup.held("red_bed", 1);
                setup.apply(click(3, 63, 3), 0, 0);
                assertEquals(
                        protocol >= 776 ? "minecraft:red_bed" : "minecraft:air",
                        REGISTRY.block(setup.state(3, 64, 4)).key());
                assertEquals(
                        protocol >= 776 ? -.2 : -.7,
                        setup.collide(new SimpleCollisionBox(3.2, 64, 5.2, 3.8, 65.8, 5.8), 0, 0, -.7).z,
                        1e-12,
                        "Bed head collision: " + protocol);
            }
    }

    @Test
    void oldTntIgnitionRemovesTheObstacleAndConsumesTheCharge() throws Exception {
        for (int protocol : PROTOCOLS)
            try (var setup = new Setup(protocol)) {
                setup.block(3, 64, 3, state("tnt"));
                setup.held("fire_charge", 2);
                setup.apply(click(3, 64, 3), 0, 0);
                assertEquals(
                        protocol < 770 ? 1 : 2,
                        setup.fixture.player.getInventory().getHeldItem().count());
                assertEquals(
                        protocol < 770 ? -.7 : -.2,
                        setup.collide(new SimpleCollisionBox(3.2, 64.1, 4.2, 3.8, 65.9, 4.8), 0, 0, -.7).z,
                        1e-12,
                        "Ignited TNT collision: " + protocol);
            }
    }

    @Test
    void olderUseOnDoesNotInventRemaindersOrPreventTheNextPlacement() throws Exception {
        for (int protocol : PROTOCOLS) {
            try (var setup = new Setup(protocol)) {
                var held = OfflineCultTestBootstrap.item("minecraft:stone", 1);
                held.setComponent("minecraft:use_remainder", CanonicalSnbt.parse("{id:\"minecraft:bowl\",count:1}"));
                held.setComponent("minecraft:use_cooldown", CanonicalSnbt.parse("{seconds:5.0f}"));
                setup.fixture.player.getInventory().inventory.setHeldItem(held);
                setup.apply(click(3, 63, 3), 0, 0);
                assertEquals(
                        protocol == 777 ? "minecraft:bowl" : "minecraft:air",
                        setup.fixture.player.getInventory().getHeldItem().itemKey());
                assertEquals(
                        protocol == 777,
                        !setup.fixture
                                .player
                                .checkManager
                                .getCompensatedCooldown()
                                .blockSimulatorSnapshot()
                                .snapshot()
                                .isEmpty());
            }
            try (var setup = new Setup(protocol)) {
                var held = OfflineCultTestBootstrap.item("minecraft:stone", 2);
                held.setComponent("minecraft:use_cooldown", CanonicalSnbt.parse("{seconds:5.0f}"));
                setup.fixture.player.getInventory().inventory.setHeldItem(held);
                setup.apply(click(3, 63, 3), 0, 0);
                setup.apply(click(4, 63, 3), 0, 0);
                assertEquals(
                        protocol == 777 ? state("air") : state("stone"),
                        setup.state(4, 64, 3),
                        "Second placement: " + protocol);
                assertEquals(
                        protocol == 777 ? 1 : 0,
                        setup.fixture.player.getInventory().getHeldItem().count());
            }
        }
    }

    @Test
    void signPacketCameraUsesTheClientDirectionMathForCollision() throws Exception {
        for (int protocol : PROTOCOLS)
            try (var setup = new Setup(protocol)) {
                setup.block(4, 64, 3, state("stone"));
                setup.block(3, 64, 4, state("stone"));
                setup.held("oak_hanging_sign", 1);
                var player = setup.fixture.player;
                player.x = 5.5;
                player.y = 64;
                player.z = 1.5;
                var place = new BlockPlace(
                        player,
                        Hand.MAIN_HAND,
                        new BlockPos(3, 63, 3),
                        ac.cult.cultac.protocol.value.Direction.UP,
                        player.getInventory().getHeldItem(),
                        null);
                place.setCursor(new Vec3(.5, 1, .5));
                ClientBlockActions.useOn(player, place);
                assertEquals(
                        protocol < 774 ? "north" : "east",
                        REGISTRY.value(setup.state(3, 64, 3), "facing"),
                        "Sign orientation: " + protocol);
                assertEquals(
                        protocol < 774 ? .4 : .075,
                        setup.collide(new SimpleCollisionBox(2.7, 64.9, 3.01, 3.3, 66.7, 3.21), .4, 0, 0).x,
                        1e-12,
                        "Sign collision: " + protocol);
            }
    }

    @ParameterizedTest
    @CsvSource({"78.73174, 0.4386616, true", "-90, 0.4386616, false", "78.73174, 0.6, false"})
    void doorPlacementUsesTrackedYawOnlyWhenTheHorizontalCursorCannotResolveIt(
            float trackedYaw, float cursorX, boolean westDoorFits) throws Exception {
        try (var setup = new Setup(774)) {
            int water = state("water");
            setup.block(3, 64, 3, water);
            setup.block(3, 65, 3, water);
            var player = setup.fixture.player;
            // Use the captured fractional position in this fixture's loaded chunk.
            player.x = 3.4386616011393;
            player.z = 3.300000011920929;
            player.packetStateData.clientSidePosition = new Vec3(player.x, player.y, player.z);
            player.boundingBox =
                    new SimpleCollisionBox(player.x - .3, 64, player.z - .3, player.x + .3, 64.6, player.z + .3);
            player.xRot = trackedYaw;
            player.yRot = 89.84897F;
            player.isSneaking = true;
            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_OFFHAND, OfflineCultTestBootstrap.item("minecraft:cherry_door", 5));

            // The model packet carries the widened wire floats. Neither the tiny decoded X delta
            // nor the vertical component supplies an azimuth in the first two cases.
            var packet = new ServerboundUseItemOn(
                    Hand.OFF_HAND, new BlockPos(3, 63, 3), Direction.UP, new Vec3d(cursorX, 1, .3F), false, false, 1);
            player.compensatedWorld.advanceClientPredictionSequence();
            PlaceHandler.handleQueuedUseItemOn(player, packet);

            assertEquals(
                    westDoorFits ? 4 : 5,
                    player.getInventory().getHandItem(Hand.OFF_HAND).count());
            int lower = setup.state(3, 64, 3);
            int upper = setup.state(3, 65, 3);
            if (westDoorFits) {
                assertEquals("minecraft:cherry_door", REGISTRY.block(lower).key());
                assertEquals("minecraft:cherry_door", REGISTRY.block(upper).key());
                assertEquals("lower", REGISTRY.value(lower, "half"));
                assertEquals("upper", REGISTRY.value(upper, "half"));
                assertEquals("west", REGISTRY.value(lower, "facing"));
                assertEquals("west", REGISTRY.value(upper, "facing"));
                assertEquals(0, player.compensatedWorld.getWaterFluidLevelAt(3, 64, 3));
                assertEquals(0, player.compensatedWorld.getWaterFluidLevelAt(3, 65, 3));
            } else {
                assertEquals(water, lower);
                assertEquals(water, upper);
            }
        }
    }

    @Test
    void oldCustomTransformerDoesNotLowerTheGround() throws Exception {
        for (int protocol : PROTOCOLS)
            try (var setup = new Setup(protocol)) {
                setup.block(3, 63, 3, state("grass_block"));
                var held = OfflineCultTestBootstrap.item("minecraft:stick", 1);
                held.setComponent("minecraft:block_transformer", new NbtValue.Text("minecraft:shovel"));
                setup.fixture.player.getInventory().inventory.setHeldItem(held);
                var prediction = setup.apply(click(3, 63, 3), 0, 0);
                assertEquals(
                        protocol == 777 ? InteractionKind.SUCCESS : InteractionKind.PASS,
                        prediction.result().interaction().kind());
                assertEquals(
                        protocol == 777 ? -.0625 : 0,
                        setup.collide(new SimpleCollisionBox(3.2, 64, 3.2, 3.8, 65.8, 3.8), 0, -.1, 0).y,
                        1e-12,
                        "Ground after custom tool: " + protocol);
            }
    }

    @Test
    void sneakingBucketOnlyCreatesAdjacentFluidOnNewClients() throws Exception {
        for (int protocol : PROTOCOLS)
            try (var setup = new Setup(protocol)) {
                setup.block(3, 64, 3, state("oak_fence"));
                setup.held("water_bucket", 1);
                var player = setup.fixture.player;
                player.isSneaking = true;
                player.x = 3.5;
                player.y = 64;
                player.z = 1.5;
                setup.apply(new SimAction.Use(ac.cult.blocksim.interaction.Hand.MAIN_HAND), 0, 20);
                assertEquals(
                        protocol < 773 ? 0 : .8888888955116272,
                        player.compensatedWorld.getWaterFluidLevelAt(3, 64, 2),
                        1e-12,
                        "Adjacent bucket fluid: " + protocol);
                assertEquals(
                        "minecraft:bucket", player.getInventory().getHeldItem().itemKey());
            }
    }

    @Test
    void oldHarvestedBerryOutlineMovesTheNextBucketFluidTarget() throws Exception {
        for (int protocol : PROTOCOLS)
            try (var setup = new Setup(protocol)) {
                setup.block(3, 64, 3, REGISTRY.with(state("sweet_berry_bush"), "age", "3"));
                setup.held("air", 0);
                var player = setup.fixture.player;
                player.x = 3.03;
                player.y = 64;
                player.z = 1.5;
                setup.apply(
                        new SimAction.UseOn(
                                ac.cult.blocksim.interaction.Hand.MAIN_HAND,
                                new BlockHit(
                                        new ac.cult.blocksim.engine.BlockPos(3, 64, 3),
                                        ac.cult.blocksim.engine.Direction.NORTH,
                                        new ac.cult.blocksim.engine.Vec3(3.03, 64.75, 3),
                                        false)),
                        0,
                        30);
                assertEquals(protocol < 773 ? "1" : "3", REGISTRY.value(setup.state(3, 64, 3), "age"));
                setup.held("water_bucket", 1);
                setup.apply(new SimAction.Use(ac.cult.blocksim.interaction.Hand.MAIN_HAND), 0, 30);
                assertEquals(
                        .8888888955116272,
                        player.compensatedWorld.getWaterFluidLevelAt(3, 64, protocol < 773 ? 4 : 2),
                        1e-12,
                        "Water at client ray target: " + protocol);
                assertEquals(0, player.compensatedWorld.getWaterFluidLevelAt(3, 64, protocol < 773 ? 2 : 4), 1e-12);
            }
    }

    private static int state(String key) {
        return REGISTRY.block("minecraft:" + key).defaultState();
    }

    private static SimAction.UseOn click(int x, int y, int z) {
        return new SimAction.UseOn(
                ac.cult.blocksim.interaction.Hand.MAIN_HAND,
                new BlockHit(
                        new ac.cult.blocksim.engine.BlockPos(x, y, z),
                        ac.cult.blocksim.engine.Direction.UP,
                        new ac.cult.blocksim.engine.Vec3(x + .5, y + 1.0, z + .5),
                        false));
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
        final int protocol;

        Setup(int protocol) throws Exception {
            this.protocol = protocol;
            var player = fixture.player;
            var version = player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            version.set(player, ClientVersion.fromProtocolVersion(protocol));
            Mockito.when(fixture.connection.platform().getClientProtocol()).thenReturn(ProtocolVersion.of(protocol));
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -2;
            player.registryState = new ClientComponentRegistries();
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            var palettes = new int[world.getLastClientboundSectionCount()][64];
            int plains = ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults()
                    .id("minecraft:worldgen/biome", "minecraft:plains");
            for (var palette : palettes) Arrays.fill(palette, plains);
            world.applyClientBiomes("minecraft:overworld", 0, 0, palettes);
            world.applyClientBiomes("minecraft:overworld", 0, -1, palettes);
            for (int x = 0; x < 7; x++)
                for (int z = 0; z < 7; z++) block(x, 63, z, VersionedBlockActionConsumerTest.state("stone"));
        }

        void held(String key, int count) {
            fixture.player
                    .getInventory()
                    .inventory
                    .setHeldItem(OfflineCultTestBootstrap.item("minecraft:" + key, count));
        }

        void block(int x, int y, int z, int state) {
            fixture.player.compensatedWorld.applyBlockChangeRawDANGER(x, y, z, state);
        }

        int state(int x, int y, int z) {
            return fixture.player.compensatedWorld.getBlockStateIdAt(x, y, z);
        }

        ClientBlockActions.Prediction apply(SimAction action, float yaw, float pitch) {
            var prediction = ClientBlockActions.predict(fixture.player, action, yaw, pitch);
            assertNull(prediction.result().decline(), "Protocol " + protocol);
            assertEquals(protocol, prediction.input().clientVersion().protocol());
            ClientBlockActions.apply(fixture.player, prediction, null);
            return prediction;
        }

        Vec3 collide(SimpleCollisionBox box, double dx, double dy, double dz) {
            return Collisions.collide(fixture.player, box, dx, dy, dz, AXES, false);
        }

        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                services.close();
            }
        }
    }
}
