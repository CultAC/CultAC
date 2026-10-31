package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JavaRiderInterpolationTest {
    private static final Vec3 PLAYER = new Vec3(.30000001192093, FLOOR_Y, .00568005179361);
    private static final Vec3 BOAT = new Vec3(1.29000001192094, FLOOR_Y, .70505533801543);
    private static final double PIG_Z = .323640342099395;
    private static final Vec3 SEAT = new Vec3(1.4104733241028, FLOOR_Y + .1875, PIG_Z);

    /**
     * Flag 33: ClientLevel#tickPassenger interpolates before rideTick's push. A rotation-only packet at the first
     * half-stroke's displaced position starts a positional path too. Reseating followed by the second piston shove
     * shifts that path; the next commonTick cannot carry the reseat delta into a target blocked by the head.
     */
    @ParameterizedTest
    @CsvSource({"true,true,true", "false,true,true", "true,false,true", "true,true,false"})
    void aPistonDisplacedPassengerInterpolatesBeforePushing(boolean interpolate, boolean blocked, boolean bundled)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(PLAYER, new Vec3(0, -.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var entities = f.player.compensatedEntities;
            entities.addEntity(43, EntityTypeIds.OAK_BOAT, BOAT, 17.532085F, 0, 0);
            PacketEntity boat = entities.getEntity(43);
            entities.addEntity(44, EntityTypeIds.CHICKEN, new Vec3(6, FLOOR_Y, 6), 0, 0, 0);
            entities.getEntity(44).mount(boat);
            entities.addEntity(45, EntityTypeIds.PIG, new Vec3(6, FLOOR_Y, 6), 0, 0, 0);
            PacketEntity pig = entities.getEntity(45);
            pig.mount(boat);
            Vec3 tracked = pig.clientPhysicalPosition;
            var trackedPath = pig.newPacketLocation;

            int firstPass = entities.beginRiderPushPass();
            double firstX = 1.5 - .9F / 2 - .01;
            pig.recordRiderPushBox(firstPass, box(f, pig, new Vec3(firstX, SEAT.y, PIG_Z)));
            if (interpolate) {
                pig.onPositionPath(EntityPositionPath.linear(Vec3.ZERO), false, 287F, 0F, f.player, bundled);
                if (!bundled) {
                    pig.onUnbundledPathProven();
                    pig.onSecondTransaction();
                }
            }
            // The first interpolation is at the packet's current position, then rideTick reseats the pig.
            entities.tickClientEntities(entity -> entity.onMovement(f.player));
            int secondPass = entities.beginRiderPushPass();

            var registry = DataTables.defaults().registry();
            int head = registry.with(registry.block("minecraft:piston_head").defaultState(), "facing", "west");
            var pass = new ClientBlockEntityPass(
                    List.of(new ClientBlockEntityPass.Piston(
                            new BlockPos(1, FLOOR_Y, 0), head, Direction.WEST, true, true, .5F)),
                    1);
            var start = new JavaPassState(
                    SEAT,
                    Vec3.ZERO,
                    false,
                    new MainSupportingBlockData(null, false),
                    null,
                    0,
                    ClientPistonDeltas.NONE,
                    false);
            var replay = JavaBlockEntityPass.runDetachedEntity(f.player, pig, pass, start, null);
            SimpleCollisionBox displaced = box(f, pig, replay.state().position());
            assertEquals(SEAT.x - .51, centerX(displaced), 1e-12);
            pig.recordRiderInterpolationPass(displaced, replay.interpolation(), replay.oldInterpolation());
            pig.recordRiderPushBox(secondPass, displaced);
            if (blocked) f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), head);
            // No packet is handled in the middle of a client tick.
            f.player.packetStateData.acceptedClientTick++;

            var pushes = entities.untrackedRiderPushBoxes(
                    pig, f.player.boundingBox, new SimpleCollisionBox(), new SimpleCollisionBox());
            double expectedX = interpolate && blocked ? firstX - .51 : SEAT.x - .51;
            assertEquals(1, pushes.size());
            assertEquals(expectedX, centerX(pushes.getFirst()), 1e-12);
            assertEquals(PIG_Z, (pushes.getFirst().minZ + pushes.getFirst().maxZ) / 2, 1e-12);
            assertEquals(
                    expectedX,
                    centerX(entities.untrackedRiderPushBoxes(
                                    pig, f.player.boundingBox, new SimpleCollisionBox(), new SimpleCollisionBox())
                            .getFirst()),
                    1e-12,
                    "repeated prediction reads must not advance interpolation twice");
            assertSame(tracked, pig.clientPhysicalPosition, "the rider's tracked position must never be committed");
            assertSame(trackedPath, pig.newPacketLocation, "the push replay owns its detached packet path");

            // The late boat and pig pushes are consumed by the following movement, not by this standing tick.
            Vec3 pigPosition = new Vec3(expectedX, SEAT.y, PIG_Z);
            Vec3 push = JavaLivingEntityPushes.impulse(BOAT, PLAYER)
                    .add(JavaLivingEntityPushes.impulse(pigPosition, PLAYER));
            var contact = f.tick(PLAYER, true);
            assertTrue(exact(contact), describe(contact));
            var moved = f.tick(PLAYER.add(push), true);
            assertTrue(exact(moved), describe(moved));
            assertSame(tracked, pig.clientPhysicalPosition);
        }
    }

    private static SimpleCollisionBox box(JavaTickFixture f, PacketEntity entity, Vec3 position) {
        return GetBoundingBox.getPacketEntityBoundingBox(f.player, position.x, position.y, position.z, entity);
    }

    private static double centerX(SimpleCollisionBox box) {
        return (box.minX + box.maxX) / 2;
    }
}
