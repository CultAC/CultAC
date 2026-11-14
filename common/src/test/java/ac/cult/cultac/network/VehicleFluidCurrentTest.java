package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A ridden living vehicle in water: Entity#baseTick adds the normalized current scaled by 0.014 to its deltaMovement,
 * and LivingEntity#travelInWater moves it by its ridden input at 0.02F, then slows it by 0.8F, or by 0.9F while its own
 * shared sprinting flag is set, and LivingEntity#getFluidFallingAdjustedMovement subtracts gravity / 16 from a
 * vehicle that is not sprinting.
 */
class VehicleFluidCurrentTest {
    private static final int VEHICLE = 100;
    private static final double WATER_SCALE = 0.014D;
    private static final double WATER_INPUT = 0.02F;
    private static final double WATER_DRAG = 0.8F;
    private static final double SPRINT_WATER_DRAG = 0.9F;
    /** LivingEntity#getFluidFallingAdjustedMovement: the default gravity of 0.08 over 16. */
    private static final double FLUID_GRAVITY = 0.08D / 16.0D;
    /** AbstractHorse#getRiddenInput's forward, the rider's zza of 0.98F. */
    private static final double HORSE_FORWARD = 0.98F;
    private static final int TICKS = 6;

    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    /** A pig steered along +z over the floor of water flowing +x drifts by the current and keeps to the floor. */
    @Test
    void aSteeredPigOnTheFloorIsCarriedByTheCurrent() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.5);
        try (var f = steered(EntityTypeIds.PIG, "minecraft:carrot_on_a_stick", seat)) {
            flowingWater(f, 1);
            walk(f, seat, new Vec3(WATER_SCALE, 0.0, 0.0), WATER_DRAG, false, 0.0);
        }
    }

    /** The same ride with its ninth move shifted 0.005 along the current is not the pig's move. */
    @Test
    void aSteeredPigOffItsCurrentTrackIsRejected() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.5);
        try (var f = steered(EntityTypeIds.PIG, "minecraft:carrot_on_a_stick", seat)) {
            flowingWater(f, 1);
            Vec3 velocity = Vec3.ZERO;
            Vec3 at = seat;
            for (int tick = 0; tick < TICKS + 2; tick++) {
                Vec3 delta = velocity.add(WATER_SCALE, 0.0, WATER_INPUT);
                at = at.add(delta);
                assertVehicleExact(f.rideTick(at, true), tick);
                velocity = delta.scale(WATER_DRAG);
            }
            Vec3 delta = velocity.add(WATER_SCALE, 0.0, WATER_INPUT);
            PredictionResult result = f.rideTick(at.add(delta.x + 0.005, 0.0, delta.z), true);
            assertFlagged(result);
        }
    }

    /** A pig steered in the middle of still water three deep sinks: it does not float while ridden. */
    @Test
    void aSteeredPigSinksThroughStillWater() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y + 1.0, 0.5);
        try (var f = steered(EntityTypeIds.PIG, "minecraft:carrot_on_a_stick", seat)) {
            stillWater(f, 3);
            walk(f, seat, Vec3.ZERO, WATER_DRAG, true, FLUID_GRAVITY);
        }
    }

    /**
     * A pig whose shared sprinting flag the server sets: LivingEntity#travelInWater slows it by 0.9F, and
     * getFluidFallingAdjustedMovement leaves its fall alone, so it holds its height in still water.
     */
    @Test
    void aSteeredPigFlaggedSprintingHoldsItsHeightInStillWater() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y + 1.0, 0.5);
        try (var f = steered(EntityTypeIds.PIG, "minecraft:carrot_on_a_stick", seat)) {
            stillWater(f, 3);
            sharedFlags(f, (byte) 0x08);
            walk(f, seat, Vec3.ZERO, SPRINT_WATER_DRAG, true, 0.0);
        }
    }

    /**
     * The flagged pig's ninth tick moved as if its eighth had been an unflagged pig's, slowed by 0.8F and sinking, is
     * not its move.
     */
    @Test
    void aSteeredPigFlaggedSprintingDoesNotSink() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y + 1.0, 0.5);
        try (var f = steered(EntityTypeIds.PIG, "minecraft:carrot_on_a_stick", seat)) {
            stillWater(f, 3);
            sharedFlags(f, (byte) 0x08);
            double velocity = 0.0;
            Vec3 at = seat;
            for (int tick = 0; tick < TICKS + 2; tick++) {
                double delta = velocity + WATER_INPUT;
                at = at.add(0.0, 0.0, delta);
                assertVehicleExact(f.rideTick(at, false), tick);
                velocity = delta * SPRINT_WATER_DRAG;
            }
            double unflagged = (velocity / SPRINT_WATER_DRAG) * WATER_DRAG + WATER_INPUT;
            PredictionResult result = f.rideTick(at.add(0.0, -FLUID_GRAVITY, unflagged), false);
            assertFlagged(result);
        }
    }

    /** A strider steered along +z through water flowing +x walks the floor and drifts by the current. */
    @Test
    void aSteeredStriderInWaterIsCarriedByTheCurrent() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.5);
        try (var f = steered(EntityTypeIds.STRIDER, "minecraft:warped_fungus_on_a_stick", seat)) {
            flowingWater(f, 2);
            walk(f, seat, new Vec3(WATER_SCALE, 0.0, 0.0), WATER_DRAG, false, 0.0);
        }
    }

    /**
     * A horse ridden upstream against water flowing +x: its horizontal move is the current and the rider's forward
     * through LivingEntity#travelInWater, at the height the engine accepts closest to the last one.
     */
    @Test
    void aHorseRiddenAgainstTheCurrentHasItsWaterTrack() throws Exception {
        float yaw = 90.0F;
        float radians = yaw * (float) (Math.PI / 180.0);
        double input = HORSE_FORWARD * WATER_INPUT;
        Vec3 inputVector = new Vec3(-input * VanillaMath.sin(radians), 0.0, input * VanillaMath.cos(radians));
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.5);
        List<Vec3> path = new ArrayList<>();
        double vx = 0.0;
        double vz = 0.0;
        double x = seat.x;
        double z = seat.z;
        for (int tick = 0; tick < TICKS; tick++) {
            // The rider's keys reach the horse's move one tick after they are sent.
            Vec3 in = tick == 0 ? Vec3.ZERO : inputVector;
            double dx = vx + WATER_SCALE + in.x;
            double dz = vz + in.z;
            x += dx;
            z += dz;
            Vec3 previous = path.isEmpty() ? seat : path.get(path.size() - 1);
            Vec3 at = closestOnHorse(seat, yaw, path, new Vec3(x, previous.y, z));
            assertTrue(Math.abs(at.x - x) < 1.0E-6 && Math.abs(at.z - z) < 1.0E-6, "tick " + tick + " at " + at);
            path.add(at);
            vx = dx * WATER_DRAG;
            vz = dz * WATER_DRAG;
        }
        try (var f = horse(seat, yaw)) {
            for (int tick = 0; tick < TICKS; tick++) assertVehicleExact(f.rideTick(path.get(tick), false), tick);
        }
    }

    /**
     * Rides the steered vehicle along +z (yaw 0) for {@link #TICKS} ticks from rest at {@code seat}: each tick moves
     * by its velocity, {@code current} and the 0.02F input, and carries the move times {@code drag} horizontally.
     * With {@code midWater} the vehicle is off the floor: its fall is slowed by 0.8F and grows by {@code gravity} a
     * tick. Otherwise the floor stops each tick's fall.
     */
    private static void walk(
            JavaTickFixture f, Vec3 seat, Vec3 current, double drag, boolean midWater, double gravity) {
        Vec3 velocity = Vec3.ZERO;
        Vec3 at = seat;
        for (int tick = 0; tick < TICKS; tick++) {
            Vec3 delta = velocity.add(current).add(0.0, 0.0, WATER_INPUT);
            at = at.add(delta);
            assertVehicleExact(f.rideTick(at, !midWater), tick);
            double fall = midWater ? delta.y * WATER_DRAG - gravity : 0.0;
            velocity = new Vec3(delta.x * drag, fall, delta.z * drag);
        }
    }

    /** The engine's accepted position closest to {@code target} after the horse rode {@code path}. */
    private static Vec3 closestOnHorse(Vec3 seat, float yaw, List<Vec3> path, Vec3 target) throws Exception {
        try (var f = horse(seat, yaw)) {
            for (Vec3 position : path) f.rideTick(position, false);
            PredictionResult probe = f.rideTick(target, false);
            return probe.getSimulationContext().getStart().add(probe.getAcceptedClosestToTarget());
        }
    }

    /** A saddled horse at {@code seat} in water flowing +x, ridden forward facing {@code yaw}. */
    private static JavaTickFixture horse(Vec3 seat, float yaw) throws Exception {
        var f = new JavaTickFixture();
        f.player.xRot = yaw;
        f.place(seat, Vec3.ZERO);
        f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
        flowingWater(f, 1);
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.HORSE, seat, 0, 0, 0);
        ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(FORWARD);
        return f;
    }

    /** A saddled {@code type} at {@code seat} under the rider facing +z, who then holds {@code item}. */
    private static JavaTickFixture steered(int type, String item, Vec3 seat) throws Exception {
        var f = new JavaTickFixture();
        f.player.xRot = 0.0F;
        f.place(seat, Vec3.ZERO);
        f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
        f.player.compensatedEntities.addEntity(VEHICLE, type, seat, 0, 0, 0);
        ((PacketEntityRideable) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        for (int tick = 0; tick < 20; tick++) f.rideTick();
        f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack(item, 1));
        return f;
    }

    /** {@code depth} layers of water from the floor, its level rising by one a block along +x, so it flows +x. */
    private static void flowingWater(JavaTickFixture f, int depth) {
        var registry = DataTables.defaults().registry();
        for (int i = -3; i <= 8; i++) {
            int water = registry.with(
                    BlockIds.WATER.defaultState(), "level", String.valueOf(Math.max(1, Math.min(7, i + 4))));
            PredictionScenarios.fill(f, i, FLOOR_Y, -3, i, FLOOR_Y + depth - 1, 8, water);
        }
        settle(f);
    }

    private static void stillWater(JavaTickFixture f, int depth) {
        PredictionScenarios.fill(f, -3, FLOOR_Y, -3, 8, FLOOR_Y + depth - 1, 8, BlockIds.WATER.defaultState());
        settle(f);
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void assertFlagged(PredictionResult result) {
        assertTrue(
                result.getFlags().stream().anyMatch(flag -> flag.getCheck().getCheckName().equals("VehicleOffset")),
                describe(result));
    }

    /** The server's shared flags for the vehicle, acknowledged by the client. */
    private static void sharedFlags(JavaTickFixture f, byte flags) {
        var packet = new EntityMetadata(
                VEHICLE,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
