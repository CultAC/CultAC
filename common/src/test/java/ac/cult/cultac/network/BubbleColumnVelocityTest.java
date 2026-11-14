package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Entity#checkInsideBlocks runs BubbleColumnBlock#entityInside once for every column block the player's box ends a
 * move in, after LivingEntity#travel: a magma column sets the deltaMovement to max(-0.3, y - 0.03), a soul sand column
 * to min(0.7, y + 0.06), each time. An idle swimmer in a column of water therefore carries y * 0.8 - 0.005 from
 * LivingEntity#travelInWater plus those changes into its next move.
 */
class BubbleColumnVelocityTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 3, 0.5);
    private static final int COLUMN_TOP = FLOOR_Y + 12;
    private static final double PLAYER_HEIGHT = 1.8F;
    private static final int TICKS = 8;

    static Stream<Arguments> versions() {
        return Stream.of(
                Arguments.of(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11),
                Arguments.of(ProtocolVersion.V26_3, ClientVersion.V_26_3));
    }

    @ParameterizedTest
    @MethodSource("versions")
    void aMagmaColumnDragsTheSwimmerDown(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = column(protocol, client, true)) {
            assertTrue(swim(f, true, 1.0, 0), "the dragged swim was rejected");
        }
    }

    @ParameterizedTest
    @MethodSource("versions")
    void aSoulSandColumnPushesTheSwimmerUp(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = column(protocol, client, false)) {
            assertTrue(swim(f, false, 1.0, 0), "the pushed swim was rejected");
        }
    }

    @ParameterizedTest
    @MethodSource("versions")
    void hoveringInAMagmaColumnIsRejected(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = column(protocol, client, true)) {
            assertTrue(!swim(f, true, 0.0, 0), "the hover was accepted");
        }
    }

    @ParameterizedTest
    @MethodSource("versions")
    void halfTheMagmaColumnDragIsRejected(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = column(protocol, client, true)) {
            assertTrue(!swim(f, true, 0.5, 0), "half the drag was accepted");
        }
    }

    @ParameterizedTest
    @MethodSource("versions")
    void sinkingWithoutTheSoulSandPushIsRejected(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = column(protocol, client, false)) {
            assertTrue(!swim(f, false, 0.0, 0), "the unpushed sink was accepted");
        }
    }

    /**
     * A magma column sent to a swimmer arrives with the ping that trails it: the swimmer sinks through plain water
     * until it answers that ping, and the column drags it from the move that ends inside it after that.
     */
    @ParameterizedTest
    @MethodSource("versions")
    void aColumnArrivingWithItsPingDragsFromThatTick(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = new JavaTickFixture(protocol, client)) {
            int transaction = pendingColumn(f);
            assertTrue(swim(f, true, 1.0, 4, transaction), "the drag from the column's ping on was rejected");
        }
    }

    @ParameterizedTest
    @MethodSource("versions")
    void takingTheDragBeforeTheColumnArrivesIsRejected(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = new JavaTickFixture(protocol, client)) {
            pendingColumn(f);
            assertTrue(!swim(f, true, 1.0, 0), "the drag before the column's ping was accepted");
        }
    }

    private static JavaTickFixture column(ProtocolVersion protocol, ClientVersion client, boolean drag)
            throws Exception {
        var f = new JavaTickFixture(protocol, client);
        f.water(new BlockPos(0, FLOOR_Y, 0), new BlockPos(0, COLUMN_TOP + 1, 0));
        int column = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), drag);
        for (int y = FLOOR_Y; y <= COLUMN_TOP; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), column);
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(START, Vec3.ZERO);
        f.input(NONE);
        return f;
    }

    /** Queues a magma column behind a ping and returns that ping's transaction. */
    private static int pendingColumn(JavaTickFixture f) {
        f.water(new BlockPos(0, FLOOR_Y, 0), new BlockPos(0, COLUMN_TOP + 1, 0));
        f.player.sendTransaction();
        int transaction = f.player.lastTransactionSent.get();
        int column = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), true);
        for (int y = FLOOR_Y; y <= COLUMN_TOP; y++) {
            f.player.compensatedWorld.queueServerBlockUpdate(0, y, 0, column, transaction);
        }
        f.place(START, Vec3.ZERO);
        f.input(NONE);
        return transaction;
    }

    private static boolean swim(JavaTickFixture f, boolean drag, double share, int columnFrom) {
        return swim(f, drag, share, columnFrom, null);
    }

    /**
     * Swims idle for {@code TICKS} ticks, scaling each column change after a move by {@code share} and taking none
     * before move {@code columnFrom}, and answers {@code transaction} before move {@code columnFrom}; returns whether
     * every move past the first was exact.
     */
    private static boolean swim(
            JavaTickFixture f, boolean drag, double share, int columnFrom, Integer transaction) {
        double y = START.y;
        double velocity = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            if (transaction != null && tick == columnFrom) f.pongThrough(transaction);
            PredictionResult result = f.tick(new Vec3(START.x, y, START.z), false);
            if (tick > 0 && !exact(result)) return false;
            velocity = velocity * 0.8F - 0.005;
            if (tick >= columnFrom) {
                for (int block = 0, blocks = columnBlocks(y); block < blocks; block++) {
                    double changed = drag ? Math.max(-0.3, velocity - 0.03) : Math.min(0.7, velocity + 0.06);
                    velocity += (changed - velocity) * share;
                }
            }
            y += velocity;
        }
        return true;
    }

    /** The column blocks that the box at {@code y}, deflated by 1.0E-5F, intersects. */
    private static int columnBlocks(double y) {
        double min = y + 1.0E-5F;
        double max = y + PLAYER_HEIGHT - 1.0E-5F;
        int blocks = 0;
        for (int block = FLOOR_Y; block <= COLUMN_TOP; block++) {
            if (block < max && block + 1 > min) blocks++;
        }
        return blocks;
    }
}
