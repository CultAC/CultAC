package ac.cult.cultac.checks.impl.prediction.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerSteer;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.api.config.ConfigManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Entity#collide clips a scaled player at its scaled box. A recorded player at scale 2 sprint-jumps beside a
 * row of blocks to the north, and the jump boost carries its 1.2-wide box into them on Z.
 */
class ScaledPlayerCollisionTest {
    private static final int FLOOR_Y = 62;
    private static final CheckManagerListener LISTENER = new CheckManagerListener();
    private static final PacketPlayerSteer STEER = new PacketPlayerSteer();
    private static final ServerboundPlayerInput FORWARD_LEFT_JUMP_SPRINT =
            new ServerboundPlayerInput(true, false, true, false, true, false, true);
    /** The blocks on the floor beside the player, as the recording's world has them. */
    private static final int[][] ROW = {
        {19002, -37}, {19003, -37}, {19004, -37}, {19005, -37}, {19006, -37}, {19007, -37},
        {19004, -36}, {19005, -36}, {19006, -36}, {19007, -36}, {19006, -35}, {19007, -35}
    };

    private static Field dataStore;
    private static Object previousDataStore;

    private final List<CultPlayer> players = new ArrayList<>();

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        OfflineCultTestBootstrap.installConfig();
        Class.forName(VerboseCodecs.class.getName());
        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        Mockito.when(config.getLongElse(Mockito.anyString(), Mockito.anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        dataStore = CultAPI.class.getDeclaredField("dataStoreLifecycle");
        dataStore.setAccessible(true);
        previousDataStore = dataStore.get(CultAPI.INSTANCE);
        dataStore.set(
                CultAPI.INSTANCE,
                new DataStoreLifecycle(CultAPI.INSTANCE.getGrimPlugin(), CultAPI.INSTANCE.getBackendRegistry()));
    }

    @AfterAll
    static void restore() throws IllegalAccessException {
        dataStore.set(CultAPI.INSTANCE, previousDataStore);
    }

    @AfterEach
    void close() {
        for (CultPlayer player : players) {
            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            player.onRemove();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aScaledSprintJumpIntoBlocksCollidesAtTheScaledWidth() {
        // An expand ability: minecraft:scale +1.0 multiplied base, movement speed 0.1 * (1 - 0.2).
        CultPlayer player = player(2.0F, 0.1F * 0.8F);

        assertExact(tick(
                player,
                FORWARD_LEFT_JUMP_SPRINT,
                new Vec3(19004.316450183233, 63.0, -34.144304945715994),
                520.6213F,
                50.619778F,
                true));
        // Z moves first as |z| > |x|; the box's north face stops at the block face z = -35, 0.6 from the centre.
        PredictionResult blocked = tick(
                player,
                null,
                new Vec3(19004.0574273916, 63.41999998688698, -34.39999997615814),
                522.4961F,
                50.3948F,
                false);
        assertExact(blocked);
        // The collision is hard, so LocalPlayer#aiStep stops the sprint and sends STOP_SPRINTING before the next
        // position; that tick moves by the walking Player#getFlyingSpeed.
        assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(blocked));
        stopSprinting(player);
        assertExact(tick(
                player,
                null,
                new Vec3(19003.898444514165, 63.7531999805212, -34.39999997615814),
                523.621F,
                49.569874F,
                false));
    }

    @Test
    void aScaledPlayerFallingPastABlockCornerCollidesOnZFirst() {
        // A recorded tick: no input, Z first; the 1.2-wide box reaches x = 0.86 and meets the stone at
        // (0, 62, 12968), stopping at its face z = 12969.
        CultPlayer player = scaled(0, 12968, 2.0F, 0.1F * 0.8F, false);
        place(player, BlockIds.STONE, new int[][] {
            {-2, 62, 12967},
            {-1, 62, 12967},
            {0, 62, 12967},
            {-2, 62, 12968},
            {-1, 62, 12968},
            {0, 62, 12968},
            {-2, 62, 12969},
            {-1, 62, 12969},
            {-2, 62, 12970}
        });
        at(player, new Vec3(1.4589175410952029, 62.79664485067641, 12969.635017766419), 41.69917F, 51.600044F);
        player.checkManager
                .getSimulationProcessor()
                .seedStartingVelocities(List.of(
                        new Vec3(0.2267261592097706, -0.22768847875803683, -0.48242078426612545),
                        new Vec3(0.23859088456621302, -0.22768847704070774, -0.49573811127088385)));

        assertExact(tick(
                player,
                new ServerboundPlayerInput(false, false, false, false, false, false, false),
                new Vec3(1.6856437003049733, 62.56895637016599, 12969.600000023842),
                38.699165F,
                54.75005F,
                false));
    }

    @Test
    void aScaledPlayerLandsOnABlockOnlyItsScaledBoxCovers() {
        // A recorded tick: sprinting forward through the air, the box's corner at z = 13038.84 lands on
        // the plank at (10992, 62, 13038); a 0.6-wide box at z = 13039.14 would fall past it.
        CultPlayer player = scaled(10992, 13038, 2.0F, 0.1F * 0.8F, true);
        place(player, DataTables.defaults().registry().block("minecraft:oak_planks"), new int[][] {{10992, 62, 13038}});
        at(player, new Vec3(10992.994701523343, 63.024424088213685, 13039.439181260495), 299.70013F, 79.34997F);
        player.checkManager
                .getSimulationProcessor()
                .seedStartingVelocities(List.of(
                        new Vec3(0.21937383764403842, -0.22768848407528247, 0.13739878003520176),
                        new Vec3(0.23951541533142617, -0.22768848579261142, 0.14888549058658465)));

        assertExact(tick(
                player,
                new ServerboundPlayerInput(true, false, false, false, false, false, true),
                new Vec3(10993.256513241038, 63.0, 13039.600399848214),
                298.95013F,
                78.74997F,
                true));
    }

    @Test
    void aShrinkBoundBeforeAMoveKeepsTheOldBoxUntilTickEnd() {
        // A recorded tick: a shrink ability (scale 1 -> 0.5) is bound before the move, but the client
        // still moves its 1.8 box into the ceiling at y 70 and stops at 70 - 1.8F.
        CultPlayer player = scaled(320, -40, 1.0F, 0.1F, true);
        place(player, BlockIds.STONE, new int[][] {{319, 70, -41}, {320, 70, -41}, {319, 70, -40}, {320, 70, -40}});
        at(player, new Vec3(319.8336069250828, 68.16610926093821, -39.95844469413747), 914.4889F, 17.686575F);
        player.refreshPlayerPose();
        player.checkManager
                .getSimulationProcessor()
                .seedStartingVelocity(new Vec3(
                        0.0285115270676215 * 0.91F, (0.1647732818260721 - 0.08) * 0.98F, -0.241003951341888 * 0.91F));
        player.compensatedEntities.updateAttributes(
                player.entityID,
                List.of(
                        new AttributeSnapshot(
                                "minecraft:movement_speed",
                                0.10000000149011612,
                                List.of(
                                        new AttributeModifier(
                                                "minecraft:sprinting",
                                                0.30000001192092896,
                                                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
                                        new AttributeModifier(
                                                "test:shrink", 0.25, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))),
                        new AttributeSnapshot(
                                "minecraft:scale",
                                1.0,
                                List.of(new AttributeModifier(
                                        "test:shrink", -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)))));

        assertExact(tick(
                player,
                new ServerboundPlayerInput(true, false, false, false, true, false, true),
                new Vec3(319.865805141874, 68.20000004768372, -40.2024591827081),
                914.20703F,
                17.444994F,
                false));
    }

    /** A player at {@code scale} in the air over empty chunks around ({@code x}, {@code z}), floored at y 61. */
    private CultPlayer scaled(int x, int z, float scale, float movementSpeed, boolean sprinting) {
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Scaled_Fall"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        players.add(player);
        player.gamemode = GameMode.SURVIVAL;
        CompensatedWorld world = player.compensatedWorld;
        for (int chunkX = (x >> 4) - 1; chunkX <= (x >> 4) + 1; chunkX++) {
            for (int chunkZ = (z >> 4) - 1; chunkZ <= (z >> 4) + 1; chunkZ++) {
                world.putChunk(
                        chunkX, chunkZ, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            }
        }
        for (int blockX = x - 4; blockX <= x + 4; blockX++) {
            for (int blockZ = z - 4; blockZ <= z + 4; blockZ++) {
                world.applyBlockChangeRawDANGER(blockX, 61, blockZ, BlockIds.STONE.defaultState());
            }
        }
        // onAttributeSnapshot refreshes every cached scalar, scale included, so it precedes the scale override.
        player.compensatedEntities
                .getSelf()
                .onAttributeSnapshot(new AttributeSnapshot("minecraft:movement_speed", movementSpeed, List.of()));
        player.compensatedEntities.getSelf().scale = scale;
        player.dimensionScale = scale;
        player.isSprinting = sprinting;
        player.sprintState.onClientTick(sprinting);
        return player;
    }

    private static void place(CultPlayer player, BlockDefinition block, int[][] positions) {
        for (int[] position : positions) {
            player.compensatedWorld.applyBlockChangeRawDANGER(
                    position[0], position[1], position[2], block.defaultState());
        }
    }

    private static void at(CultPlayer player, Vec3 position, float yaw, float pitch) {
        player.x = player.lastX = position.x;
        player.y = player.lastY = position.y;
        player.z = player.lastZ = position.z;
        player.xRot = player.lastTickXRot = yaw;
        player.yRot = player.lastTickYRot = pitch;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
        player.onGround = player.lastOnGround = false;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(result.getFlags().isEmpty(), describe(result));
        assertTrue(result.getOffset() < 1e-5, describe(result));
    }

    private PredictionResult tick(
            CultPlayer player, ServerboundPlayerInput input, Vec3 to, float yaw, float pitch, boolean onGround) {
        if (input != null) {
            var inputEvent = event(player, ServerboundPackets.PLAYER_INPUT, input);
            STEER.onPlayerInput(inputEvent, player, input);
            LISTENER.onPlayerInput(inputEvent, player, input);
        }
        ServerboundMovePlayer move =
                new ServerboundMovePlayer(to.x, to.y, to.z, yaw, pitch, onGround, false, true, true);
        LISTENER.onMovePlayer(event(player, ServerboundPackets.MOVE_PLAYER, move), player, move);
        LISTENER.processClientTickEndReceive(
                event(player, ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue()),
                player);
        PredictionResult result = player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotNull(result);
        return result;
    }

    private static void stopSprinting(CultPlayer player) {
        var command = new ServerboundPlayerCommand(player.entityID, PlayerCommandAction.STOP_SPRINTING, 0);
        new PacketEntityAction()
                .onPlayerCommand(event(player, ServerboundPackets.PLAYER_COMMAND, command), player, command);
    }

    /** The recording's state after its tick 1467: sprinting, falling onto the floor. */
    private CultPlayer player(float scale, float movementSpeed) {
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Scaled_Jump"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        players.add(player);
        player.gamemode = GameMode.SURVIVAL;
        CompensatedWorld world = player.compensatedWorld;
        for (int chunkZ = -3; chunkZ <= -2; chunkZ++) {
            world.putChunk(
                    19004 >> 4, chunkZ, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
        }
        for (int x = 18992; x < 19008; x++) {
            for (int z = -48; z < -16; z++) {
                world.applyBlockChangeRawDANGER(x, FLOOR_Y, z, BlockIds.STONE.defaultState());
            }
        }
        for (int[] block : ROW) {
            world.applyBlockChangeRawDANGER(
                    block[0],
                    FLOOR_Y + 1,
                    block[1],
                    DataTables.defaults().registry().block("minecraft:dirt").defaultState());
        }
        // onAttributeSnapshot refreshes every cached scalar, scale included, so it precedes the scale override.
        player.compensatedEntities
                .getSelf()
                .onAttributeSnapshot(new AttributeSnapshot("minecraft:movement_speed", movementSpeed, List.of()));
        player.compensatedEntities.getSelf().scale = scale;
        player.dimensionScale = scale;
        player.isSprinting = true;
        player.sprintState.onClientTick(true);
        player.x = player.lastX = 19004.433598022035;
        player.y = player.lastY = 63.1212968405392;
        player.z = player.lastZ = -33.9942411556237;
        player.xRot = player.lastTickXRot = 520.39624F;
        player.yRot = player.lastTickYRot = 51.444702F;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
        player.onGround = player.lastOnGround = false;
        // Tick 1467's movement after air friction and gravity.
        player.checkManager
                .getSimulationProcessor()
                .seedStartingVelocity(new Vec3(
                        -0.10297158223693259 * 0.91F,
                        (-0.373904036466719 - 0.08) * 0.98F,
                        -0.15255090795095327 * 0.91F));
        return player;
    }

    private static String describe(PredictionResult result) {
        StringBuilder text = new StringBuilder("offset ").append(result.getOffset());
        for (PredictionResult.Flag flag : result.getFlags()) {
            text.append(' ')
                    .append(flag.getCheck().getCheckName())
                    .append('=')
                    .append(flag.getVerbose().getString());
        }
        return text.toString();
    }

    private static <T extends ServerboundPacket> PacketReceiveEvent<T> event(
            CultPlayer player, PacketType<T> type, T packet) {
        return new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, type, packet);
    }
}
