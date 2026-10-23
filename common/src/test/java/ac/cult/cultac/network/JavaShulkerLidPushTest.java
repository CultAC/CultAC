package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketServerTickingState;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTickingState;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class JavaShulkerLidPushTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double FALL = 0.0784000015258789;
    private static final BlockPos BOX = new BlockPos(0, FLOOR_Y, 0);

    /**
     * A 26.1 client standing on a shulker box while it opens. Each ShulkerBoxBlockEntity#updateAnimation after
     * LocalPlayer#sendPosition raises the progress by 0.1F and moves the player up by the height of
     * Shulker#getProgressDeltaAabb plus 0.01, while the lid it lands on is ShulkerBoxBlockEntity#getBoundingBox at half
     * the progress. A teleport lifts the player above the lid, so a shove is followed by a fall past the lid.
     */
    @Test
    void aShovedPlayerFallingPastAnOpeningShulkerLidIsNotOnGround() throws Exception {
        rideOpeningLid(ClientVersion.V_26_1, 6, 0, true, -1, 0, false);
    }

    /** The last shove of the opening, at progress 1.0F, is reported with the landing on the fully open lid. */
    @Test
    void aShovedPlayerLandsOnTheFullyOpenShulkerLid() throws Exception {
        rideOpeningLid(ClientVersion.V_26_1, 14, 0, true, -1, 0, false);
    }

    /**
     * ShulkerBoxBlockEntity#triggerEvent runs when the client handles the block event, and the first
     * ShulkerBoxBlockEntity#updateAnimation follows in that client tick, however many ticks after the server sent it.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void theLidOpensFromTheClientTickThatHandlesItsBlockEvent(int ticksBeforeHandled) throws Exception {
        rideOpeningLid(ClientVersion.V_26_1, 14, ticksBeforeHandled, true, -1, 0, false);
        rideOpeningLid(ClientVersion.V_26_1, 14, ticksBeforeHandled, false, -1, 0, false);
    }

    /**
     * The tenth ShulkerBoxBlockEntity#updateAnimation is the last that moves the player: 0.11 up onto a lid whose top
     * is 0.5 above the box. Before 26.3 that shove reaches the server with the next packet, whose landing is on the
     * fully open lid, so a player reported higher on the ground is not on any lid.
     */
    @ParameterizedTest
    @CsvSource({"10, 65.9", "10, 65.56", "11, 65.9", "11, 65.56"})
    void aLandingAboveTheOpeningLidIsRejected(int tick, double y) throws Exception {
        JavaTickFixture.assertRejected(
                rideOpeningLid(ClientVersion.V_26_1, 14, 0, false, tick, y, true), "VerticalOffset");
    }

    /** From 26.3 Minecraft#tick sends the position after ClientLevel#tickBlockEntities, so it carries the shove. */
    @Test
    void aShulkerBoxShovesA26_3PlayerAfterItsTravel() throws Exception {
        rideOpeningLid(ClientVersion.V_26_3, 14, 0, false, -1, 0, false);
    }

    /**
     * From 26.3 the shove is the tick's last Entity#move, and the local player's Entity#move sets onGround from its own
     * vertical collision below: none for a shove up.
     */
    @Test
    void aShoved26_3PlayerIsNotOnGround() throws Exception {
        JavaTickFixture.assertRejected(
                rideOpeningLid(ClientVersion.V_26_3, 14, 0, false, 1, Double.NaN, true), "GroundSpoof");
    }

    /**
     * Level#tickBlockEntities ticks nothing while TickRateManager#runsNormally is false, which Minecraft#tick sets from
     * the frozen state before the client tick's block entities run, so a frozen lid neither opens nor shoves.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_3"})
    void aFrozenShulkerBoxDoesNotMoveItsRider(ClientVersion version) throws Exception {
        new Ride(version).freeze(4).run(9);
    }

    /** A frozen lid gives no shove, so a rider still moving up by one is not riding it. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_3"})
    void aRiderMovedUpByAFrozenShulkerBoxIsRejected(ClientVersion version) throws Exception {
        double shove = pushTop(0.4F) - pushTop(0.3F) + 0.01;
        boolean shoveAfterTravel = version.isNewerThanOrEquals(ClientVersion.V_26_3);
        double y = lidTop(0.4F) + shove - (shoveAfterTravel ? 0.0 : FALL);
        JavaTickFixture.assertRejected(
                new Ride(version).freeze(4).fake(5, y, false).run(9), "VerticalOffset");
    }

    /**
     * ShulkerBoxBlockEntity#triggerEvent sets OPENING whenever the open count becomes 1, also on an open lid, and the
     * next ShulkerBoxBlockEntity#updateAnimation calls moveCollidedEntities with Shulker#getProgressDeltaAabb from 1.0F
     * to 1.0F: a box of no height that still shoves its rider by 0.01.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_3"})
    void aSecondViewerClosingTheBoxShovesItsRider(ClientVersion version) throws Exception {
        new Ride(version).open(13, 2).open(15, 1).run(20);
    }

    /**
     * ShulkerBoxBlockEntity#moveCollidedEntities either moves an entity its box intersects by the whole step or does
     * not move it, so a 26.3 position is the travel's end or that end plus the step.
     */
    @ParameterizedTest
    @CsvSource({"65.12, false, VerticalOffset", "65.1, false, VerticalOffset"})
    void aFallingPlayerIsShovedByTheWholeStepOrNotAtAll(double y, boolean onGround, String check) throws Exception {
        JavaTickFixture.assertRejected(
                new Ride(ClientVersion.V_26_3)
                        .start(65.3, -0.1, false)
                        .fake(0, y, onGround)
                        .run(3),
                check);
    }

    /** The first 26.3 step shoves a rider standing on the closed lid up, which leaves it off the ground. */
    @Test
    void aRiderTheFirstStepShovesIsNotOnGround() throws Exception {
        JavaTickFixture.assertRejected(
                new Ride(ClientVersion.V_26_3).fake(0, Double.NaN, true).run(2), "GroundSpoof");
    }

    /**
     * Before 26.3 the shove is the Entity#move before the travel, and the local player's Entity#move sets onGround
     * from its own vertical collision below: none for a shove up, so LivingEntity#aiStep does not jump.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void aRiderTheLidShovesUpDoesNotJump(int tick) throws Exception {
        JavaTickFixture.assertRejected(new Ride(ClientVersion.V_26_1).jump(tick).run(tick + 3), "VerticalOffset");
    }

    /**
     * Entity#move collides a shulker box shove like any other movement: a box opening east shoves a 26.1 player
     * standing beside it no further than the wall, and every shove leaves the player off the ground for the travel.
     */
    @Test
    void aShoveIntoAWallStopsAtTheWall() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
            int box = REGISTRY.with(REGISTRY.block("minecraft:shulker_box").defaultState(), "facing", "east");
            int stone = REGISTRY.block("minecraft:stone").defaultState();
            f.player.compensatedWorld.updateBlock(BOX, box);
            f.player.compensatedWorld.updateBlock(new BlockPos(2, FLOOR_Y, 0), stone);
            f.player.compensatedWorld.updateBlock(new BlockPos(2, FLOOR_Y + 1, 0), stone);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            double halfWidth = (double) (0.6F / 2.0F);
            double x = 1.0 + halfWidth;
            f.place(new Vec3(x, FLOOR_Y, 0.5), new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(new Vec3(x, FLOOR_Y, 0.5), true));
            blockEvent(f, BOX, 1, 1, box, true);
            float progress = 0.0F;
            for (int tick = 0; tick < 7; tick++) {
                f.input(NONE);
                assertExact(f.tick(new Vec3(x, FLOOR_Y, 0.5), true));
                float progressOld = progress;
                progress += 0.1F;
                double minX = 1.0 + (double) progressOld;
                double maxX = 1.0 + (double) progress;
                if (x - halfWidth < maxX && x + halfWidth > minX)
                    x += Math.min(maxX - minX + 0.01, 2.0 - (x + halfWidth));
            }
        }
    }

    /**
     * A vanilla client standing on an upward shulker box at {@link #BOX}: LivingEntity#travel falling onto the lid,
     * ShulkerBoxBlockEntity#triggerEvent at the start of the client tick that handles each block event, and
     * ShulkerBoxBlockEntity#updateAnimation with moveCollidedEntities in each client tick whose block entities run.
     */
    private static final class Ride {
        private final ClientVersion version;
        private final Map<Integer, Integer> openCounts = new HashMap<>(Map.of(0, 1));
        private double y = lidTop(0.0F);
        private double velocity = -FALL;
        private boolean onGround = true;
        private int freezeTick = -1;
        private int jumpTick = -1;
        private int fakeTick = -1;
        private double fakeY;
        private boolean fakeOnGround;
        private String status = "CLOSED";
        private float progress;
        private float progressOld;
        private boolean frozen;

        Ride(ClientVersion version) {
            this.version = version;
        }

        Ride start(double y, double velocity, boolean onGround) {
            this.y = y;
            this.velocity = velocity;
            this.onGround = onGround;
            return this;
        }

        /** The block event setting the open count to {@code openCount}, handled at the start of {@code tick}. */
        Ride open(int tick, int openCount) {
            openCounts.put(tick, openCount);
            return this;
        }

        /** The ticking state freezing the game, handled at the start of {@code tick}. */
        Ride freeze(int tick) {
            freezeTick = tick;
            return this;
        }

        /** At {@code tick} the client reports a jump from where vanilla leaves it. */
        Ride jump(int tick) {
            jumpTick = tick;
            return this;
        }

        /** At {@code tick} the client reports {@code y}, or vanilla's height when NaN, and {@code onGround}. */
        Ride fake(int tick, double y, boolean onGround) {
            fakeTick = tick;
            fakeY = y;
            fakeOnGround = onGround;
            return this;
        }

        /** Asserts every vanilla tick exact and returns the jumping or fake tick's result, else null. */
        PredictionResult run(int ticks) throws Exception {
            try (var f = new JavaTickFixture()) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                boolean shoveAfterTravel = version.isNewerThanOrEquals(ClientVersion.V_26_3);
                int box = REGISTRY.block("minecraft:shulker_box").defaultState();
                f.player.compensatedWorld.updateBlock(BOX, box);
                f.place(new Vec3(0.5, y, 0.5), new Vec3(0, velocity, 0));
                f.player.onGround = f.player.lastOnGround = onGround;
                f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(onGround));
                travel();
                f.input(NONE);
                assertExact(f.tick(new Vec3(0.5, y, 0.5), onGround));
                for (int tick = 0; tick < ticks; tick++) {
                    Integer openCount = openCounts.get(tick);
                    if (openCount != null) {
                        blockEvent(f, BOX, 1, openCount, box, true);
                        if (openCount == 0) status = "CLOSING";
                        if (openCount == 1) status = "OPENING";
                    }
                    if (tick == freezeTick) {
                        tickingState(f, true);
                        frozen = true;
                    }
                    if (tick == jumpTick) {
                        f.input(JUMP);
                        return f.tick(new Vec3(0.5, y + (double) 0.42F, 0.5), false);
                    }
                    travel();
                    if (shoveAfterTravel) updateAnimation();
                    f.input(NONE);
                    if (tick == fakeTick)
                        return f.tick(new Vec3(0.5, Double.isNaN(fakeY) ? y : fakeY, 0.5), fakeOnGround);
                    assertExact(f.tick(new Vec3(0.5, y, 0.5), onGround));
                    if (!shoveAfterTravel) updateAnimation();
                }
            }
            return null;
        }

        /** LivingEntity#travel with no input: gravity, landing on the lid at its current progress. */
        private void travel() {
            double lid = lidTop(progress);
            if (y >= lid && y + velocity <= lid) {
                y = lid;
                onGround = true;
                velocity = (0.0 - 0.08) * (double) 0.98F;
            } else {
                y += velocity;
                onGround = false;
                velocity = (velocity - 0.08) * (double) 0.98F;
            }
        }

        private void updateAnimation() {
            if (frozen) return;
            progressOld = progress;
            switch (status) {
                case "CLOSED" -> progress = 0.0F;
                case "OPENED" -> progress = 1.0F;
                case "OPENING" -> {
                    progress += 0.1F;
                    if (progress >= 1.0F) {
                        status = "OPENED";
                        progress = 1.0F;
                    }
                    double bottom = pushTop(Math.min(progressOld, progress));
                    double top = pushTop(Math.max(progressOld, progress));
                    if (y < top && y + (double) 1.8F > bottom) {
                        y += top - bottom + 0.01;
                        onGround = false;
                    }
                }
                case "CLOSING" -> {
                    progress -= 0.1F;
                    if (progress <= 0.0F) {
                        status = "CLOSED";
                        progress = 0.0F;
                    }
                }
                default -> throw new IllegalStateException(status);
            }
        }
    }

    private static void tickingState(JavaTickFixture f, boolean frozen) {
        var packet = new ClientboundTickingState(20.0F, frozen);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TICKING_STATE, packet, false);
        new PacketServerTickingState(f.player).onTickingState(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /**
     * Rides the opening lid for {@code ticks} client ticks, the block event handled {@code ticksBeforeHandled} ticks
     * after it is sent, asserting each reported tick exact. At {@code fakeTick} the client instead reports
     * {@code fakeY}, or its own height when that is NaN, and {@code fakeOnGround}, and that tick's result is returned.
     */
    private static PredictionResult rideOpeningLid(
            ClientVersion version,
            int ticks,
            int ticksBeforeHandled,
            boolean lift,
            int fakeTick,
            double fakeY,
            boolean fakeOnGround)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            boolean shoveAfterTravel = version.isNewerThanOrEquals(ClientVersion.V_26_3);
            int box = REGISTRY.block("minecraft:shulker_box").defaultState();
            f.player.compensatedWorld.updateBlock(BOX, box);
            double y = lidTop(0.0F);
            f.place(new Vec3(0.5, y, 0.5), new Vec3(0, -FALL, 0));
            f.player.onGround = f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, y, 0.5), true));

            blockEvent(f, BOX, 1, 1, box, ticksBeforeHandled == 0);
            for (int tick = 0; tick < ticksBeforeHandled; tick++) {
                f.input(NONE);
                assertExact(f.tick(new Vec3(0.5, y, 0.5), true));
            }
            if (ticksBeforeHandled > 0) f.pongThrough(f.player.lastTransactionSent.get());

            float[] progress = {0.0F};
            double velocity = -FALL;
            for (int tick = 0; tick < ticks; tick++) {
                if (lift && tick == 2) {
                    y = BOX.getY() + 1.33;
                    velocity = 0.0;
                    f.teleport(new Vec3(0.5, y, 0.5), new RelativeFlag(0));
                }
                double lid = lidTop(progress[0]);
                boolean onGround = y + velocity <= lid;
                if (onGround) {
                    y = lid;
                    velocity = -FALL;
                } else {
                    y += velocity;
                    velocity = (velocity - 0.08) * (double) 0.98F;
                }
                if (shoveAfterTravel) {
                    double shoved = updateAnimation(progress, y);
                    if (shoved != y) onGround = false;
                    y = shoved;
                }
                f.input(NONE);
                if (tick == fakeTick) return f.tick(new Vec3(0.5, Double.isNaN(fakeY) ? y : fakeY, 0.5), fakeOnGround);
                assertExact(f.tick(new Vec3(0.5, y, 0.5), onGround));
                if (!shoveAfterTravel) y = updateAnimation(progress, y);
            }
        }
        return null;
    }

    /**
     * ShulkerBoxBlockEntity#updateAnimation while OPENING, then moveCollidedEntities: the player's feet height after
     * the shove, when Shulker#getProgressDeltaAabb intersects its box.
     */
    private static double updateAnimation(float[] progress, double y) {
        if (progress[0] >= 1.0F) return y;
        float progressOld = progress[0];
        progress[0] = Math.min(progress[0] + 0.1F, 1.0F);
        double bottom = pushTop(progressOld);
        double top = pushTop(progress[0]);
        return y < top && y + (double) 1.8F > bottom ? y + top - bottom + 0.01 : y;
    }

    /** Shulker#getProgressAabb at ShulkerBoxBlockEntity#getBoundingBox's half progress, at the box's bottom center. */
    private static double lidTop(float progress) {
        return 1.0 + (double) (0.5F * progress) + BOX.getY();
    }

    /** The top of Shulker#getProgressDeltaAabb for {@code progress}, at the box's bottom center. */
    private static double pushTop(float progress) {
        return 1.0 + (double) progress + BOX.getY();
    }

    private static void blockEvent(
            JavaTickFixture f, BlockPos pos, int action, int parameter, int state, boolean handled) {
        var packet = new ClientboundBlockEvent(pos, action, parameter, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        if (handled) f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
