package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Camel#tick stands a sitting camel up at once in water, so Camel#canJump holds for its rider's next jump. */
class JavaCamelPoseTest {
    private static final int CAMEL = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    /** LivingEntity#travelInWater: moveRelative(0.02F) of the 0.98 forward zza AbstractHorse#getRiddenInput passes. */
    private static final double WATER_INPUT = (double) 0.98F * 0.02F;
    /** LivingEntity#getWaterSlowDown for a camel that is not sprinting. */
    private static final double WATER_SLOW_DOWN = 0.8F;
    /** LivingEntity#travel on stone: moveRelative of the camel's 0.09 speed, scaled for 0.6 friction, by 0.98 zza. */
    private static final double LAND_INPUT = (double) 0.98F * (double) (0.09F * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    /** A move up and forward that a camel's riding jump could make. */
    private static final Vec3 HOP = new Vec3(SEAT.x, SEAT.y + 0.16, SEAT.z + 0.8);

    @Test
    void aSittingCamelInWaterStandsUpBeforeItsRiderReleasesAJump() throws Exception {
        try (var f = sitting()) {
            shallowWater(f);
            expireBlockChangeUncertainty(f);
            // onPlayerJump(0) arms the minimum 0.4 scale.
            releaseJump(f);
            assertTrue(exact(f.rideTick(SEAT, true)));
            assertFalse(camel(f).isCamelSitting());
            assertEquals(0.4D, camel(f).horseJump, 1.0E-6);
        }
    }

    @Test
    void aSittingCamelOnDryGroundDropsItsRidersJump() throws Exception {
        try (var f = sitting()) {
            releaseJump(f);
            assertTrue(exact(f.rideTick(SEAT, true)));
            assertTrue(camel(f).isCamelSitting());
            assertEquals(0.0D, camel(f).horseJump);
            assertTrue(exact(f.rideTick(SEAT, true)));
        }
    }

    @Test
    void aSittingCamelTakesNoJumpReleasedAfterItsMove() throws Exception {
        try (var f = sitting()) {
            var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, true, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            var move = new ServerboundMoveVehicle(
                    new Vec3d(SEAT.x, SEAT.y, SEAT.z), f.player.xRot, f.player.yRot, true, true);
            f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
            releaseJump(f);
            f.tickEnd();
            assertEquals(0.0D, camel(f).horseJump);
            assertTrue(exact(f.rideTick(SEAT, true)));
        }
    }

    @Test
    void aSittingCamelTakesNoJumpReleasedInATickWithoutItsMove() throws Exception {
        try (var f = sitting()) {
            releaseJump(f);
            f.rideTick();
            assertEquals(0.0D, camel(f).horseJump);
            assertTrue(exact(f.rideTick(SEAT, true)));
        }
    }

    /** The client's water arrived before the camel's tick: Camel#tick stands it up at once, and it walks next tick. */
    @Test
    void aSittingCamelReachedByWaterBeforeItsTickStandsUpAtOnceAndWalks() throws Exception {
        try (var f = sitting()) {
            shallowWater(f);
            f.input(FORWARD);
            // The root ticked on the idle keys before LocalPlayer#aiStep read the pressed ones.
            assertExactTick(uncertainWater(f.rideTick(SEAT, true)), "entry");
            double velocity = 0.0D;
            double z = SEAT.z;
            for (int tick = 0; tick < 5; tick++) {
                velocity = velocity * WATER_SLOW_DOWN + WATER_INPUT;
                z += velocity;
                assertExactTick(f.rideTick(new Vec3(SEAT.x, SEAT.y, z), true), "walk " + tick);
            }
        }
    }

    /** The client's water arrived before the camel's tick: Camel#canJump holds when its rider releases a jump. */
    @Test
    void aSittingCamelReachedByWaterBeforeItsTickTakesItsRidersJump() throws Exception {
        try (var f = sitting()) {
            shallowWater(f);
            releaseJump(f);
            assertExactTick(uncertainWater(f.rideTick(SEAT, true)), "entry");
            assertFalse(camel(f).isCamelSitting());
            assertEquals(0.4D, camel(f).horseJump, 1.0E-6);
        }
    }

    /** Camel#tickRidden stands the camel up on its rider's forward key before Camel#tick checks the water. */
    @Test
    void aSittingCamelReachedByWaterAfterItsTickStandsUpThroughItsTransition() throws Exception {
        try (var f = sitting()) {
            f.input(FORWARD);
            assertExactTick(f.rideTick(SEAT, true), "entry");
            shallowWater(f);
            assertExactTick(uncertainWater(f.rideTick(SEAT, true)), "transition 0");
            for (int tick = 1; tick < 6; tick++) assertExactTick(f.rideTick(SEAT, true), "transition " + tick);
        }
    }

    /** A camel that stood up in water keeps its momentum while its rider holds forward; it does not halt at once. */
    @Test
    void aCamelThatStoodUpInWaterKeepsItsMomentum() throws Exception {
        try (var f = sitting()) {
            shallowWater(f);
            f.input(FORWARD);
            assertExactTick(uncertainWater(f.rideTick(SEAT, true)), "entry");
            double velocity = 0.0D;
            double z = SEAT.z;
            for (int tick = 0; tick < 2; tick++) {
                velocity = velocity * WATER_SLOW_DOWN + WATER_INPUT;
                z += velocity;
                assertExactTick(f.rideTick(new Vec3(SEAT.x, SEAT.y, z), true), "walk " + tick);
            }
            assertFalse(exact(f.rideTick(new Vec3(SEAT.x, SEAT.y, z), true)));
        }
    }

    /** A camel already standing up when water reached it refuses its rider's input through its transition. */
    @Test
    void aCamelThatKeptSittingInWaterDoesNotWalkInItsTransition() throws Exception {
        try (var f = sitting()) {
            f.input(FORWARD);
            assertExactTick(f.rideTick(SEAT, true), "entry");
            shallowWater(f);
            assertExactTick(uncertainWater(f.rideTick(SEAT, true)), "transition 0");
            for (int tick = 1; tick < 3; tick++) assertExactTick(f.rideTick(SEAT, true), "transition " + tick);
            assertFalse(exact(f.rideTick(new Vec3(SEAT.x, SEAT.y, SEAT.z + WATER_INPUT), true)));
        }
    }

    /** Camel#tick keeps a camel seated on a tick it is not in water, and a seated camel takes no riding jump. */
    @Test
    void aSittingCamelLeftOnDryGroundTakesNoJump() throws Exception {
        try (var f = sitting()) {
            shallowWater(f);
            layer(f, FLOOR_Y, BlockIds.AIR.defaultState());
            assertExactTick(f.rideTick(SEAT, true), "dry");
            releaseJump(f);
            assertExactTick(f.rideTick(SEAT, true), "jump");
            assertTrue(camel(f).isCamelSitting());
            assertEquals(0.0D, camel(f).horseJump);
            assertFalse(exact(f.rideTick(HOP, false)));
        }
    }

    /** Camel#getDefaultDimensions: water held by slabs over a sitting camel lies above its box, so it stays seated. */
    @Test
    void aSittingCamelUnderWaterloggedSlabsStaysSeatedAndTakesNoJump() throws Exception {
        try (var f = sitting()) {
            var registry = DataTables.defaults().registry();
            int slab = registry.with(
                    registry.with(registry.block("minecraft:oak_slab").defaultState(), "type", "top"),
                    "waterlogged",
                    "true");
            layer(f, FLOOR_Y + 1, slab);
            expireBlockChangeUncertainty(f);
            releaseJump(f);
            assertExactTick(f.rideTick(SEAT, true), "jump");
            assertTrue(camel(f).isCamelSitting());
            assertEquals(0.0D, camel(f).horseJump);
            assertExactTick(f.rideTick(SEAT, true), "seated");
        }
    }

    /** Camel#getDefaultDimensions: water above a sitting camel's box leaves it seated, and forward stands it up slowly. */
    @Test
    void aSittingCamelBelowWaterStandsUpThroughItsTransition() throws Exception {
        try (var f = sitting()) {
            layer(f, FLOOR_Y + 1, BlockIds.WATER.defaultState());
            expireBlockChangeUncertainty(f);
            f.input(FORWARD);
            for (int tick = 0; tick < 6; tick++) assertExactTick(f.rideTick(SEAT, true), "transition " + tick);
        }
    }

    /** A camel that stood up on a tick it was in water stays standing once the water is gone, and can jump. */
    @Test
    void aCamelThatStoodUpInBriefWaterStaysStanding() throws Exception {
        try (var f = sitting()) {
            shallowWater(f);
            assertExactTick(f.rideTick(SEAT, true), "water 0");
            assertExactTick(f.rideTick(SEAT, true), "water 1");
            layer(f, FLOOR_Y, BlockIds.AIR.defaultState());
            for (int tick = 0; tick < 5; tick++) assertExactTick(f.rideTick(SEAT, true), "dry " + tick);
            releaseJump(f);
            assertExactTick(f.rideTick(SEAT, true), "jump");
            assertEquals(0.4D, camel(f).horseJump, 1.0E-6);
            assertFalse(camel(f).isCamelSitting());
        }
    }

    /** Water placed and removed between two client ticks is never in the camel's tick, so it stays seated. */
    @Test
    void aSittingCamelMissedByWaterBetweenItsTicksDoesNotWalk() throws Exception {
        try (var f = sitting()) {
            shallowWater(f);
            layer(f, FLOOR_Y, BlockIds.AIR.defaultState());
            f.input(FORWARD);
            assertExactTick(f.rideTick(SEAT, true), "entry");
            assertTrue(camel(f).isCamelSitting());
            assertFalse(exact(f.rideTick(new Vec3(SEAT.x, SEAT.y, SEAT.z + LAND_INPUT), true)));
        }
    }

    /** The tick ran while the client could hold the water or not. */
    private static PredictionResult uncertainWater(PredictionResult result) {
        assertEquals(
                List.of(false, true),
                result.getSimulationContext().getWorldData().getInWater().getStates());
        return result;
    }

    private static void assertExactTick(PredictionResult result, String tick) {
        StringBuilder report = new StringBuilder(tick).append(": ").append(describe(result));
        for (PredictionResult.Flag flag : result.getFlags())
            report.append(' ').append(flag.getVerbose().getString());
        report.append(" inWater=")
                .append(result.getSimulationContext()
                        .getWorldData()
                        .getInWater()
                        .getStates());
        assertTrue(exact(result), report.toString());
    }

    private static void releaseJump(JavaTickFixture f) {
        var jump = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_JUMPING_WITH_HORSE, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, jump), f.player, jump);
    }

    /** Level-7 water over the floor under the camel. */
    private static void shallowWater(JavaTickFixture f) {
        layer(f, FLOOR_Y, DataTables.defaults().registry().with(BlockIds.WATER.defaultState(), "level", "7"));
    }

    private static void layer(JavaTickFixture f, int y, int state) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
        }
    }

    private static void expireBlockChangeUncertainty(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** A saddled camel that sat down long enough ago to have finished its 40-tick sitting transition. */
    private static JavaTickFixture sitting() throws Exception {
        JavaTickFixture f = ridden();
        long sat = f.player.compensatedWorld.clientGameTime();
        var layout = WatchableIndexUtil.forModel(f.player.user.getCultConnection());
        setEntityData(
                f,
                CAMEL,
                List.of(
                        entry(WatchableIndexUtil.ENTITY_POSE, EntityPose.SITTING),
                        // Camel defines LAST_POSE_CHANGE_TICK directly after DASH; negative while sitting.
                        entry(layout.camelDash() + 1, -(sat + 1))));
        PacketEntityCamel camel = camel(f);
        assertTrue(camel.isCamelSitting());
        while (camel.isInPoseTransition(f.player.compensatedWorld.clientGameTime())) {
            assertTrue(exact(f.rideTick(SEAT, true)));
        }
        return f;
    }

    /** A saddled camel at {@link #SEAT} mounted through set_passengers, after one stationary ridden tick. */
    private static JavaTickFixture ridden() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        // A level well past the 52 ticks in which a camel spawned with LAST_POSE_CHANGE_TICK 0 is still standing up.
        f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, SEAT, 0, 0, 0);
        camel(f).hasSaddle = true;
        var packet = new ClientboundSetPassengers(CAMEL, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(exact(f.rideTick(SEAT, true)));
        return f;
    }

    private static PacketEntityCamel camel(JavaTickFixture f) {
        return (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
    }

    private static EntityMetadata.Entry entry(int index, Object value) {
        return new EntityMetadata.Entry(index, value, ByteBuffer.allocate(0));
    }

    private static void setEntityData(JavaTickFixture f, int entityId, List<EntityMetadata.Entry> entries) {
        var packet = new EntityMetadata(entityId, entries);
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
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
