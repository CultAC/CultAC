package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundInitializeBorder;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Entity#collide adds the world border's shape only when WorldBorder#isInsideCloseToBorder holds for the box it
 * queries, the mover's box expanded towards its movement: the mover is closer to the border than twice that box's
 * larger horizontal size, and at least 1.0. Shapes#collide returns 0 for an axis distance under 1.0E-7 only once
 * a shape is gathered.
 */
class WorldBorderGatherTest {
    /** A border of 10 around 0, 0: its south edge is at z 5. */
    private static final double SIZE = 10.0;

    private static final double SUB_EPSILON = -5.0E-8;
    private static final int FALLING_BLOCK = 10;

    @Test
    void aPlayerGathersTheBorderWithinTwiceItsReach() throws Exception {
        try (var f = new JavaTickFixture()) {
            initializeBorder(f);
            // A player at rest queries its own 0.6 wide box, so bbMax is 1.0.
            assertEquals(SUB_EPSILON, fall(f, 5.0 - 2.0, 0.0, 0.6));
            assertEquals(0.0, fall(f, 5.0 - 2.0 + 1.0E-6, 0.0, 0.6));
            // Moving 1.0 towards the border widens the box to 1.6, so it reaches 3.2.
            assertEquals(SUB_EPSILON, fall(f, 5.0 - 3.0, 0.5, 0.6));
            assertEquals(0.0, fall(f, 5.0 - 3.0, 1.0, 0.6));
        }
    }

    @Test
    void aBoatGathersTheBorderWithinTwiceItsReach() throws Exception {
        try (var f = new JavaTickFixture()) {
            initializeBorder(f);
            // A boat's 1.375 wide box moving 0.3 or 0.4 reaches 3.35 or 3.55.
            assertEquals(SUB_EPSILON, fall(f, 5.0 - 3.5, 0.3, 1.375));
            assertEquals(0.0, fall(f, 5.0 - 3.5, 0.4, 1.375));
            assertEquals(0.0, fall(f, 5.0 - 3.5, -0.4, 1.375));
        }
    }

