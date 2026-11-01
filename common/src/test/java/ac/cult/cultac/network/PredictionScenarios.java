package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.Phase;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/** Seeded movement over a {@link JavaTickFixture}: terrain, inputs, client positions, knockback and teleports. */
final class PredictionScenarios {
    static final int TICKS = 40;
    private static final int HORSE = 900;

    enum Terrain {
        FLAT,
        AIR,
        WATER,
        LAVA,
        LADDER,
        COBWEB,
        WALL,
        STEP,
        CEILING,
        ENTITIES,
        HORSE
    }

    private PredictionScenarios() {}

    static Terrain terrain(long seed) {
        return Terrain.values()[(int) Math.floorMod(seed, (long) Terrain.values().length)];
    }

    /** Scenario {@code seed} on a fresh fixture; each tick's committed prediction, or null for a tick without one. */
    static void run(JavaTickFixture f, long seed, Consumer<PredictionResult> each) throws Exception {
        Random random = new Random(seed);
        Terrain terrain = terrain(seed);
        build(f, terrain);
        Vec3 position = new Vec3(
                0.5 + random.nextInt(3) * 0.15,
                terrain == Terrain.AIR ? FLOOR_Y + 1 + random.nextInt(4) : FLOOR_Y,
                0.5 + random.nextInt(3) * 0.15);
        f.place(position, Vec3.ZERO);
        // Phase compares each move with the box a join or teleport would have left it.
        JavaTickFixture.setField(
                Phase.class,
                f.player.checkManager.getCheck(Phase.class),
                "oldBox",
                GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.6F, 1.8F));
        boolean riding = terrain == Terrain.HORSE;
        if (riding) mount(f, position);
        SimulationProcessor processor = f.player.checkManager.getSimulationProcessor();
        Vec3 delta = Vec3.ZERO;
        for (int tick = 0; tick < TICKS; tick++) {
            switch (random.nextInt(24)) {
                case 0 ->
                    f.pongThrough(f.velocity(new Vec3(
                            random.nextGaussian() * 0.3, random.nextDouble() * 0.4, random.nextGaussian() * 0.3)));
                case 1 ->
                    f.explosion(new Vec3(
                            random.nextGaussian() * 0.5, random.nextDouble() * 0.5, random.nextGaussian() * 0.5));
                case 2 -> {
                    if (!riding) {
                        position = new Vec3(0.5, FLOOR_Y + random.nextInt(3), 0.5);
                        f.teleport(position, new RelativeFlag(0));
                    }
                }
                case 3 -> {
                    if (!riding) release(f, true);
                }
                case 4 -> {
                    if (!riding) release(f, false);
                }
                default -> {}
            }
            f.input(new ServerboundPlayerInput(
                    random.nextInt(3) != 0,
                    random.nextInt(8) == 0,
                    random.nextInt(5) == 0,
                    random.nextInt(5) == 0,
                    random.nextInt(4) == 0,
                    random.nextInt(10) == 0,
                    random.nextInt(3) == 0));
            Vec3 move = random.nextInt(3) == 0
                    ? new Vec3(random.nextGaussian() * 0.15, random.nextGaussian() * 0.2, random.nextGaussian() * 0.15)
                    : delta;
            Vec3 to = position.add(move);
            if (to.y < FLOOR_Y) to = new Vec3(to.x, FLOOR_Y, to.z);
            boolean onGround = to.y == FLOOR_Y ? random.nextInt(10) != 0 : random.nextInt(12) == 0;

            PredictionResult before = processor.getLastPrediction();
            if (riding) {
                rideMove(f, to, onGround);
            } else {
                f.move(to, onGround);
            }
            f.tickEnd();
            PredictionResult after = processor.getLastPrediction();
            boolean committed = after != null && after != before;
            each.accept(committed ? after : null);
            position = riding ? to : new Vec3(f.player.x, f.player.y, f.player.z);
            delta = committed && after.getValidMovements() != null ? after.getAcceptedClosestToTarget() : Vec3.ZERO;
        }
    }

    /** A riptide release this tick: {@code inWater} when the last move left Entity#wasTouchingWater set. */
    private static void release(JavaTickFixture f, boolean inWater) {
        f.player.packetStateData.riptideLevel = 3;
        f.player.packetStateData.riptideReleaseCertain = inWater;
        f.player.riptideSpinAttackTicks = 20;
    }

    private static void build(JavaTickFixture f, Terrain terrain) {
        switch (terrain) {
            case WATER, LAVA ->
                fill(f, -3, FLOOR_Y, -3, 3, FLOOR_Y + 1, 3, terrain == Terrain.WATER ? BlockIds.WATER : BlockIds.LAVA);
            case LADDER -> fill(f, 0, FLOOR_Y, 0, 0, FLOOR_Y + 5, 0, BlockIds.LADDER);
            case COBWEB -> fill(f, 0, FLOOR_Y, 0, 0, FLOOR_Y + 1, 0, BlockIds.COBWEB);
            case WALL -> fill(f, 1, FLOOR_Y, -2, 1, FLOOR_Y + 1, 2, BlockIds.STONE);
            case STEP -> fill(f, 1, FLOOR_Y, -2, 2, FLOOR_Y, 2, BlockIds.STONE);
            case CEILING -> fill(f, -2, FLOOR_Y + 2, -2, 2, FLOOR_Y + 2, 2, BlockIds.STONE);
            case ENTITIES -> {
                f.player.compensatedEntities.addEntity(500, EntityTypeIds.ZOMBIE, new Vec3(1.2, FLOOR_Y, 0.5), 0, 0, 0);
                f.player.compensatedEntities.addEntity(
                        501, EntityTypeIds.ZOMBIE, new Vec3(0.5, FLOOR_Y, -0.3), 0, 0, 0);
                f.player.compensatedEntities.addEntity(
                        502, EntityTypeIds.OAK_BOAT, new Vec3(-1.0, FLOOR_Y, 0.5), 0, 0, 0);
            }
            default -> {}
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void fill(JavaTickFixture f, int x0, int y0, int z0, int x1, int y1, int z1, BlockDefinition block) {
        fill(f, x0, y0, z0, x1, y1, z1, block.defaultState());
    }

    /** {@code state} in every block from {@code x0, y0, z0} to {@code x1, y1, z1}. */
    static void fill(JavaTickFixture f, int x0, int y0, int z0, int x1, int y1, int z1, int state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
                }
            }
        }
    }

    private static void mount(JavaTickFixture f, Vec3 seat) {
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, seat, 0, 0, 0);
        ((PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE)).hasSaddle = true;
        var packet = new ClientboundSetPassengers(HORSE, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void rideMove(JavaTickFixture f, Vec3 to, boolean onGround) {
        var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move =
                new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), f.player.xRot, f.player.yRot, onGround, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
    }
}
