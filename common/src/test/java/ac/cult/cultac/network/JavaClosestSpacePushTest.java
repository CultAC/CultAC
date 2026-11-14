package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A still 26.3 client whose box overlaps stone. LocalPlayer#aiStep probes four points 0.35 of the box's width from its
 * centre; each one inside a suffocating block sets deltaMovement on one axis to 0.1 toward the closest side that does
 * not suffocate, and a later probe replaces an earlier push on the same axis.
 */
class JavaClosestSpacePushTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final float GLIDE_YAW = 160.0F, GLIDE_PITCH = -18.0F;

    /** Stone around and in the player's cell on its west, north and south, open to the east. */
    @ParameterizedTest
    @CsvSource({"0.1, 0.0, true", "-0.1, 0.1, false", "0.0, -0.1, false", "-0.1, -0.1, false", "0.1, 0.1, false"})
    void aPlayerWalledInOnThreeSidesIsPushedTowardTheOpenOne(double dx, double dz, boolean vanilla) throws Exception {
        try (var f = still()) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                for (int x = -1; x <= 0; x++) {
                    for (int z = -1; z <= 1; z++) stone(f, x, y, z);
                }
            }
            settle(f);
            PredictionResult result = f.tick(START.add(dx, 0.0, dz), true);
            assertTrue(exact(result) == vanilla, describe(result));
        }
    }

    /**
     * One stone block at the feet, the box centred on it: the west probes push west, the east probes push east, and
     * the last probe's east push is the one the move keeps.
     */
    @ParameterizedTest
    @CsvSource({"0.1, true", "-0.1, false", "0.0, false"})
    void theLastProbeDecidesThePushOnItsAxis(double dx, boolean vanilla) throws Exception {
        try (var f = still()) {
            stone(f, 0, FLOOR_Y, 0);
            settle(f);
            PredictionResult result = f.tick(START.add(dx, 0.0, 0.0), true);
            assertTrue(exact(result) == vanilla, describe(result));
        }
    }

    /** A closed shulker box suffocates like stone, so the last probe's east push is the move. */
    @ParameterizedTest
    @CsvSource({"0.1, true", "0.0, false"})
    void aClosedShulkerBoxPushesLikeStone(double dx, boolean vanilla) throws Exception {
        try (var f = still()) {
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 0),
                    DataTables.defaults().registry().block("minecraft:shulker_box").defaultState());
            settle(f);
            PredictionResult result = f.tick(START.add(dx, 0.0, 0.0), true);
            assertTrue(exact(result) == vanilla, describe(result));
        }
    }

    /**
     * A shift-holding client on a floor's east rim whose feet cell fills with stone, walled on its other sides: the
     * push points east at the drop, and Player#maybeBackOffFromEdge shortens the pushed delta in the same travel.
     */
    @ParameterizedTest
    @CsvSource({
        "true, 0.0, 0.0, true, true",
        "false, 0.1, -0.0784000015258789, false, true",
        "false, 0.0, 0.0, true, false"
    })
    void aPushTowardTheDropIsBackedOffWhileShiftIsHeld(
            boolean shift, double dx, double dy, boolean onGround, boolean vanilla) throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = 1; x <= 3; x++) {
                for (int z = -2; z <= 2; z++) air(f, x, FLOOR_Y - 1, z);
            }
            settle(f);
            Vec3 rim = new Vec3(1.27, FLOOR_Y, 0.5);
            f.place(rim, Vec3.ZERO);
            JavaTickFixture.setField(
                    SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", shift);
            f.input(shift ? SNEAK : NONE);
            PredictionResult first = f.tick(rim, true);
            assertTrue(exact(first), describe(first));
            stone(f, 1, FLOOR_Y, 0);
            stone(f, 0, FLOOR_Y, 0);
            stone(f, 1, FLOOR_Y, -1);
            stone(f, 1, FLOOR_Y, 1);
            settle(f);
            PredictionResult result = f.tick(rim.add(dx, dy, 0.0), onGround);
            assertTrue(exact(result) == vanilla, describe(result));
        }
    }

    /**
     * A block the server sets in the feet cell, such as landing gravel, reaches the client with its ping: until the
     * pong the client stands still, and the tick after it moves by the push.
     */
    @ParameterizedTest
    @CsvSource({"stone, 0, false", "stone, 1, false", "gravel, 16, false", "stone, 1, true", "gravel, 16, true"})
    void aServerBlockInTheFeetPushesFromItsPong(String block, int pendingTicks, boolean pushedEarly)
            throws Exception {
        try (var f = still()) {
            int state = DataTables.defaults().registry().block("minecraft:" + block).defaultState();
            int transaction = f.player.sendTrailingTransaction();
            f.player.compensatedWorld.queueServerBlockUpdate(0, FLOOR_Y, 0, state, transaction);
            for (int tick = 0; tick < pendingTicks; tick++) {
                PredictionResult still = f.tick(START, true);
                assertTrue(exact(still), tick + ": " + describe(still));
            }
            if (pushedEarly) {
                PredictionResult early = f.tick(START.add(0.1, 0.0, 0.0), true);
                assertFalse(exact(early), describe(early));
                return;
            }
            f.pongThrough(transaction);
            PredictionResult pushed = f.tick(START.add(0.1, 0.0, 0.0), true);
            assertTrue(exact(pushed), describe(pushed));
        }
    }

    /** An opening or open shulker box does not suffocate, so it neither pushes nor holds the player in a block. */
    @ParameterizedTest
    @CsvSource({"1, 0", "1, 12", "0, 12"})
    void aShulkerBoxThatIsNotClosedDoesNotPush(int openAction, int animationTicks) throws Exception {
        try (var f = still()) {
            BlockPos at = new BlockPos(0, FLOOR_Y, 0);
            int box = DataTables.defaults().registry().block("minecraft:shulker_box").defaultState();
            f.player.compensatedWorld.updateBlock(at, box);
            settle(f);
            f.place(new Vec3(5.5, FLOOR_Y, 0.5), Vec3.ZERO);
            f.tick(new Vec3(5.5, FLOOR_Y, 0.5), true);
            blockEvent(f, at, 1, 1, box, true);
            for (int tick = 0; tick < animationTicks; tick++) f.tickEnd();
            if (openAction == 0) blockEvent(f, at, 1, 0, box, true);
            assertFalse(Collisions.doesBlockSuffocate(f.player, 0, FLOOR_Y, 0));
            f.place(START, Vec3.ZERO);
            PredictionResult inside = f.tick(START, true);
            assertFalse(inside.getSimulationContext().getWorldData().isMightBeInBlock(), describe(inside));
            if (openAction == 1 && animationTicks > 0) {
                assertTrue(exact(inside), describe(inside));
                PredictionResult pushed = f.tick(START.add(0.1, 0.0, 0.0), true);
                assertFalse(exact(pushed), describe(pushed));
            }
        }
    }

    /**
     * A gliding client centred on one stone block in the air: LocalPlayer#aiStep's last probe sets deltaMovement x to
     * 0.1 before LivingEntity#travelFallFlying turns that velocity toward the look, so the glide moves by the pushed
     * velocity's glide, not by the push itself.
     */
    @Test
    void aGlideStartsFromThePushedVelocity() throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_3);
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            f.player.xRot = GLIDE_YAW;
            f.player.yRot = GLIDE_PITCH;
            var look = ReachUtils.getLook(f.player, GLIDE_YAW, GLIDE_PITCH);
            Vec3 from = new Vec3(0.5, FLOOR_Y + 20, 0.5);
            f.place(from, Vec3.ZERO);
            Vec3 carried = ElytraTransform.getElytraMovement(f.player, Vec3.ZERO, look, GLIDE_PITCH, 0.08);
            Vec3 at = from.add(carried);
            PredictionResult first = f.tick(at, false);
            assertTrue(exact(first), describe(first));
            stone(f, 0, (int) Math.floor(at.y), 0);
            settle(f);
            Vec3 pushed = new Vec3(0.1, carried.y, carried.z);
            Vec3 move = ElytraTransform.getElytraMovement(f.player, pushed, look, GLIDE_PITCH, 0.08);
            PredictionResult result = f.tick(at.add(move), false);
            assertTrue(exact(result), describe(result));
        }
    }

    /** Without stone the same still client cannot move a tenth of a block. */
    @ParameterizedTest
    @CsvSource({"0.1, 0.0", "0.0, -0.1"})
    void outsideBlocksNoPushMoves(double dx, double dz) throws Exception {
        try (var f = still()) {
            PredictionResult result = f.tick(START.add(dx, 0.0, dz), true);
            assertFalse(exact(result), describe(result));
        }
    }

    private static JavaTickFixture still() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(START, Vec3.ZERO);
        f.input(NONE);
        PredictionResult first = f.tick(START, true);
        assertTrue(exact(first), describe(first));
        return f;
    }

    private static void stone(JavaTickFixture f, int x, int y, int z) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.STONE.defaultState());
    }

    private static void air(JavaTickFixture f, int x, int y, int z) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.AIR.defaultState());
    }

    /** A block_event the client handles, answered by the ping after it when {@code handled}. */
    private static void blockEvent(
            JavaTickFixture f, BlockPos pos, int action, int parameter, int state, boolean handled) {
        var packet = new ClientboundBlockEvent(
                pos, action, parameter, DataTables.defaults().registry().blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        if (handled) f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }
}