    @Test
    void aMoveIntoTheBorderStopsAtIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            initializeBorder(f);
            for (double width : new double[] {0.6, 1.375}) {
                Vec3 at = new Vec3(0.5, JavaTickFixture.FLOOR_Y + 8.0, 4.0);
                f.place(at, Vec3.ZERO);
                SimpleCollisionBox box = box(at, width);
                Vec3 moved = Collisions.collide(f.player, box, 0.0, 0.0, 1.5, false);
                assertEquals(5.0 - box.maxZ, moved.z, String.valueOf(width));
            }
        }
    }

    /** A player knocked towards the border stops with its face on it; a report past it does not follow vanilla. */
    @Test
    void aPlayerKnockedIntoTheBorderStopsAtIt() throws Exception {
        for (boolean through : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                initializeBorder(f);
                knockedInto(f, "static", 5.0, through);
            }
        }
    }

    /**
     * Before 1.21.11 MovingBorderExtent#getSize follows the client's clock from when it handled the packet; a lerp of
     * no length, and a tick lerp once WorldBorder#tick has finished it, hold the target size. A knocked player stops
     * at the edge of the size the client holds.
     */
    @Test
    void aPlayerKnockedIntoALerpedBorderStopsAtTheSizeTheClientHolds() {
        record Lerp(ProtocolVersion protocol, ClientVersion client, double from, double to, long length, double edge) {}
        List<Lerp> lerps = List.of(
                new Lerp(ProtocolVersion.V1_21_9, ClientVersion.V_1_21_9, 10.0, 10.0, 0L, 5.0),
                new Lerp(ProtocolVersion.V1_21_9, ClientVersion.V_1_21_9, 20.0, 10.0, 1L, 5.0),
                new Lerp(ProtocolVersion.V1_21_9, ClientVersion.V_1_21_9, 10.0, 20.0, 1L, 10.0),
                new Lerp(ProtocolVersion.V1_21_9, ClientVersion.V_1_21_9, 2000.0, 10.0, 1L, 5.0),
                new Lerp(ProtocolVersion.V1_21_9, ClientVersion.V_1_21_9, 20.0, 10.0, 600_000L, 10.0),
                new Lerp(ProtocolVersion.V26_3, ClientVersion.V_26_3, 20.0, 10.0, 0L, 5.0),
                new Lerp(ProtocolVersion.V26_3, ClientVersion.V_26_3, 20.0, 10.0, 2L, 5.0));
        List<Executable> rows = new ArrayList<>();
        for (Lerp lerp : lerps) {
            for (boolean through : new boolean[] {false, true}) {
                rows.add(() -> {
                    try (var f = new JavaTickFixture(lerp.protocol(), lerp.client())) {
                        sendBorder(f, lerp.from(), lerp.to(), lerp.length());
                        Thread.sleep(50L);
                        Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y, lerp.edge() - 1.0);
                        f.place(start, Vec3.ZERO);
                        for (int tick = 0; tick < 3; tick++) f.tick(start, false);
                        knockedInto(f, lerp.toString(), lerp.edge(), through);
                    }
                });
            }
        }
        assertAll(rows);
    }

    /** Before 1.21.11 MovingBorderExtent shrinks on the client's clock; a player knocked into its edge stops there. */
    @Test
    void aPlayerKnockedIntoABorderShrinkingOnTheClientsClockStopsAtIt() throws Exception {
        try (var stop = new JavaTickFixture(ProtocolVersion.V1_21_9, ClientVersion.V_1_21_9);
                var through = new JavaTickFixture(ProtocolVersion.V1_21_9, ClientVersion.V_1_21_9)) {
            for (JavaTickFixture f : List.of(stop, through)) send(f, 40.0, 20.0, 100_000L);
            Thread.sleep(15_000L);
            for (JavaTickFixture f : List.of(stop, through)) {
                f.pongThrough(f.player.lastTransactionSent.get());
                Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 19.625);
                f.place(start, Vec3.ZERO);
                for (int tick = 0; tick < 3; tick++) f.tick(start, false);
                knockedInto(f, "shrinking", start.z, 0.3, 20.0, f == through);
            }
        }
    }

    /**
     * Minecraft#runTick runs at most 10 client ticks a frame, and DeltaTracker.Timer gives the frames after the next
     * one together no more ticks than Util#getMillis has passed in 50 ms steps, plus the residual it carries. Once
     * that many ticks have ended after the client handled a clock lerp, its MovingBorderExtent holds the target size.
     */
    @Test
    void aClockLerpHoldsItsTargetOnceTheClientHasTickedPastIt() {
        // 2 * 10 frame ticks, then ceil(length / 50 + length * 2^-23) for the float residual's rounding.
        long[][] rows = {{1L, 21L}, {1_000L, 41L}};
        List<Executable> checks = new ArrayList<>();
        for (long[] row : rows) {
            checks.add(() -> {
                try (var f = new JavaTickFixture(ProtocolVersion.V1_21_9, ClientVersion.V_1_21_9)) {
                    sendBorder(f, 20.0, SIZE, row[0]);
                    Thread.sleep(row[0] + 1L);
                    for (long tick = 1; tick < row[1]; tick++) f.tickEnd();
                    assertEquals(0.0, fall(f, 5.0 - 2.0, 0.0, 0.6), row[0] + " ms before");
                    f.tickEnd();
                    assertEquals(SUB_EPSILON, fall(f, 5.0 - 2.0, 0.0, 0.6), row[0] + " ms after");
                }
            });
        }
        assertAll(checks);
    }

    /** The border placed for a clock lerp follows its length on the server's clock, whatever ticks have ended. */
    @Test
    void aClockLerpHoldsUntilItsLengthHasPassed() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_9, ClientVersion.V_1_21_9)) {
            sendBorder(f, 20.0, SIZE, 600_000L);
            for (long tick = 0; tick < 12_100L; tick++) f.tickEnd();
            assertEquals(0.0, fall(f, 5.0 - 2.0, 0.0, 0.6));
        }
    }

    /**
     * FallingBlockEntity#tick moves by Entity#move, which gathers the border from the falling block's own position: a
     * block 1.0 from the edge sliding 1.0 towards it stops with its 0.98 wide box on the edge while the player stands
     * 4.5 from every edge.
     */
    @Test
    void aFallingBlockSlidingIntoTheBorderStopsAtIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            initializeBorder(f);
            double y = JavaTickFixture.FLOOR_Y + 8.0;
            f.place(new Vec3(0.5, y, 0.5), Vec3.ZERO);
            f.player.compensatedEntities.addEntity(
                    FALLING_BLOCK, EntityTypeIds.FALLING_BLOCK, new Vec3(0.5, y, 4.0), 0, 0, 0);
            PacketEntity block = f.player.compensatedEntities.getEntity(FALLING_BLOCK);
            block.hasGravity = false;
            block.deltaMovement = new Vec3(0.0, 0.0, 1.0);
            block.onMovement(f.player, true);
            assertEquals(5.0 - 0.98F / 2.0, block.clientPhysicalPosition.z, 1.0E-9);
        }
    }

    /** Knocks a player 1.0 inside the south edge at {@code edge} towards it by 1.5. */
    private static void knockedInto(JavaTickFixture f, String border, double edge, boolean through) {
        knockedInto(f, border, edge - 1.0, 1.5, edge, through);
    }

    /** Knocks a player at {@code startZ} by {@code speed} towards the south edge at {@code edge}. */
    private static void knockedInto(
            JavaTickFixture f, String border, double startZ, double speed, double edge, boolean through) {
        Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y, startZ);
        f.place(start, Vec3.ZERO);
        f.pongThrough(f.velocity(new Vec3(0.0, 0.0, speed)));
        double z = through ? startZ + speed : edge - f.player.boundingBox.maxZ + start.z;
        PredictionResult result = f.tick(new Vec3(start.x, start.y, z), false);
        double offset =
                result.getTarget().subtract(result.getAcceptedClosestToTarget()).length();
        String at = border + " through " + through + " offset " + offset;
        if (through) {
            assertFalse(result.getFlags().isEmpty(), at);
        } else {
            assertTrue(result.getFlags().isEmpty() && offset < 1.0E-5, at);
        }
    }

    /** Collides a fall of {@link #SUB_EPSILON} with a horizontal move of {@code towards} towards the south edge. */
    private static double fall(JavaTickFixture f, double z, double towards, double width) {
        Vec3 at = new Vec3(0.5, JavaTickFixture.FLOOR_Y + 8.0, z);
        f.place(at, Vec3.ZERO);
        return Collisions.collide(f.player, box(at, width), 0.0, SUB_EPSILON, towards, false).y;
    }

    private static SimpleCollisionBox box(Vec3 at, double width) {
        double half = width / 2.0;
        return new SimpleCollisionBox(at.x - half, at.y, at.z - half, at.x + half, at.y + 0.5625, at.z + half, false);
    }

    private static void initializeBorder(JavaTickFixture f) {
        sendBorder(f, SIZE, SIZE, 0L);
    }

    private static void sendBorder(JavaTickFixture f, double from, double to, long length) {
        send(f, from, to, length);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void send(JavaTickFixture f, double from, double to, long length) {
        var packet = new ClientboundInitializeBorder(0.0, 0.0, from, to, length, 29_999_984);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.INITIALIZE_BORDER, packet, false);
        f.player.checkManager.getWorldBorder().onInitializeBorder(event, f.player, packet);
    }
}
