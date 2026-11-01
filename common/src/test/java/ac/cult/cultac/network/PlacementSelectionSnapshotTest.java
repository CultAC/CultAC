package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.scaffolding.AirLiquidPlace;
import ac.cult.cultac.checks.impl.scaffolding.FabricatedPlace;
import ac.cult.cultac.checks.impl.visibility.PlaceVisibility;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PlacementSelectionSnapshotTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);

    static Stream<ProtocolVersion> clients() {
        return Stream.of(ProtocolVersion.V1_21_11, ProtocolVersion.V26_3);
    }

    @ParameterizedTest
    @MethodSource("clients")
    void breakingAndPlacingUsesTheCachedSelectionButAppliesToTheChangedWorld(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.player.checkManager.getCheck(AirLiquidPlace.class).setEnabled(true);
            setup.block(TARGET, "short_grass");
            setup.block(TARGET.below(), "dirt");
            setup.breakTarget();
            assertEquals(state("air"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET));

            var frame = setup.fixture.id(ServerboundPackets.USE_ITEM_ON, "use_item_on");
            Wire.writeVarInt(frame, Hand.MAIN_HAND.ordinal());
            Wire.writeBlockPos(frame, TARGET);
            Wire.writeVarInt(frame, Direction.NORTH.ordinal());
            frame.writeFloat(.5F)
                    .writeFloat(.5F)
                    .writeFloat(0)
                    .writeBoolean(false)
                    .writeBoolean(false);
            Wire.writeVarInt(frame, 2);
            setup.fixture.forward(frame);

            assertEquals(0, setup.player.checkManager.getCheck(AirLiquidPlace.class).violations);
            assertEquals(state("stone"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET));
            assertEquals(state("air"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET.north()));
            assertEquals(2, setup.player.getInventory().getHeldItem().count());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aBrokenSupportIsInvalidAfterTheClientTickEnds(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            var check = setup.player.checkManager.getCheck(AirLiquidPlace.class);
            check.setEnabled(true);
            setup.block(TARGET, "short_grass");
            setup.breakTarget();
            setup.tickEnd();

            setup.player.checkManager.onBlockPlace(setup.place(Direction.NORTH, new Vec3(.5, .5, 0)));
            assertEquals(1, check.violations, "A previous tick's support must not remain valid through block history");
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void placeVisibilityUsesTheSelectedSlabHeightUntilTickEnd(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            var check = setup.player.checkManager.getCheck(PlaceVisibility.class);
            check.setEnabled(true);
            setup.player.y = 63;
            setup.player.packetStateData.clientSidePosition = new Vec3(.5, 63, -1.5);
            setup.block(TARGET, "oak_slab[type=bottom]");
            setup.predict(TARGET, "oak_slab[type=double]");
            assertFalse(setup.use(Direction.UP, new Vec3(.5, .5, .5)).isCancelled());
            assertEquals(
                    0, check.violations, "The cached top face was below the eye when the bottom slab was selected");
            assertEquals(state("oak_slab[type=double]"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET));

            setup.tickEnd();
            assertTrue(setup.use(Direction.UP, new Vec3(.5, .5, .5)).isCancelled());
            assertEquals(1, check.violations, "A new pick must use the double slab's higher top face");
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void fabricatedPlaceUsesTheSelectedMaterialForCursorBounds(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            var check = setup.player.checkManager.getCheck(FabricatedPlace.class);
            check.setEnabled(true);
            setup.block(TARGET, "lectern");
            setup.predict(TARGET, "air");

            setup.player.checkManager.onBlockPlace(setup.place(Direction.NORTH, new Vec3(.5, 1.2, 0)));
            assertEquals(0, check.violations);
            assertEquals(state("air"), setup.player.compensatedWorld.getBlockStateIdAt(TARGET));
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void placeVisibilityUsesTheOpenShulkerLidWithoutChangingIt(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            var player = setup.player;
            var world = player.compensatedWorld;
            var check = player.checkManager.getCheck(PlaceVisibility.class);
            check.setEnabled(true);
            player.y = 63.6;
            setup.block(TARGET, "shulker_box[facing=up]");
            world.applyPredictedBlockEntityData(
                    TARGET,
                    new ac.cult.blocksim.engine.BlockEntityData(
                            "minecraft:shulker_box",
                            new ac.cult.blocksim.data.Components(java.util.Map.of(
                                    "animation_status", new com.google.gson.JsonPrimitive("OPENED"),
                                    "progress", new com.google.gson.JsonPrimitive(1.0F))),
                            new ac.cult.blocksim.data.nbt.NbtValue.Compound(java.util.Map.of())));
            world.beginSelectionSnapshot();

            assertFalse(setup.use(Direction.NORTH, new Vec3(.5, 1.2, 0)).isCancelled());
            assertTrue(setup.use(Direction.UP, new Vec3(.5, 1.5, .5)).isCancelled());
            assertEquals(1, check.violations, "The eye is above the closed top, below the open lid");
            assertEquals(
                    1.0F,
                    world.getClientBlockEntityData(TARGET)
                            .data()
                            .get("progress")
                            .getAsFloat());
        }
    }

    private static int state(String name) {
        var registry = DataTables.defaults().registry();
        int bracket = name.indexOf('[');
        int result = registry.block("minecraft:" + (bracket < 0 ? name : name.substring(0, bracket)))
                .defaultState();
        if (bracket >= 0)
            for (String property :
                    name.substring(bracket + 1, name.length() - 1).split(",")) {
                String[] entry = property.split("=", 2);
                result = registry.with(result, entry[0], entry[1]);
            }
        return result;
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture;
        final CultPlayer player;

        Setup(ProtocolVersion version) throws Exception {
            fixture = version == ProtocolVersion.V26_3
                    ? new RecordReceiveFixture(version, false)
                    : RecordReceiveFixture.olderHost(version, false);
            services.useNetwork(fixture.manager);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            player.platformPlayer = mock(PlatformPlayer.class);
            var platformWorld = mock(PlatformWorld.class);
            when(player.platformPlayer.getWorld()).thenReturn(platformWorld);
            when(platformWorld.getName()).thenReturn("world");
            fixture.user.bind(player.platformPlayer);
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -1.5;
            player.packetStateData.clientSidePosition = new Vec3(.5, 64, -1.5);
            player.boundingBox = new SimpleCollisionBox(.2, 64, -1.8, .8, 65.8, -1.2);
            player.registryState = new ClientComponentRegistries();
            player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:stone", 3));
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            fixture.registrar.register(routes -> {
                var checks = new CheckManagerListener();
                checks.registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW);
                routes.registerReceiveListener(PacketListenerPriority.LOW, checks);
            });
        }

        void block(BlockPos pos, String name) {
            player.compensatedWorld.applyBlockChangeRawDANGER(pos.x(), pos.y(), pos.z(), state(name));
        }

        void breakTarget() {
            var frame = fixture.id(ServerboundPackets.PLAYER_ACTION, "player_action");
            Wire.writeVarInt(frame, PlayerAction.START_DESTROY_BLOCK.ordinal());
            Wire.writeBlockPos(frame, TARGET);
            frame.writeByte(Direction.NORTH.ordinal());
            Wire.writeVarInt(frame, 1);
            fixture.forward(frame);
        }

        void predict(BlockPos pos, String name) {
            var world = player.compensatedWorld;
            world.beginSelectionSnapshot();
            world.startPredicting();
            world.updateBlock(pos, state(name));
            world.stopPredicting(1);
        }

        PacketReceiveEvent<ServerboundUseItemOn> use(Direction face, Vec3 cursor) {
            var packet = new ServerboundUseItemOn(
                    Hand.MAIN_HAND, TARGET, face, new Vec3d(cursor.x, cursor.y, cursor.z), false, false, 2);
            var event = new PacketReceiveEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ServerboundPackets.USE_ITEM_ON, packet);
            player.checkManager.dispatchEarlyReceive(event);
            return event;
        }

        BlockPlace place(Direction face, Vec3 cursor) {
            var place = new BlockPlace(
                    player, Hand.MAIN_HAND, TARGET, face, player.getInventory().getHeldItem(), null);
            place.setCursor(cursor);
            return place;
        }

        void tickEnd() {
            fixture.forward(fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
        }

        @Override
        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                services.close();
            }
        }
    }
}
