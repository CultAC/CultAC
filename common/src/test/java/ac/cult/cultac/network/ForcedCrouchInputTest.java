package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#aiStep crouches where the crouching pose fits and the standing pose does not, without shift, and
 * LocalPlayer#isMovingSlowly (crouching or Entity#isVisuallyCrawling) scales the keys' input by sneaking_speed.
 */
class ForcedCrouchInputTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double FRICTION = 0.6F * 0.91F;
    private static final float YAW = 30.0F;
    private static final float WALK = 0.1F;
    private static final float SPRINT_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final int BOAT = 100;
    private static final Vec3 BOAT_AT = new Vec3(0.5, FLOOR_Y + 1.6, 0.5);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final int TOP_SLAB = DataTables.defaults()
            .registry()
            .with(DataTables.defaults().registry().block("minecraft:stone_slab").defaultState(), "type", "top");

    @Test
    void aCrawlAtTheUnscaledWalkingInputFlags() throws Exception {
        try (var f = under(BlockIds.STONE.defaultState())) {
            Vec3[] state = crawlEntry(f);
            assertFlags(f, state[0], state[1]);
        }
    }

    @Test
    void aForcedCrouchUnderAOneAndAHalfCeilingAtTheUnscaledWalkingInputFlags() throws Exception {
        try (var f = under(TOP_SLAB)) {
            assertFlags(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
        }
    }

    @Test
    void aCrawlAtTheSneakingScaleIsExact() throws Exception {
        try (var f = under(BlockIds.STONE.defaultState())) {
            Vec3[] state = crawlEntry(f);
            walk(f, state[0], state[1], 0.3F, 10, "crawl");
        }
    }

    /** Entity#updatePlayerPose runs after aiStep, so the tick entering a crawl space still walks at full input. */
    @Test
    void theCrawlEntryTickIsUnscaled() throws Exception {
        try (var f = under(BlockIds.STONE.defaultState())) {
            crawlEntry(f);
        }
    }

    @Test
    void aForcedCrouchAtTheSneakingScaleIsExact() throws Exception {
        try (var f = under(TOP_SLAB)) {
            walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 0.3F, 10, "crouch");
        }
    }

    /** The client handles a block update in the bundle task whose ping it answers, so the ceiling stays until then. */
    @Test
    void aCeilingRemovalInFlightKeepsTheCrouchUntilItsPong() throws Exception {
        try (var f = under(TOP_SLAB)) {
            Vec3[] state = walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 0.3F, 3, "before");
            f.player.sendTransaction();
            int removal = f.player.lastTransactionSent.get();
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.queueServerBlockUpdate(
                            x, FLOOR_Y + 1, z, BlockIds.AIR.defaultState(), removal);
                }
            }
            state = walk(f, state[0], state[1], 0.3F, 3, "in flight");
            f.pongThrough(removal);
            walk(f, state[0], state[1], 1.0F, 3, "proven");
        }
    }

    @Test
    void anUnscaledMoveBeforeTheCeilingRemovalsPongFlags() throws Exception {
        try (var f = under(TOP_SLAB)) {
            Vec3[] state = walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 0.3F, 3, "before");
            f.player.sendTransaction();
            int removal = f.player.lastTransactionSent.get();
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.queueServerBlockUpdate(
                            x, FLOOR_Y + 1, z, BlockIds.AIR.defaultState(), removal);
                }
            }
            Vec3 delta = state[1].add(ClientMoveInput.of(f, FORWARD, 1.0F, WALK, YAW));
            PredictionResult result = f.tick(state[0].add(delta.x, 0.0, delta.z), true);
            assertFalse(result.getFlags().isEmpty(), describe(result));
        }
    }

    /** The client applies a ceiling block once it handles the update, before the ping after it, so it walks until. */
    @Test
    void aCeilingPlacedInFlightLeavesTheWalkUntilItsPong() throws Exception {
        try (var f = under(BlockIds.AIR.defaultState())) {
            Vec3[] state = walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 1.0F, 2, "open");
            f.player.sendTransaction();
            int placed = f.player.lastTransactionSent.get();
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.queueServerBlockUpdate(x, FLOOR_Y + 1, z, TOP_SLAB, placed);
                }
            }
            state = walk(f, state[0], state[1], 1.0F, 3, "in flight");
            f.pongThrough(placed);
            walk(f, state[0], state[1], 0.3F, 3, "proven");
        }
    }

    /**
     * MultiPlayerGameMode#destroyBlock predicts the mined ceiling block as air before the move, so the next aiStep
     * finds room to stand and walks at full input.
     */
    @Test
    void aMinedCeilingBlockLetsTheNextTickStand() throws Exception {
        try (var f = under(TOP_SLAB)) {
            f.place(new Vec3(0.5, FLOOR_Y, 0.3), Vec3.ZERO);
            Vec3[] state = walk(f, new Vec3(0.5, FLOOR_Y, 0.3), Vec3.ZERO, 0.3F, 2, "under");
            mineAbove(f, 1);
            walk(f, state[0], state[1], 1.0F, 2, "mined");
        }
    }

    /** BlockStatePredictionHandler keeps a refused break's air until ClientLevel#handleBlockChangedAck. */
    @Test
    void aRefusedMinedCeilingBlockLeavesTheWalkUprightUntilTheAcknowledgement() throws Exception {
        try (var f = under(TOP_SLAB)) {
            f.place(new Vec3(0.5, FLOOR_Y, 0.3), Vec3.ZERO);
            Vec3[] state = walk(f, new Vec3(0.5, FLOOR_Y, 0.3), Vec3.ZERO, 0.3F, 2, "under");
            mineAbove(f, 1);
            state = walk(f, state[0], state[1], 1.0F, 1, "mined");
            f.player.sendTransaction();
            int refusal = f.player.lastTransactionSent.get();
            f.player.compensatedWorld.queueServerBlockUpdate(0, FLOOR_Y + 1, 0, TOP_SLAB, refusal);
            f.player.compensatedWorld.handlePredictionConfirmation(1, refusal);
            walk(f, state[0], state[1], 1.0F, 2, "refusal in flight");
        }
    }

    /** A sprint held into a crawl space keeps the sprinting speed, scaled by sneaking_speed once the pose updates. */
    @Test
    void aSprintCarriedIntoACrawlIsExact() throws Exception {
        try (var f = under(BlockIds.STONE.defaultState())) {
            f.input(SPRINT);
            f.command(PlayerCommandAction.START_SPRINTING);
            Vec3[] state = walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 1.0F, SPRINT_SPEED, 1, "entry");
            walk(f, state[0], state[1], 0.3F, SPRINT_SPEED, 5, "crawl");
        }
    }

    /** Player#canPlayerFitWithinBlocksAndEntitiesWhen counts a boat over the head like a block. */
    @Test
    void aBoatCeilingForcesTheCrouch() throws Exception {
        try (var f = underABoat()) {
            walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 0.3F, 2, "under the boat");
        }
    }

    /**
     * The boat's InterpolationHandler steps toward a position sync in the boat's own tick, which follows the local
     * player's, so the player crouches through the tick that handles the sync and stands once the boat has moved.
     */
    @Test
    void aBoatCeilingMovingAwayLetsThePlayerStandOnceTheBoatSteps() throws Exception {
        try (var f = underABoat()) {
            positionSyncInFlight(f, BOAT_AT.add(5.0, 0.0, 0.0));
            f.pongThrough(f.player.sendTrailingTransaction());
            Vec3[] state = walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 0.3F, 1, "sync tick");
            walk(f, state[0], state[1], 1.0F, 2, "upright");
        }
    }

    @Test
    void aBoatCeilingMovingAwayInFlightMayKeepTheCrouch() throws Exception {
        try (var f = underABoat()) {
            positionSyncInFlight(f, BOAT_AT.add(5.0, 0.0, 0.0));
            walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 0.3F, 2, "crouched");
        }
    }

    /**
     * A sticky piston pulls the ceiling block up. ClientLevel#tickEntities ticks the player before the moving block
     * entity, so the tick of the block event still crouches and the next one stands.
     */
    @Test
    void aCeilingPulledAwayByAPistonLetsTheNextTickStand() throws Exception {
        try (var f = under(BlockIds.AIR.defaultState())) {
            BlockPos piston = new BlockPos(0, FLOOR_Y + 3, 0);
            int stickyPiston = REGISTRY.with(
                    REGISTRY.with(REGISTRY.block("minecraft:sticky_piston").defaultState(), "facing", "down"),
                    "extended",
                    "true");
            int head = REGISTRY.with(
                    REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", "down"),
                    "type",
                    "sticky");
            f.player.compensatedWorld.updateBlock(piston, stickyPiston);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 2, 0), head);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), TOP_SLAB);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3[] state = walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 0.3F, 2, "under");
            JavaPistonSlimeLaunchTest.blockEvent(f, piston, 1, Direction.DOWN.get3DDataValue(), stickyPiston);
            state = walk(f, state[0], state[1], 0.3F, 1, "event tick");
            walk(f, state[0], state[1], 1.0F, 2, "pulled away");
        }
    }

    /** An open fixture with a boat whose bottom is above the crouching box's top and below the standing box's. */
    private static JavaTickFixture underABoat() throws Exception {
        var f = under(BlockIds.AIR.defaultState());
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
        return f;
    }

    private static void positionSyncInFlight(JavaTickFixture f, Vec3 at) {
        var sync = new ClientboundEntityPositionSync(
                BOAT, new PositionPath.Linear(new Vec3d(at.x, at.y, at.z)), 0.0F, 0.0F, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, sync, false);
        f.player.packetEntityReplication.onEntityPositionSync(event, f.player, sync);
        event.tasksAfter().forEach(Runnable::run);
    }

    /** Mines the ceiling block over the origin under the client's prediction {@code sequence}. */
    private static void mineAbove(JavaTickFixture f, int sequence) {
        CompensatedWorld world = f.player.compensatedWorld;
        world.advanceClientPredictionSequence(sequence);
        world.startPredicting();
        world.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), BlockIds.AIR.defaultState());
        world.stopPredicting(sequence);
    }

    private static Vec3[] crawlEntry(JavaTickFixture f) {
        return walk(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 1.0F, 1, "entry");
    }

    private static void assertFlags(JavaTickFixture f, Vec3 position, Vec3 velocity) {
        Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD, 1.0F, WALK, YAW));
        PredictionResult result = f.tick(position.add(delta.x, 0.0, delta.z), true);
        assertFalse(result.getFlags().isEmpty(), describe(result));
    }

    /** {@code ticks} forward ground moves at {@code factor}, each exact; the position and carried velocity. */
    private static Vec3[] walk(JavaTickFixture f, Vec3 position, Vec3 velocity, float factor, int ticks, String label) {
        return walk(f, position, velocity, factor, WALK, ticks, label);
    }

    private static Vec3[] walk(
            JavaTickFixture f, Vec3 position, Vec3 velocity, float factor, float speed, int ticks, String label) {
        for (int tick = 0; tick < ticks; tick++) {
            Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD, factor, speed, YAW));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult result = f.tick(position, true);
            assertTrue(exact(result), label + " tick " + tick + " pose " + result.getSimulationContext().getPose()
                    + ": " + describe(result));
            velocity = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
        }
        return new Vec3[] {position, velocity};
    }

    private static JavaTickFixture under(int ceiling) throws Exception {
        var f = new JavaTickFixture();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 1, z), ceiling);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
        f.player.xRot = YAW;
        f.input(FORWARD);
        return f;
    }
}
