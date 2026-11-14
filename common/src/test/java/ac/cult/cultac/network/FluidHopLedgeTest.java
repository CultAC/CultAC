package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LivingEntity#jumpOutOfFluid sets a swimmer's vertical velocity to 0.3 after a move that collided horizontally only
 * when Entity#isFree holds, in the world of that tick, for its pose box moved by the velocity and raised 0.6 above
 * where the move started: no block collides with that box and it holds no fluid. A swimmer at the surface hops onto a
 * one-block ledge and bobs against a higher wall; a submerged swimmer does not hop. Entity#applyEffectsFromBlocks runs
 * the bubble column callbacks of that move on the hop's velocity.
 */
class FluidHopLedgeTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);
    private static final float TOWARDS_THE_WALL = -90.0F;
    private static final double HOP = (double) 0.3F;
    private static final double PLAYER_HEIGHT = 1.8F;
    private static final int DEEP = FLOOR_Y + 30;
    // The box of a player here ends 1.0E-6 short of the wall's face.
    private static final double BY_THE_WALL = 0.7 - 1.0E-6;
    // Entity#collide stops a move into the wall where the box meets its face.
    private static final double AT_THE_WALL = BY_THE_WALL + (1.0 - (BY_THE_WALL + (double) 0.3F));
    private static final Vec3 SURFACE = new Vec3(AT_THE_WALL, FLOOR_Y + 0.5, 0.5);
    private static final BlockPos ABOVE_THE_HEAD = new BlockPos(0, FLOOR_Y + 2, 0);
    private static final int TOP_SLAB = DataTables.defaults()
            .registry()
            .with(DataTables.defaults().registry().block("minecraft:stone_slab").defaultState(), "type", "top");

    static Stream<Arguments> cases() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (BlockDefinition fluid : new BlockDefinition[] {BlockIds.WATER, BlockIds.LAVA}) {
            cases.add(Arguments.of(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4, fluid));
            cases.add(Arguments.of(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11, fluid));
            cases.add(Arguments.of(ProtocolVersion.V26_3, ClientVersion.V_26_3, fluid));
        }
        return cases.build();
    }

    static Stream<Arguments> versions() {
        return Stream.of(
                Arguments.of(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4),
                Arguments.of(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11),
                Arguments.of(ProtocolVersion.V26_3, ClientVersion.V_26_3));
    }

    static Stream<Arguments> exactEffectVersions() {
        return Stream.of(
                Arguments.of(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11),
                Arguments.of(ProtocolVersion.V26_3, ClientVersion.V_26_3));
    }

    static Stream<Arguments> columns() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (boolean drag : new boolean[] {false, true}) {
            cases.add(Arguments.of(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11, drag));
            cases.add(Arguments.of(ProtocolVersion.V26_3, ClientVersion.V_26_3, drag));
        }
        return cases.build();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void aSwimmerAtTheSurfaceHopsOntoALedge(ProtocolVersion protocol, ClientVersion client, BlockDefinition fluid)
            throws Exception {
        try (var f = pool(protocol, client, fluid, FLOOR_Y, FLOOR_Y)) {
            PredictionResult hop = surfaceHop(f, HOP);
            assertTrue(exact(hop), "the hop onto the ledge was rejected: " + describe(hop));
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void aSwimmerAtTheSurfaceHopsAgainstATwoBlockWall(
            ProtocolVersion protocol, ClientVersion client, BlockDefinition fluid) throws Exception {
        try (var f = pool(protocol, client, fluid, FLOOR_Y, FLOOR_Y + 1)) {
            PredictionResult hop = surfaceHop(f, HOP);
            assertTrue(exact(hop), "the hop against the wall was rejected: " + describe(hop));
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void aSubmergedSwimmerDoesNotHopUpAWall(ProtocolVersion protocol, ClientVersion client, BlockDefinition fluid)
            throws Exception {
        for (ServerboundPlayerInput keys : new ServerboundPlayerInput[] {FORWARD, FORWARD_JUMP}) {
            try (var f = pool(protocol, client, fluid, DEEP, DEEP)) {
                assertTrue(climbRejected(f, keys, FLOOR_Y - 1, false), "the submerged climb was accepted");
            }
        }
    }

    /**
     * A swimmer leaving a soul sand column, or crossing the top of a magma column, against a ledge hops, and the
     * surface callback of the column block its box ends in then changes the hop's 0.3: +0.1 or -0.03.
     */
    @ParameterizedTest
    @MethodSource("columns")
    void aHopOutOfABubbleColumnTakesTheColumnAfterTheHop(ProtocolVersion protocol, ClientVersion client, boolean drag)
            throws Exception {
        try (var f = pool(protocol, client, BlockIds.WATER, FLOOR_Y, FLOOR_Y)) {
            column(f, FLOOR_Y, drag);
            double hop = drag ? Math.max(-0.9, HOP - 0.03) : Math.min(1.8, HOP + 0.1);
            PredictionResult result = surfaceHop(f, hop);
            assertTrue(exact(result), "the hop out of the column was rejected: " + describe(result));
        }
    }

    @ParameterizedTest
    @MethodSource("exactEffectVersions")
    void aHopOutOfASoulSandColumnWithoutItsPushIsRejected(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = pool(protocol, client, BlockIds.WATER, FLOOR_Y, FLOOR_Y)) {
            column(f, FLOOR_Y, false);
            PredictionResult result = surfaceHop(f, HOP);
            assertTrue(!exact(result), "the hop without the column was accepted");
        }
    }

    @ParameterizedTest
    @MethodSource("columns")
    void aSubmergedSwimmerInABubbleColumnDoesNotHopUpAWall(
            ProtocolVersion protocol, ClientVersion client, boolean drag) throws Exception {
        for (ServerboundPlayerInput keys : new ServerboundPlayerInput[] {FORWARD, FORWARD_JUMP}) {
            try (var f = pool(protocol, client, BlockIds.WATER, DEEP, DEEP)) {
                column(f, DEEP, drag);
                assertTrue(climbRejected(f, keys, DEEP, drag), "the submerged climb in the column was accepted");
            }
        }
    }

    /** A slab that appears above the head after the hop's tick does not take back the hop that tick made. */
    @ParameterizedTest
    @MethodSource("versions")
    void aBlockAboveThatArrivesAfterTheHopKeepsTheHop(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = pool(protocol, client, BlockIds.WATER, FLOOR_Y, FLOOR_Y)) {
            pushIntoTheWall(f);
            f.player.compensatedWorld.updateBlock(ABOVE_THE_HEAD, TOP_SLAB);
            PredictionResult result = tick(f, SURFACE.add(0.0, underTheSlab(), 0.0));
            assertTrue(exact(result), "the hop under the new slab was rejected: " + describe(result));
        }
    }

    /**
     * A slab sent before the hop's tick, whose ping the client answers only after that tick's move, reached the client
     * after the hop.
     */
    @ParameterizedTest
    @MethodSource("versions")
    void aBlockAboveAnsweredAfterTheHopKeepsTheHop(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = pool(protocol, client, BlockIds.WATER, FLOOR_Y, FLOOR_Y)) {
            f.player.sendTransaction();
            int transaction = f.player.lastTransactionSent.get();
            f.player.compensatedWorld.queueServerBlockUpdate(
                    ABOVE_THE_HEAD.x(), ABOVE_THE_HEAD.y(), ABOVE_THE_HEAD.z(), TOP_SLAB, transaction);
            pushIntoTheWall(f);
            f.pongThrough(transaction);
            PredictionResult result = tick(f, SURFACE.add(0.0, underTheSlab(), 0.0));
            assertTrue(exact(result), "the hop under the answered slab was rejected: " + describe(result));
        }
    }

    @ParameterizedTest
    @MethodSource("versions")
    void aSlabAboveTheHeadInTheHopsTickStopsTheHop(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = pool(protocol, client, BlockIds.WATER, FLOOR_Y, FLOOR_Y)) {
            f.player.compensatedWorld.updateBlock(ABOVE_THE_HEAD, TOP_SLAB);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            pushIntoTheWall(f);
            PredictionResult result = tick(f, SURFACE.add(0.0, underTheSlab(), 0.0));
            assertTrue(!exact(result), "the hop under the slab was accepted");
        }
    }

    /** The rise of the hop until the top of the box meets the slab above the head. */
    private static double underTheSlab() {
        return Math.min(HOP, ABOVE_THE_HEAD.y() + 0.5 - (SURFACE.y + PLAYER_HEIGHT));
    }

    /** Pushes into the wall from rest half a block above the floor; the move collides at the wall's face. */
    private static void pushIntoTheWall(JavaTickFixture f) {
        f.player.xRot = TOWARDS_THE_WALL;
        f.player.yRot = 0.0F;
        f.place(new Vec3(BY_THE_WALL, SURFACE.y, SURFACE.z), Vec3.ZERO);
        f.input(FORWARD);
        tick(f, SURFACE);
    }

    /** {@link #pushIntoTheWall}, then a move that rises by {@code rise}. */
    private static PredictionResult surfaceHop(JavaTickFixture f, double rise) {
        pushIntoTheWall(f);
        return tick(f, SURFACE.add(0.0, rise, 0.0));
    }

    /**
     * Pushes into the wall deep under the surface with {@code keys}, then climbs by the hop on every move, each
     * changed by the inside callbacks of a column up to {@code columnTop}; whether a climbing move was rejected.
     */
    private static boolean climbRejected(JavaTickFixture f, ServerboundPlayerInput keys, int columnTop, boolean drag) {
        f.player.xRot = TOWARDS_THE_WALL;
        f.player.yRot = 0.0F;
        f.place(new Vec3(BY_THE_WALL, FLOOR_Y + 3, 0.5), Vec3.ZERO);
        f.input(keys);
        Vec3 position = new Vec3(AT_THE_WALL, FLOOR_Y + 3, 0.5);
        tick(f, position);
        for (int hop = 0; hop < 12; hop++) {
            double rise = HOP;
            for (int block = 0, blocks = columnBlocks(position.y, columnTop); block < blocks; block++) {
                rise = drag ? Math.max(-0.3, rise - 0.03) : Math.min(0.7, rise + 0.06);
            }
            position = position.add(0.0, rise, 0.0);
            if (!exact(tick(f, position))) return true;
        }
        return false;
    }

    /** The column blocks up to {@code columnTop} that the box at {@code y}, deflated by 1.0E-5F, intersects. */
    private static int columnBlocks(double y, int columnTop) {
        double min = y + 1.0E-5F;
        double max = y + PLAYER_HEIGHT - 1.0E-5F;
        int blocks = 0;
        for (int block = FLOOR_Y; block <= columnTop; block++) {
            if (block < max && block + 1 > min) blocks++;
        }
        return blocks;
    }

    /** A bubble column from the floor up to {@code top} in the swimmer's block column, settled. */
    private static void column(JavaTickFixture f, int top, boolean drag) {
        int column = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), drag);
        for (int y = FLOOR_Y; y <= top; y++) f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), column);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** A pool of {@code fluid} up to {@code fluidTop} with a stone wall at x 1 up to {@code wallTop}. */
    private static JavaTickFixture pool(
            ProtocolVersion protocol, ClientVersion client, BlockDefinition fluid, int fluidTop, int wallTop)
            throws Exception {
        var f = new JavaTickFixture(protocol, client);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                int top = x >= 1 ? wallTop : fluidTop;
                int state = x >= 1 ? BlockIds.STONE.defaultState() : fluid.defaultState();
                for (int y = FLOOR_Y; y <= top; y++) f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    /** One client tick whose move packet reports a horizontal collision. */
    private static PredictionResult tick(JavaTickFixture f, Vec3 to) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var packet = new ServerboundMovePlayer(to.x, to.y, to.z, f.player.xRot, f.player.yRot, false, true, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotNull(result);
        assertTrue(result != before, "the tick committed no prediction: " + describe(result));
        return result;
    }
}
