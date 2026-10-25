package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A client sprints forward at yaw 22.5 into a wall at z = 1, 22.5 degrees off the wall: a hard collision. The block
 * entities tick after the player's travel, and an opening shulker box's ShulkerBoxBlockEntity#moveCollidedEntities
 * moves the player with Entity#move(MoverType.SHULKER_BOX), which sets horizontalCollision from its own movement: none
 * for a shove up. LocalPlayer#aiStep reads it in the next tick before that tick's travel, so a player the lid shoves
 * keeps sprinting. From 26.3 Minecraft#tick sends the position after the block entities, so the move carries the
 * shove after its travel; before, the next move carries it before its own travel.
 */
class JavaShulkerBoxSprintTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final float YAW = 22.5F;
    // The yaw LivingEntity#moveRelative turns forward input west by.
    private static final float WEST = 90.0F;
    private static final double HALF_WIDTH = (double) (0.6F / 2.0F);
    private static final float GROUND_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    // Player#getFlyingSpeed while sprinting.
    private static final float AIR_SPEED = 0.025999999F;
    // LivingEntity#travelInAir's ground friction.
    private static final double GROUND_FRICTION = 0.6F * 0.91F;

    @Test
    void aPlayerTheLidShovesKeepsSprintingIntoTheWall() throws Exception {
        try (var f = new JavaTickFixture()) {
            BlockPos box = new BlockPos(0, FLOOR_Y, 0);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1, 1.0 - 0.3F);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            SprintE sprintE = wall(f, box, FLOOR_Y + 1);
            open(f, box);

            Vec3 ground = ClientMoveInput.of(f, SPRINT, 1.0F, GROUND_SPEED, YAW);
            f.input(SPRINT);
            command(f, sprintE, PlayerCommandAction.START_SPRINTING);
            double lid = FLOOR_Y + 1.0;
            position = new Vec3(position.x + ground.x, shove(lid, 0.0F, 0.1F), position.z);
            PredictionResult shoved = f.tick(position, false);
            assertTrue(exact(shoved), describe(shoved));
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(shoved), describe(shoved));

            Vec3 air = ClientMoveInput.of(f, SPRINT, 1.0F, AIR_SPEED, YAW);
            lid = FLOOR_Y + 1.0 + (double) (0.5F * 0.1F);
            position = new Vec3(position.x + ground.x * GROUND_FRICTION + air.x, shove(lid, 0.1F, 0.2F), position.z);
            PredictionResult kept = f.tick(position, false);
            assertTrue(exact(kept), describe(kept));
            assertEquals(0.0, sprintE.violations);
        }
    }

    /** Before 26.3 the first move is the travel alone, and the next carries the shove before its travel. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_1", "V_26_2"})
    void anOlderPlayerTheLidShovesKeepsSprintingIntoTheWall(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            BlockPos box = new BlockPos(0, FLOOR_Y, 0);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1, 1.0 - 0.3F);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            SprintE sprintE = wall(f, box, FLOOR_Y + 1);
            open(f, box);

            Vec3 ground = ClientMoveInput.of(f, SPRINT, 1.0F, GROUND_SPEED, YAW);
            f.input(SPRINT);
            command(f, sprintE, PlayerCommandAction.START_SPRINTING);
            position = new Vec3(position.x + ground.x, FLOOR_Y + 1.0, position.z);
            PredictionResult blocked = f.tick(position, true);
            assertTrue(exact(blocked), describe(blocked));

            Vec3 air = ClientMoveInput.of(f, SPRINT, 1.0F, AIR_SPEED, YAW);
            double lid = FLOOR_Y + 1.0 + (double) (0.5F * 0.1F);
            position = new Vec3(position.x + ground.x * GROUND_FRICTION + air.x, lid, position.z);
            PredictionResult kept = f.tick(position, true);
            assertTrue(exact(kept), describe(kept));
            assertEquals(0.0, sprintE.violations);
        }
    }

    /**
     * Before 26.3 a box facing east shoves its rider into a wall at x = 2 at the end of the tick it starts sprinting
     * in, and the rider turns before the next. Entity#isHorizontalCollisionMinor reads the yaw and keys of the tick
     * the shove ends: along the shove it is minor and the sprint goes on, across it a hard collision stops it.
     */
    @ParameterizedTest
    @CsvSource({"-90.0, 0.0, 0.0", "0.0, -90.0, 1.0"})
    void anOlderPlayerTheLidShovesIntoAWallJudgesTheShoveByItsOwnTick(float shovedYaw, float nextYaw, double violations)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_2);
            BlockPos box = new BlockPos(0, FLOOR_Y, 0);
            Vec3 position = new Vec3(1.0 + HALF_WIDTH, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            SprintE sprintE = sideWall(f, box);
            open(f, box);

            f.input(NONE);
            PredictionResult travel = f.tick(position, true);
            assertTrue(exact(travel), describe(travel));
            float progress = 0.0F;
            for (int tick = 0; tick < 2; tick++) {
                float progressOld = progress;
                progress += 0.1F;
                position = position.add(sidewaysShove(progressOld, progress), 0.0, 0.0);
                PredictionResult shoved = f.tick(position, true);
                assertTrue(exact(shoved), describe(shoved));
            }

            f.player.xRot = shovedYaw;
            Vec3 air = ClientMoveInput.of(f, SPRINT, 1.0F, AIR_SPEED, shovedYaw);
            f.input(SPRINT);
            command(f, sprintE, PlayerCommandAction.START_SPRINTING);
            position = position.add(sidewaysShove(progress, progress + 0.1F) + air.x, 0.0, air.z);
            PredictionResult sprinting = f.tick(position, true);
            assertTrue(exact(sprinting), describe(sprinting));

            // The wall stops the shove and zeroes the x velocity; LivingEntity#aiStep zeroes a residue under 0.003.
            f.player.xRot = nextYaw;
            Vec3 next = ClientMoveInput.of(f, SPRINT, 1.0F, AIR_SPEED, nextYaw);
            double velocityZ = air.z * (double) 0.91F;
            if (Math.abs(velocityZ) < 0.003D) velocityZ = 0.0D;
            position = new Vec3(2.0 - HALF_WIDTH, FLOOR_Y, position.z + velocityZ + next.z);
            PredictionResult turned = f.tick(position, true);
            assertTrue(exact(turned), describe(turned));
            assertEquals(violations, sprintE.violations);
        }
    }

    /**
     * A 26.3 client sprinting west head-on into the push area of an east-facing box as it opens: the travel ends
     * inside it and the shove moves the player back east, off the ground, to where only a shorter travel than the
     * keys give ends unshoved.
     */
    @ParameterizedTest
    @ValueSource(doubles = {1.29, 1.37})
    void aPlayerSprintingIntoASidewaysShoveIsShoved(double minX) throws Exception {
        PredictionResult result = sprintIntoSidewaysShove(minX, false);
        assertTrue(exact(result), describe(result));
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.29, 1.37})
    void aPlayerSprintingIntoASidewaysShoveOnTheGroundIsRejected(double minX) throws Exception {
        JavaTickFixture.assertRejected(sprintIntoSidewaysShove(minX, true), "GroundSpoof");
    }

    /**
     * A 26.3 client sprinting west along the floor towards an east-facing box, its box's west face at {@code minX}
     * after the run-up, and sprinting on as the box opens; the move it reports is shoved east by the first step.
     */
    private static PredictionResult sprintIntoSidewaysShove(double minX, boolean onGround) throws Exception {
        try (var f = new JavaTickFixture()) {
            BlockPos box = new BlockPos(0, FLOOR_Y, 0);
            int state = REGISTRY.with(REGISTRY.block("minecraft:shulker_box").defaultState(), "facing", "east");
            f.player.compensatedWorld.updateBlock(box, state);
            Vec3 ground = ClientMoveInput.of(f, SPRINT, 1.0F, GROUND_SPEED, WEST);
            int runUp = 10;
            double[] moves = new double[runUp + 1];
            double velocity = 0.0;
            double distance = 0.0;
            for (int tick = 0; tick <= runUp; tick++) {
                moves[tick] = velocity + ground.x;
                velocity = moves[tick] * GROUND_FRICTION;
                if (tick < runUp) distance += moves[tick];
            }
            Vec3 position = new Vec3(minX + HALF_WIDTH - distance, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            SprintE sprintE = enable(f);
            f.player.xRot = WEST;
            f.input(NONE);
            PredictionResult rest = f.tick(position, true);
            assertTrue(exact(rest), describe(rest));

            double velocityZ = 0.0;
            for (int tick = 0; tick <= runUp; tick++) {
                if (tick == runUp) open(f, box, state);
                f.input(SPRINT);
                if (tick == 0) command(f, sprintE, PlayerCommandAction.START_SPRINTING);
                double moveZ = velocityZ + ground.z;
                velocityZ = moveZ * GROUND_FRICTION;
                position = new Vec3(position.x + moves[tick], FLOOR_Y, position.z + moveZ);
                if (tick == runUp) {
                    return f.tick(position.add(sidewaysShove(0.0F, 0.1F), 0.0, 0.0), onGround);
                }
                PredictionResult run = f.tick(position, true);
                assertTrue(exact(run), describe(run));
            }
            throw new AssertionError();
        }
    }

    /** A lid that opens beside the player shoves nothing, so the hard collision stops the sprint. */
    @Test
    void aPlayerBesideAnOpeningLidStopsSprinting() throws Exception {
        try (var f = new JavaTickFixture()) {
            BlockPos box = new BlockPos(3, FLOOR_Y, 0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 1.0 - 0.3F);
            f.place(position, Vec3.ZERO);
            SprintE sprintE = wall(f, box, FLOOR_Y);
            open(f, box);

            Vec3 ground = ClientMoveInput.of(f, SPRINT, 1.0F, GROUND_SPEED, YAW);
            f.input(SPRINT);
            command(f, sprintE, PlayerCommandAction.START_SPRINTING);
            position = position.add(ground.x, 0.0, 0.0);
            PredictionResult blocked = f.tick(position, true);
            assertTrue(exact(blocked), describe(blocked));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(blocked), describe(blocked));

            position = position.add(ground.x * GROUND_FRICTION + ground.x, 0.0, 0.0);
            PredictionResult kept = f.tick(position, true);
            assertTrue(exact(kept), describe(kept));
            assertEquals(1.0, sprintE.violations);
        }
    }

    /**
     * Places the upward shulker box at {@code box} and a wall at z = 1 up to {@code bottom + 1}, puts the placed player
     * on the ground at the test's yaw, and enables SprintE.
     */
    private static SprintE wall(JavaTickFixture f, BlockPos box, int bottom) {
        f.player.compensatedWorld.updateBlock(
                box, REGISTRY.block("minecraft:shulker_box").defaultState());
        for (int x = -8; x <= 8; x++) {
            for (int y = FLOOR_Y; y <= bottom + 1; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 1), BlockIds.STONE.defaultState());
            }
        }
        return enable(f);
    }

    /** Places the east-facing shulker box at {@code box} and a wall at x = 2, like {@link #wall}. */
    private static SprintE sideWall(JavaTickFixture f, BlockPos box) {
        f.player.compensatedWorld.updateBlock(
                box, REGISTRY.with(REGISTRY.block("minecraft:shulker_box").defaultState(), "facing", "east"));
        for (int z = -1; z <= 1; z++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(2, y, z), BlockIds.STONE.defaultState());
            }
        }
        return enable(f);
    }

    /** Puts the placed player on the ground at the test's yaw and enables SprintE, once the blocks are in place. */
    private static SprintE enable(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.xRot = YAW;
        f.player.yRot = 0.0F;
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.player.setExperimentalChecks(true);
        SprintE sprintE = f.player.checkManager.getCheck(SprintE.class);
        sprintE.violations = 0;
        return sprintE;
    }

    /**
     * The movement of ShulkerBoxBlockEntity#moveCollidedEntities east from {@code progressOld} to {@code progress}:
     * the width of Shulker#getProgressDeltaAabb plus 0.01.
     */
    private static double sidewaysShove(float progressOld, float progress) {
        return (1.0 + (double) progress) - (1.0 + (double) progressOld) + 0.01;
    }

    /** The block event that opens the box, handled before the next client tick. */
    private static void open(JavaTickFixture f, BlockPos box) {
        open(f, box, REGISTRY.block("minecraft:shulker_box").defaultState());
    }

    private static void open(JavaTickFixture f, BlockPos box, int state) {
        var packet = new ClientboundBlockEvent(box, 1, 1, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /**
     * The player's feet on the lid at {@code y} after ShulkerBoxBlockEntity#moveCollidedEntities from
     * {@code progressOld} to {@code progress}: up by the height of Shulker#getProgressDeltaAabb plus 0.01.
     */
    private static double shove(double y, float progressOld, float progress) {
        double bottom = FLOOR_Y + 1.0 + (double) progressOld;
        double top = FLOOR_Y + 1.0 + (double) progress;
        return y + top - bottom + 0.01;
    }

    private static void command(JavaTickFixture f, SprintE sprintE, PlayerCommandAction action) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        PacketReceiveEvent<ServerboundPlayerCommand> event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        new PacketEntityAction().onPlayerCommand(event, f.player, packet);
        sprintE.onPlayerCommand(event, f.player, packet);
    }
}
