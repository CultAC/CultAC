package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Shulker box block shoves that Entity#collide clips, against a stepper of the client's own Entity#move: a 26.1 client
 * runs Level#tickBlockEntities after LocalPlayer#sendPosition, a 26.3 client before it.
 */
class JavaShulkerBoxShoveCollisionTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double HEIGHT = (double) 1.8F;
    private static final double CROUCHING_HEIGHT = (double) 1.5F;
    private static final double HALF_WIDTH = (double) (0.6F / 2.0F);
    private static final double EPSILON = 1.0E-7;

    /**
     * Stone at y=67 over a box at y=64: from progress 0.3F the shove's Entity#move clips at the ceiling, and its
     * vertical collision zeroes the vertical velocity (26.1 Block#updateEntityMovementAfterFallOn, 26.3
     * Entity#restituteMovementAfterCollisions), so the rider hovers there until the lid stops shoving.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_3"})
    void aShoveTheCeilingClipsStopsTheRidersFall(ClientVersion version) throws Exception {
        Scenario ceiling = new Scenario(version, 16);
        ceiling.stones.add(new BlockPos(0, FLOOR_Y + 3, 0));
        ceiling.run();
    }

    /** From 26.3 Entity#collide clips the shove after the travel, so the rider cannot end above the ceiling's clip. */
    @Test
    void aRiderAboveTheCeilingsClipOfTheShoveIsRejected() throws Exception {
        Scenario ceiling = new Scenario(ClientVersion.V_26_3, 4);
        ceiling.stones.add(new BlockPos(0, FLOOR_Y + 3, 0));
        ceiling.fake(3, 0.0, 0.05);
        JavaTickFixture.assertRejected(ceiling.run(), "VerticalOffset");
    }

    /**
     * A rider straddling two opening boxes takes one Entity#move per box, the second from where the first ends, and
     * the second sees the box the first left.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_3"})
    void twoOpeningShulkerBoxesShoveTheirRiderOneAfterTheOther(ClientVersion version) throws Exception {
        Scenario twoBoxes = new Scenario(version, 12);
        twoBoxes.box(new BlockPos(1, FLOOR_Y, 0), "up");
        twoBoxes.startX = 1.0;
        twoBoxes.run();
    }

    /**
     * A box facing down over a standing player shoves it down into the floor, where the shove's vertical collision
     * below lands it.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_3"})
    void aShulkerBoxFacingDownShovesItsRiderIntoTheFloor(ClientVersion version) throws Exception {
        Scenario down = new Scenario(version, 13);
        down.boxes.set(0, new Lid(new BlockPos(0, FLOOR_Y + 2, 0), "down"));
        down.startY = FLOOR_Y;
        down.run();
    }

    /**
     * A box facing down over a player standing on the floor, or falling to 0.1016 above it where the first shove lands
     * it: while the lid opens, whenever the packet's last Entity#move, the travel's or a shove's, collides below,
     * Entity#onGround is true and a packet reporting it false on the listed ticks is rejected.
     */
    @ParameterizedTest
    @CsvSource({
        "V_26_1, 0, 0 1 2 3 4 5 6 7 8 9 10",
        "V_26_1, 0.18, 0 1 2 3 4 5 6 7 8 9 10",
        "V_26_2, 0, 0 1 2 3 4 5 6 7 8 9 10",
        "V_26_2, 0.18, 0 1 2 3 4 5 6 7 8 9 10",
        "V_26_3, 0, 0 1 2 3 4 8 9 10",
        "V_26_3, 0.18, 0 1 2 3 4 8 9 10"
    })
    void aPlayerTheShoveLeavesOnTheFloorCannotReportItselfInTheAir(ClientVersion version, double height, String ticks)
            throws Exception {
        Scenario honest = downOntoTheFloor(version, height);
        honest.run();
        int grounded = 0;
        // The lid opens over ticks 0 to 9; a client before 26.3 reports each tick's shoves in the next packet.
        for (String each : ticks.split(" ")) {
            int tick = Integer.parseInt(each);
            if (!honest.grounds.get(tick)) continue;
            Scenario fake = downOntoTheFloor(version, height);
            fake.fake(tick, 0.0, 0.0);
            fake.fakeOnGround = false;
            PredictionResult result = fake.run();
            assertNotNull(result);
            assertTrue(!result.isExempt() && !result.getFlags().isEmpty(), tick + ": " + describe(result));
            grounded++;
        }
        assertTrue(grounded > 0, "no grounded tick");
    }

    private static Scenario downOntoTheFloor(ClientVersion version, double height) {
        Scenario down = new Scenario(version, 13);
        down.boxes.set(0, new Lid(new BlockPos(0, FLOOR_Y + 2, 0), "down"));
        down.startY = FLOOR_Y + height;
        if (height > 0.0) {
            down.startVelocityY = 0.0;
            down.startOnGround = false;
        }
        return down;
    }

    /** A sideways shove into a slab steps up onto it from the onGround the player's last Entity#move left. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_3"})
    void aSidewaysShoveStepsItsRiderUpASlab(ClientVersion version) throws Exception {
        Scenario step = sideways(version, FLOOR_Y, 9);
        step.slabs.add(new BlockPos(2, FLOOR_Y, 0));
        step.run();
    }

    /** A box at head height shoves a standing player sideways into the wall, where Entity#collide clips it. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_3"})
    void aHeadHeightSidewaysShoveMovesItsRiderIntoTheWall(ClientVersion version) throws Exception {
        Scenario wall = sideways(version, FLOOR_Y + 1, 8);
        wall.stones.add(new BlockPos(2, FLOOR_Y, 0));
        wall.stones.add(new BlockPos(2, FLOOR_Y + 1, 0));
        wall.run();
    }

    /** Each shove is one Entity#move of one length, so a rider reported part of the way is not shoved. */
    @ParameterizedTest
    @CsvSource({"V_26_1, 65, 1, -0.05", "V_26_1, 65, 1, 0.05", "V_26_3, 64, 2, -0.05", "V_26_3, 64, 2, 0.05"})
    void aRiderPartOfTheWayAlongASidewaysShoveIsRejected(ClientVersion version, int boxY, int tick, double dx)
            throws Exception {
        Scenario partial = sideways(version, boxY, tick + 1);
        partial.fake(tick, dx, 0.0);
        JavaTickFixture.assertRejected(partial.run(), "Strafe");
    }

    /**
     * Player#updatePlayerPose runs at the end of Player#tick, before Level#tickBlockEntities, so a rider who holds
     * shift under the ceiling is shoved at its crouching height past the standing clip.
     */
    @ParameterizedTest
    @CsvSource({"V_26_1, 2", "V_26_1, 3", "V_26_3, 2", "V_26_3, 3"})
    void aCrouchingRiderIsShovedAtItsCrouchingHeight(ClientVersion version, int shiftFrom) throws Exception {
        Scenario crouch = new Scenario(version, 13);
        crouch.stones.add(new BlockPos(0, FLOOR_Y + 3, 0));
        crouch.shiftFrom = shiftFrom;
        crouch.run();
    }

    /**
     * Level#tickBlockEntities ticks in the order LevelChunk#updateBlockEntityTicker added the block entities, not the
     * order they started opening.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_3"})
    void boxesShoveTheirRiderInTheOrderTheyWerePlaced(ClientVersion version) throws Exception {
        Scenario order = new Scenario(version, 6);
        Lid placedFirst = new Lid(new BlockPos(1, FLOOR_Y, 0), "up");
        placedFirst.openTick = 1;
        order.boxes.add(0, placedFirst);
        order.startX = 1.0;
        order.run();
    }

    /**
     * A 26.3 player lands next to an opening box while a piston's moving block passes over its head: the
     * PistonMovingBlockEntity#moveCollidedEntities and ShulkerBoxBlockEntity#moveCollidedEntities calls after the
     * travel never reach it, so the travel's Entity#move is the last to set Entity#onGround, and a landing reported in
     * the air is a lie.
     */
    @Test
    void aLandingNoShoveReachesStillSetsOnGround() throws Exception {
        landingUnderAPiston(8).run();
        Scenario landing = landingUnderAPiston(5);
        landing.fake(4, 0.0, 0.0);
        landing.fakeOnGround = false;
        PredictionResult result = landing.run();
        assertNotNull(result);
        JavaTickFixture.assertRejected(result, "GroundSpoof");
    }

    private static Scenario landingUnderAPiston(int ticks) {
        Scenario landing = new Scenario(ClientVersion.V_26_3, ticks);
        landing.boxes.set(0, new Lid(new BlockPos(1, FLOOR_Y, 0), "up"));
        landing.startY = FLOOR_Y + 1;
        landing.startVelocityY = 0.0;
        landing.startOnGround = false;
        landing.piston = new BlockPos(-2, FLOOR_Y + 2, 0);
        landing.pistonTick = 4;
        return landing;
    }

    /** A box at {@code boxY} facing east, a player standing against its east face. */
    private static Scenario sideways(ClientVersion version, int boxY, int ticks) {
        Scenario scenario = new Scenario(version, ticks);
        scenario.boxes.set(0, new Lid(new BlockPos(0, boxY, 0), "east"));
        scenario.startX = 1.0 + HALF_WIDTH;
        scenario.startY = FLOOR_Y;
        return scenario;
    }

    /** An axis-aligned box. */
    private record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        Box move(double x, double y, double z) {
            return new Box(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
        }

        boolean intersects(Box o) {
            return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ;
        }

        double min(int axis) {
            return axis == 0 ? minX : axis == 1 ? minY : minZ;
        }

        double max(int axis) {
            return axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
        }
    }

    /** A client ShulkerBoxBlockEntity, opened at {@code openTick}. */
    private static final class Lid {
        final BlockPos pos;
        final String facing;
        final int[] step;
        int openTick;
        boolean opening;
        float progress;
        float progressOld;

        Lid(BlockPos pos, String facing) {
            this.pos = pos;
            this.facing = facing;
            this.step = new int[] {
                facing.equals("east") ? 1 : 0, facing.equals("up") ? 1 : facing.equals("down") ? -1 : 0, 0
            };
        }

        /** Shulker#getProgressDeltaAabb of size 1 at the given bottom center. */
        Box delta(float from, float to, double x, double y, double z) {
            double max = Math.max(from, to);
            double min = Math.min(from, to);
            double[] low = {-0.5, 0.0, -0.5};
            double[] high = {0.5, 1.0, 0.5};
            for (int axis = 0; axis < 3; axis++) {
                double grow = step[axis] * max;
                if (grow < 0) low[axis] += grow;
                else high[axis] += grow;
                double cut = -step[axis] * (1.0 + min);
                if (cut < 0) low[axis] -= cut;
                else high[axis] -= cut;
            }
            return new Box(low[0] + x, low[1] + y, low[2] + z, high[0] + x, high[1] + y, high[2] + z);
        }

        /** ShulkerBoxBlock#getShape: ShulkerBoxBlockEntity#getBoundingBox at half the progress. */
        Box shape() {
            return delta(-1.0F, 0.5F * progress, 0.5, 0.0, 0.5).move(pos.getX(), pos.getY(), pos.getZ());
        }

        Box area() {
            return delta(progressOld, progress, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        }
    }

    /** The local player's LivingEntity#travel without input, Entity#move, and Level#tickBlockEntities. */
    private static final class Stepper {
        final boolean shoveAfterTravel;
        final List<Box> solids = new ArrayList<>();
        final List<Lid> lids = new ArrayList<>();
        double x;
        double y;
        double z = 0.5;
        double height = HEIGHT;
        double velocityX;
        double velocityY;
        boolean onGround;

        Stepper(boolean shoveAfterTravel) {
            this.shoveAfterTravel = shoveAfterTravel;
        }

        Box box() {
            return new Box(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + height, z + HALF_WIDTH);
        }

        /** Player#updatePlayerPose: crouching while shift is held, else standing where the standing box fits. */
        void updatePlayerPose(boolean shift) {
            if (shift) {
                height = CROUCHING_HEIGHT;
                return;
            }
            Box standing = new Box(
                    x - HALF_WIDTH + EPSILON,
                    y + EPSILON,
                    z - HALF_WIDTH + EPSILON,
                    x + HALF_WIDTH - EPSILON,
                    y + HEIGHT - EPSILON,
                    z + HALF_WIDTH - EPSILON);
            if (shapes().stream().noneMatch(standing::intersects)) height = HEIGHT;
        }

        List<Box> shapes() {
            List<Box> shapes = new ArrayList<>(solids);
            for (Lid lid : lids) shapes.add(lid.shape());
            return shapes;
        }

        /** Shapes#collide on one axis over full-cell and lid shapes. */
        static double collide(int axis, Box moving, List<Box> shapes, double movement) {
            for (Box shape : shapes) {
                if (Math.abs(movement) < EPSILON) return 0.0;
                boolean overlaps = true;
                for (int other = 0; other < 3; other++) {
                    if (other == axis) continue;
                    overlaps &= moving.min(other) + EPSILON < shape.max(other)
                            && moving.max(other) - EPSILON >= shape.min(other);
                }
                if (!overlaps) continue;
                if (movement > 0 && moving.max(axis) - EPSILON < shape.min(axis)) {
                    double gap = shape.min(axis) - moving.max(axis);
                    if (gap >= -EPSILON) movement = Math.min(movement, gap);
                } else if (movement < 0 && moving.min(axis) + EPSILON >= shape.max(axis)) {
                    double gap = shape.max(axis) - moving.min(axis);
                    if (gap <= EPSILON) movement = Math.max(movement, gap);
                }
            }
            return movement;
        }

        /** Entity#collideWithShapes: Y, then the longer horizontal axis, X here. */
        static double[] collideWith(double dx, double dy, double dz, Box box, List<Box> shapes) {
            double y = dy == 0 ? 0 : collide(1, box, shapes, dy);
            box = box.move(0, y, 0);
            double x = dx == 0 ? 0 : collide(0, box, shapes, dx);
            box = box.move(x, 0, 0);
            double z = dz == 0 ? 0 : collide(2, box, shapes, dz);
            return new double[] {x, y, z};
        }

        /** Entity#collide, stepping up to 0.6 from onGround or a vertical collision below. */
        double[] collide(double dx, double dy, double dz) {
            Box box = box();
            if (dx == 0 && dy == 0 && dz == 0) return new double[] {0, 0, 0};
            List<Box> shapes = shapes();
            double[] movement = collideWith(dx, dy, dz, box, shapes);
            boolean horizontal = dx != movement[0] || dz != movement[2];
            boolean landed = dy != movement[1] && dy < 0;
            if ((onGround || landed) && horizontal) {
                Box from = onGround ? box : box.move(0, movement[1], 0);
                List<Float> heights = new ArrayList<>();
                for (Box shape : shapes) {
                    for (double edge : new double[] {shape.minY(), shape.maxY()}) {
                        float height = (float) (edge - from.minY());
                        if (height >= 0 && height <= 0.6F && height != (float) movement[1] && !heights.contains(height))
                            heights.add(height);
                    }
                }
                heights.sort(Float::compare);
                for (float height : heights) {
                    double[] stepped = collideWith(dx, height, dz, from, shapes);
                    if (stepped[0] * stepped[0] + stepped[2] * stepped[2]
                            > movement[0] * movement[0] + movement[2] * movement[2]) {
                        return new double[] {stepped[0], stepped[1] - (box.minY() - from.minY()), stepped[2]};
                    }
                }
            }
            return movement;
        }

        /** Entity#move for the local player. */
        void move(double dx, double dy, double dz) {
            double[] movement = collide(dx, dy, dz);
            double length = movement[0] * movement[0] + movement[1] * movement[1] + movement[2] * movement[2];
            if (length > 1.0E-7 || dx * dx + dy * dy + dz * dz - length < 1.0E-7) {
                x += movement[0];
                y += movement[1];
                z += movement[2];
            }
            boolean vertical = dy != movement[1];
            onGround = vertical && dy < 0.0;
            if (Math.abs(dx - movement[0]) >= 1.0E-5F) velocityX = 0;
            if (vertical && (!shoveAfterTravel || dy != 0.0)) velocityY = 0;
        }

        void travel() {
            if (Math.abs(velocityX) < 0.003) velocityX = 0;
            if (Math.abs(velocityY) < 0.003) velocityY = 0;
            float friction = (onGround ? 0.6F : 1.0F) * 0.91F;
            move(velocityX, velocityY, 0);
            velocityY = (velocityY - 0.08) * (double) 0.98F;
            velocityX *= friction;
        }

        /** ShulkerBoxBlockEntity#updateAnimation while OPENING, with moveCollidedEntities. */
        void tickBlockEntities() {
            for (Lid lid : lids) {
                if (!lid.opening) continue;
                lid.progressOld = lid.progress;
                if (lid.progress >= 1.0F) continue;
                lid.progress = Math.min(lid.progress + 0.1F, 1.0F);
                Box area = lid.area();
                if (!box().intersects(area)) continue;
                move(
                        (area.maxX() - area.minX() + 0.01) * lid.step[0],
                        (area.maxY() - area.minY() + 0.01) * lid.step[1],
                        0.0);
            }
        }
    }

    private static final class Scenario {
        final ClientVersion version;
        final int ticks;
        final List<Lid> boxes = new ArrayList<>(List.of(new Lid(new BlockPos(0, FLOOR_Y, 0), "up")));
        final List<BlockPos> stones = new ArrayList<>();
        final List<BlockPos> slabs = new ArrayList<>();
        double startX = 0.5;
        double startY = FLOOR_Y + 1;
        double startVelocityY = -0.0784000015258789;
        boolean startOnGround = true;
        BlockPos piston;
        int pistonTick = -1;
        int shiftFrom = Integer.MAX_VALUE;
        int fakeTick = -1;
        double fakeX;
        double fakeY;
        Boolean fakeOnGround;
        final List<Boolean> grounds = new ArrayList<>();

        Scenario(ClientVersion version, int ticks) {
            this.version = version;
            this.ticks = ticks;
        }

        void box(BlockPos pos, String facing) {
            boxes.add(new Lid(pos, facing));
        }

        void fake(int tick, double dx, double dy) {
            fakeTick = tick;
            fakeX = dx;
            fakeY = dy;
        }

        /**
         * Opens every box at its open tick and reports each tick the stepper reaches, asserting it exact, except at the
         * fake tick, whose result it returns with the reported position moved by the fake offset.
         */
        PredictionResult run() throws Exception {
            try (var f = new JavaTickFixture()) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                Stepper stepper = new Stepper(version.isNewerThanOrEquals(ClientVersion.V_26_3));
                for (int x = -8; x <= 8; x++) {
                    for (int z = -8; z <= 8; z++) stepper.solids.add(new Box(x, FLOOR_Y - 1, z, x + 1, FLOOR_Y, z + 1));
                }
                List<Integer> states = new ArrayList<>();
                for (Lid lid : boxes) {
                    int state = REGISTRY.with(
                            REGISTRY.block("minecraft:shulker_box").defaultState(), "facing", lid.facing);
                    f.player.compensatedWorld.updateBlock(lid.pos, state);
                    states.add(state);
                    stepper.lids.add(lid);
                }
                for (BlockPos stone : stones) {
                    f.player.compensatedWorld.updateBlock(
                            stone, REGISTRY.block("minecraft:stone").defaultState());
                    stepper.solids.add(new Box(
                            stone.getX(),
                            stone.getY(),
                            stone.getZ(),
                            stone.getX() + 1,
                            stone.getY() + 1,
                            stone.getZ() + 1));
                }
                for (BlockPos slab : slabs) {
                    f.player.compensatedWorld.updateBlock(
                            slab, REGISTRY.block("minecraft:stone_slab").defaultState());
                    stepper.solids.add(new Box(
                            slab.getX(),
                            slab.getY(),
                            slab.getZ(),
                            slab.getX() + 1,
                            slab.getY() + 0.5,
                            slab.getZ() + 1));
                }
                int pistonState =
                        REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
                if (piston != null) {
                    f.player.compensatedWorld.updateBlock(piston, pistonState);
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(piston.getX() + 1, piston.getY(), piston.getZ()),
                            REGISTRY.block("minecraft:stone").defaultState());
                }
                for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
                stepper.x = startX;
                stepper.y = startY;
                stepper.velocityY = startVelocityY;
                stepper.onGround = startOnGround;
                f.place(new Vec3(stepper.x, stepper.y, stepper.z), new Vec3(0, stepper.velocityY, 0));
                f.player.onGround = f.player.lastOnGround = startOnGround;
                f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(startOnGround));
                stepper.travel();
                f.input(NONE);
                assertExact(f.tick(new Vec3(stepper.x, stepper.y, stepper.z), stepper.onGround));

                for (int tick = 0; tick < ticks; tick++) {
                    for (int i = 0; i < boxes.size(); i++) {
                        if (boxes.get(i).openTick != tick) continue;
                        blockEvent(f, boxes.get(i).pos, 1, 1, states.get(i));
                        boxes.get(i).opening = true;
                    }
                    // PistonBaseBlock#triggerEvent EXTEND toward east (Direction#get3DDataValue 5).
                    if (tick == pistonTick) blockEvent(f, piston, 0, 5, pistonState);
                    boolean shift = tick >= shiftFrom;
                    stepper.travel();
                    stepper.updatePlayerPose(shift);
                    if (stepper.shoveAfterTravel) stepper.tickBlockEntities();
                    f.input(new ServerboundPlayerInput(false, false, false, false, false, shift, false));
                    if (tick == fakeTick) {
                        return f.tick(
                                new Vec3(stepper.x + fakeX, stepper.y + fakeY, stepper.z),
                                fakeOnGround == null ? stepper.onGround : fakeOnGround);
                    }
                    assertExact(f.tick(new Vec3(stepper.x, stepper.y, stepper.z), stepper.onGround));
                    grounds.add(stepper.onGround);
                    if (!stepper.shoveAfterTravel) stepper.tickBlockEntities();
                }
            }
            assertTrue(fakeTick < 0, "no fake tick reached");
            return null;
        }
    }

    private static void blockEvent(JavaTickFixture f, BlockPos pos, int action, int parameter, int state) {
        var packet = new ClientboundBlockEvent(pos, action, parameter, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertNotNull(result);
        assertTrue(exact(result), describe(result));
    }
}
