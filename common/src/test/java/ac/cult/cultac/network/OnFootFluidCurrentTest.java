package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.FluidTypeFlowing;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Entity#updateFluidInteraction adds a player's fluid current to its deltaMovement in Entity#baseTick, before
 * LocalPlayer#aiStep: the average of the touched cells' flow times 0.014 for water and 0.0023333 or, with fast lava,
 * 0.007 for lava, raised to 0.0045 for a player nearly at rest. The channel flows +x:
 * a source at x -4, then levels 1 to 7 at x -3 to 3.
 */
class OnFootFluidCurrentTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final float UPSTREAM = 90.0F;
    private static final float DOWNSTREAM = -90.0F;
    private static final double WATER_SCALE = 0.014D;
    private static final double LAVA_SCALE = 0.0023333333333333335D;
    private static final double FAST_LAVA_SCALE = 0.007D;
    private static final double MINIMUM_PUSH = 0.0045000000000000005D;
    // LivingEntity#travelInWater's slowDown and LivingEntity#travelInLava's 0.5.
    private static final float WATER_DRAG = 0.8F;
    private static final double LAVA_DRAG = 0.5D;

    static Stream<Arguments> versions() {
        return Stream.of(
                Arguments.of(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4),
                Arguments.of(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11),
                Arguments.of(ProtocolVersion.V26_3, ClientVersion.V_26_3));
    }

    @ParameterizedTest
    @MethodSource("versions")
    void wadingDownstreamIsCarriedByTheCurrent(ProtocolVersion protocol, ClientVersion client) throws Exception {
        for (int depth = 1; depth <= 2; depth++) {
            try (var f = channel(protocol, client, BlockIds.WATER, depth)) {
                wade(f, new Vec3(-2.5, FLOOR_Y, 0.5), DOWNSTREAM, FORWARD, WATER_SCALE, WATER_DRAG, 10);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("versions")
    void wadingUpstreamIsHeldBackByTheCurrent(ProtocolVersion protocol, ClientVersion client) throws Exception {
        for (int depth = 1; depth <= 2; depth++) {
            try (var f = channel(protocol, client, BlockIds.WATER, depth)) {
                wade(f, new Vec3(-2.5, FLOOR_Y, 0.5), UPSTREAM, FORWARD, WATER_SCALE, WATER_DRAG, 10);
            }
        }
    }

    /** A client that pushes itself against the flow by the current's size instead of along it moves no further. */
    @ParameterizedTest
    @MethodSource("versions")
    void aPushAgainstTheFlowIsRejected(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = channel(protocol, client, BlockIds.WATER, 1)) {
            Vec3 position = new Vec3(-2.5, FLOOR_Y, 0.5);
            Vec3 velocity = Vec3.ZERO;
            f.player.xRot = UPSTREAM;
            f.player.yRot = 0.0F;
            f.place(position, Vec3.ZERO);
            f.input(FORWARD);
            boolean rejected = false;
            for (int tick = 0; tick < 10 && !rejected; tick++) {
                double push = tick == 0 ? WATER_SCALE : -WATER_SCALE;
                Vec3 delta = velocity.add(push, 0.0, 0.0).add(ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, UPSTREAM));
                position = position.add(delta.x, 0.0, delta.z);
                PredictionResult result = f.tick(position, true);
                rejected = !exact(result);
                velocity = new Vec3(delta.x * WATER_DRAG, 0.0, delta.z * WATER_DRAG);
            }
            assertTrue(rejected, "a push against the flow was accepted every tick");
        }
    }

    /** Level 6 water is 2/9 deep, which scales its current under the minimum push a player at rest gets. */
    @ParameterizedTest
    @MethodSource("versions")
    void standingInAShallowCurrentDriftsFromTheMinimumPush(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = channel(protocol, client, BlockIds.WATER, 1)) {
            double height = 2.0F / 9.0F - (client.isNewerThanOrEquals(ClientVersion.V_26_1) ? 0.0D : 0.001D);
            wade(f, new Vec3(2.5, FLOOR_Y, 0.5), DOWNSTREAM, NONE, WATER_SCALE * height, WATER_DRAG, 5);
        }
    }

    @ParameterizedTest
    @MethodSource("versions")
    void wadingThroughFlowingLavaIsCarriedByItsCurrent(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        for (boolean fastLava : new boolean[] {false, true}) {
            try (var f = channel(protocol, client, BlockIds.LAVA, 1)) {
                JavaTickFixture.setField(CompensatedWorld.class, f.player.compensatedWorld, "fastLava", fastLava);
                double scale = fastLava ? FAST_LAVA_SCALE : LAVA_SCALE;
                wade(f, new Vec3(-2.5, FLOOR_Y, 0.5), DOWNSTREAM, FORWARD, scale, (float) LAVA_DRAG, 6);
            }
        }
    }

    /**
     * Flowing water sent to a player standing still arrives with the ping that trails it ten ticks later: the player
     * stands dry until it answers that ping and is carried by the current from that tick on.
     */
    @ParameterizedTest
    @MethodSource("versions")
    void waterArrivingWithItsPingCarriesFromThatTick(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = new JavaTickFixture(protocol, client)) {
            f.player.sendTransaction();
            int transaction = f.player.lastTransactionSent.get();
            for (var cell : channelCells(BlockIds.WATER, 1)) {
                BlockPos pos = cell.pos;
                f.player.compensatedWorld.queueServerBlockUpdate(pos.x(), pos.y(), pos.z(), cell.state, transaction);
            }
            Vec3 position = new Vec3(-2.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(NONE);
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < 14; tick++) {
                if (tick == 10) {
                    f.pongThrough(transaction);
                }
                if (tick >= 10) {
                    Vec3 delta = velocity.add(currentFor(velocity, WATER_SCALE));
                    position = position.add(delta.x, 0.0, delta.z);
                    velocity = new Vec3(delta.x * WATER_DRAG, 0.0, delta.z * WATER_DRAG);
                }
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), "setback at " + tick);
            }
        }
    }

    static Stream<Arguments> exactSmallMoveVersions() {
        return Stream.of(
                Arguments.of(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11),
                Arguments.of(ProtocolVersion.V26_3, ClientVersion.V_26_3));
    }

    /** A player that drifts with the current before the ping that brings the water is answered moves too early. */
    @ParameterizedTest
    @MethodSource("exactSmallMoveVersions")
    void driftingBeforeTheWaterArrivesIsRejected(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = new JavaTickFixture(protocol, client)) {
            f.player.sendTransaction();
            int transaction = f.player.lastTransactionSent.get();
            for (var cell : channelCells(BlockIds.WATER, 1)) {
                BlockPos pos = cell.pos;
                f.player.compensatedWorld.queueServerBlockUpdate(pos.x(), pos.y(), pos.z(), cell.state, transaction);
            }
            Vec3 position = new Vec3(-2.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(NONE);
            Vec3 velocity = Vec3.ZERO;
            boolean rejected = false;
            for (int tick = 0; tick < 10 && !rejected; tick++) {
                Vec3 delta = velocity.add(currentFor(velocity, WATER_SCALE));
                position = position.add(delta.x, 0.0, delta.z);
                velocity = new Vec3(delta.x * WATER_DRAG, 0.0, delta.z * WATER_DRAG);
                rejected = !exact(f.tick(position, true));
            }
            assertTrue(rejected, "the drift was accepted before the water arrived");
        }
    }

    /**
     * Walks {@code ticks} ticks on the channel floor with {@code keys} looking along {@code yaw}: each tick moves by the
     * carried velocity, the +x current and the input, and carries the move times {@code drag}.
     */
    private static void wade(
            JavaTickFixture f,
            Vec3 start,
            float yaw,
            ServerboundPlayerInput keys,
            double scale,
            float drag,
            int ticks) {
        f.player.xRot = yaw;
        f.player.yRot = 0.0F;
        f.place(start, Vec3.ZERO);
        f.input(keys);
        Vec3 position = start;
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < ticks; tick++) {
            Vec3 pushed = threshold(f, velocity.add(currentFor(velocity, scale)));
            Vec3 delta = pushed.add(ClientMoveInput.of(f, keys, 1.0F, 0.02F, yaw));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult result = f.tick(position, true);
            assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            velocity = new Vec3(delta.x * drag, 0.0, delta.z * drag);
        }
    }

    /**
     * LivingEntity#aiStep's movement threshold on the pushed horizontal velocity: from 1.21.5 a horizontal speed under
     * 0.003 stops both axes, before that each axis under 0.003 stops on its own.
     */
    private static Vec3 threshold(JavaTickFixture f, Vec3 velocity) {
        if (f.player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)) {
            return velocity.x * velocity.x + velocity.z * velocity.z < 9.0E-6
                    ? new Vec3(0.0, velocity.y, 0.0)
                    : velocity;
        }
        return new Vec3(
                Math.abs(velocity.x) < 0.003 ? 0.0 : velocity.x,
                velocity.y,
                Math.abs(velocity.z) < 0.003 ? 0.0 : velocity.z);
    }

    /** EntityFluidInteraction's +x current of {@code scale}, raised to the minimum push for a velocity at rest. */
    private static Vec3 currentFor(Vec3 velocity, double scale) {
        boolean resting = Math.abs(velocity.x) < 0.003 && Math.abs(velocity.z) < 0.003;
        return new Vec3(resting && scale < MINIMUM_PUSH ? MINIMUM_PUSH : scale, 0.0, 0.0);
    }

    /**
     * A sprinter on the floor of the two-deep channel swims from its second sprinting tick: Entity#updateSwimming,
     * then Player#updatePlayerPose's 0.6 swimming box, whose cells still all flow +x. LivingEntity#travelInWater slows
     * a sprinter by 0.9F.
     */
    @ParameterizedTest
    @MethodSource("versions")
    void sprintSwimmingAlongTheFloorIsCarriedByTheCurrent(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        for (float yaw : new float[] {UPSTREAM, DOWNSTREAM}) {
            try (var f = channel(protocol, client, BlockIds.WATER, 2)) {
                Vec3 start = new Vec3(yaw == UPSTREAM ? -1.5 : -2.5, FLOOR_Y, 0.5);
                f.player.xRot = yaw;
                f.player.yRot = 0.0F;
                f.place(start, Vec3.ZERO);
                f.input(NONE);
                Vec3 position = start;
                Vec3 velocity = Vec3.ZERO;
                for (int tick = 0; tick < 12; tick++) {
                    boolean sprinting = tick > 0;
                    if (tick == 1) {
                        command(f, PlayerCommandAction.START_SPRINTING);
                        f.input(FORWARD_SPRINT);
                    }
                    ServerboundPlayerInput keys = sprinting ? FORWARD_SPRINT : NONE;
                    Vec3 pushed = threshold(f, velocity.add(currentFor(velocity, WATER_SCALE)));
                    Vec3 delta = pushed.add(ClientMoveInput.of(f, keys, 1.0F, 0.02F, yaw));
                    position = position.add(delta.x, 0.0, delta.z);
                    PredictionResult result = f.tick(position, true);
                    assertTrue(exact(result), "yaw " + yaw + " tick " + tick + ": " + describe(result));
                    float drag = sprinting ? 0.9F : WATER_DRAG;
                    velocity = new Vec3(delta.x * drag, 0.0, delta.z * drag);
                }
                assertEquals(Pose.SWIMMING, f.player.pose, "yaw " + yaw);
            }
        }
    }

    /**
     * A source replacing the level 1 water beside the player's cell turns that cell's flow from +x towards -z. The
     * change can still be ahead of the client for the ticks after it, so the move keeps the current of either flow,
     * and no candidate goes without a current.
     */
    @ParameterizedTest
    @MethodSource("versions")
    void aRecentFlowChangeBesideThePlayerKeepsTheCurrentOfEitherFlow(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = channel(protocol, client, BlockIds.WATER, 1)) {
            Vec3 position = drift(f);
            Vec3 velocity = position.subtract(new Vec3(-2.5, FLOOR_Y, 0.5)).scale(WATER_DRAG);

            f.player.compensatedWorld.updateBlock(new BlockPos(-3, FLOOR_Y, 1), BlockIds.WATER.defaultState());
            BlockPos cell = new BlockPos(-3, FLOOR_Y, 0);
            Vec3 newCurrent = FluidTypeFlowing.flow(
                            f.player, cell, ClientFluidQueries.fluidAt(f.player.compensatedWorld, cell))
                    .scale(WATER_SCALE);
            Vec3 oldCurrent = new Vec3(WATER_SCALE, 0.0, 0.0);
            for (int tick = 0; tick < 2; tick++) {
                Vec3 delta = threshold(f, velocity.add(newCurrent));
                position = position.add(delta.x, 0.0, delta.z);
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                assertTrue(carriesTheWaterCurrent(result), "tick " + tick + ": a candidate without the water current");
                assertTrue(carriesWaterCurrent(result, oldCurrent), "tick " + tick + ": no candidate with the old flow");
                assertTrue(carriesWaterCurrent(result, newCurrent), "tick " + tick + ": no candidate with the new flow");
                velocity = new Vec3(delta.x * WATER_DRAG, 0.0, delta.z * WATER_DRAG);
            }
        }
    }

    /**
     * Lava placed at the player's head leaves the lava uncertain for a few ticks; the water current stays certain, so
     * no candidate goes without it.
     */
    @ParameterizedTest
    @MethodSource("versions")
    void anUncertainLavaCellKeepsTheWaterCurrent(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = channel(protocol, client, BlockIds.WATER, 1)) {
            Vec3 position = drift(f);
            Vec3 velocity = position.subtract(new Vec3(-2.5, FLOOR_Y, 0.5)).scale(WATER_DRAG);

            f.player.compensatedWorld.updateBlock(new BlockPos(-3, FLOOR_Y + 1, 0), BlockIds.LAVA.defaultState());
            for (int tick = 0; tick < 2; tick++) {
                Vec3 delta = threshold(f, velocity.add(WATER_SCALE, 0.0, 0.0));
                position = position.add(delta.x, 0.0, delta.z);
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                assertTrue(carriesTheWaterCurrent(result), "tick " + tick + ": a candidate without the water current");
                velocity = new Vec3(delta.x * WATER_DRAG, 0.0, delta.z * WATER_DRAG);
            }
        }
    }

    /** A tick at rest at -2.5 in the level 1 water, carried by its +x current; the position it ends at. */
    private static Vec3 drift(JavaTickFixture f) {
        Vec3 start = new Vec3(-2.5, FLOOR_Y, 0.5);
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        f.place(start, Vec3.ZERO);
        f.input(NONE);
        Vec3 delta = threshold(f, currentFor(Vec3.ZERO, WATER_SCALE));
        Vec3 position = start.add(delta.x, 0.0, delta.z);
        assertTrue(exact(f.tick(position, true)), "the first tick was rejected");
        return position;
    }

    /**
     * A player at rest with its feet 0.002 under the surface of the level 1 water scales that cell's flow by 0.002:
     * Entity#updateFluidHeightAndDoFluidPushing raises any current longer than 0 to the 0.0045 minimum push, and from
     * 26.1 EntityFluidInteraction drops a current under 1.0E-5F squared instead.
     */
    @ParameterizedTest
    @MethodSource("versions")
    void aCurrentScaledNearlyToNothingPushesOnlyBefore26Dot1(ProtocolVersion protocol, ClientVersion client)
            throws Exception {
        try (var f = channel(protocol, client, BlockIds.WATER, 1)) {
            double entityMinYOffset = client.isNewerThanOrEquals(ClientVersion.V_26_1) ? 0.0D : 0.001D;
            Vec3 start = new Vec3(-2.5, FLOOR_Y + 7.0F / 9.0F - 0.002 - entityMinYOffset, 0.5);
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            f.place(start, Vec3.ZERO);
            f.input(NONE);
            double push = client.isNewerThanOrEquals(ClientVersion.V_26_1) ? 0.0D : MINIMUM_PUSH;
            PredictionResult result = f.tick(start.add(push, 0.0, 0.0), false);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * Whether every candidate the move's realities start from adds a water current. A move in water is checked inside
     * the circular input allowance, which covers a single tick's current.
     */
    private static boolean carriesTheWaterCurrent(PredictionResult result) {
        for (PredictionResult reality : result.getRealities()) {
            if (reality.getInitialStartingVel().latestWithReason("water current") == null) return false;
        }
        return !result.getRealities().isEmpty();
    }

    /** Whether a candidate the move's realities start from adds {@code current} as its water current. */
    private static boolean carriesWaterCurrent(PredictionResult result, Vec3 current) {
        for (PredictionResult reality : result.getRealities()) {
            Vec3 added = reality.getInitialStartingVel().additionOfReason("water current");
            if (added.distanceTo(current) < 1.0E-9) return true;
        }
        return false;
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    /** Flowing {@code fluid} {@code depth} blocks deep along the channel, settled past the block-change timing. */
    private static JavaTickFixture channel(ProtocolVersion protocol, ClientVersion client, BlockDefinition fluid, int depth)
            throws Exception {
        var f = new JavaTickFixture(protocol, client);
        for (var cell : channelCells(fluid, depth)) f.player.compensatedWorld.updateBlock(cell.pos, cell.state);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    private record Cell(BlockPos pos, int state) {}

    private static List<Cell> channelCells(BlockDefinition fluid, int depth) {
        var registry = DataTables.defaults().registry();
        var cells = new ArrayList<Cell>();
        for (int x = -4; x <= 3; x++) {
            int state = x == -4
                    ? fluid.defaultState()
                    : registry.with(fluid.defaultState(), "level", String.valueOf(x + 4));
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y < FLOOR_Y + depth; y++) cells.add(new Cell(new BlockPos(x, y, z), state));
            }
        }
        return cells;
    }
}
