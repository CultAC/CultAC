package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class ReachInterpolationInPlaceTest {
    private static final SimpleCollisionBox START = new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
    private static final SimpleCollisionBox TARGET = new SimpleCollisionBox(3, 3, 3, 4, 4, 4);

    @Test
    void combineIntoInput() {
        SimpleCollisionBox one = new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
        SimpleCollisionBox two = new SimpleCollisionBox(-1, 2, 0.5, 0.5, 3, 4, true);
        SimpleCollisionBox expected = ReachInterpolationData.combineCollisionBox(one, two);
        assertSame(two, ReachInterpolationData.combineCollisionBox(one, two, two));
        assertBoxEquals(expected, two);
    }

    /** Entity#refreshDimensions resizes each position a pending second transaction leaves possible around its feet. */
    @Test
    void resizeKeepsEveryPossibleStartingPosition() {
        ReachInterpolationData data = new ReachInterpolationData(START);
        data.updatePossibleStartingLocation(new SimpleCollisionBox(2, 0, 0, 3, 1, 1));
        data.resize(3.0F, 2.0F);
        assertBoxEquals(new SimpleCollisionBox(-1, 0, -1, 4, 2, 2), data.getPossibleMovementLocationCombined());
    }

    /** A baby start under the adult target it grew into, then a scale drop: the start never shrinks below the size. */
    @Test
    void aShrinkPastAStartSmallerThanTheTargetKeepsTheStartAtTheNewSize() throws Exception {
        ReachInterpolationData data = new ReachInterpolationData(new SimpleCollisionBox(-0.3, 0, -0.3, 0.3, 1.95, 0.3));
        set(data, "startingLocation", new SimpleCollisionBox(1.85, 0, -0.15, 2.15, 0.975, 0.15));
        data.resize(0.06F, 0.195F);
        var field = ReachInterpolationData.class.getDeclaredField("startingLocation");
        field.setAccessible(true);
        SimpleCollisionBox start = (SimpleCollisionBox) field.get(data);
        assertEquals(2.0, (start.minX + start.maxX) / 2.0, 1e-9);
        assertEquals(0.0, (start.minZ + start.maxZ) / 2.0, 1e-9);
        assertEquals(0.0, start.minY);
        assertTrue(start.maxX - start.minX >= 0.06F - 1e-9, "start X " + start.minX + ".." + start.maxX);
        assertTrue(start.maxZ - start.minZ >= 0.06F - 1e-9, "start Z " + start.minZ + ".." + start.maxZ);
        assertTrue(start.maxY - start.minY >= 0.195F - 1e-9, "start Y " + start.minY + ".." + start.maxY);
    }

    @Test
    void combinedLocationIntoBox() throws Exception {
        ReachInterpolationData data = data(3, 1, 2);
        SimpleCollisionBox out = new SimpleCollisionBox(9, 9, 9, 10, 10, 10, true);
        assertSame(out, data.getPossibleMovementLocationCombined(out));
        assertBoxEquals(new SimpleCollisionBox(1, 1, 1, 3, 3, 3), out);
    }

    @Test
    void exactLocationIntoBox() throws Exception {
        ReachInterpolationData exact = data(3, 2, 2);
        SimpleCollisionBox out = new SimpleCollisionBox(9, 9, 9, 10, 10, 10, true);
        assertSame(out, exact.getExactMovementLocation(out));
        assertBoxEquals(exact.getExactMovementLocation(), out);
        assertNull(data(3, 1, 2).getExactMovementLocation(new SimpleCollisionBox()));
    }

    @Test
    void exactLocationAfterClientTickIntoBox() throws Exception {
        ReachInterpolationData data = data(3, 1, 1);
        SimpleCollisionBox expected = data.copyAdvancedForClientTick(true, true)
                .getPossibleMovementLocations()
                .get(0);
        SimpleCollisionBox out = new SimpleCollisionBox(9, 9, 9, 10, 10, 10, true);
        assertSame(out, data.exactMovementLocationAfterClientTick(out));
        assertBoxEquals(expected, out);
    }

    @Test
    void steppedExactLocationAfterClientTickIntoBox() {
        CultPlayer player = mock(CultPlayer.class);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_26_3);
        player.packetStateData = new PacketStateData();
        PacketEntity entity = new PacketEntity(EntityTypeIds.ZOMBIE, 1);
        SimpleCollisionBox start = new SimpleCollisionBox(-0.3, 0, -0.3, 0.3, 1.95, 0.3);
        ReachInterpolationData data =
                new ReachInterpolationData(player, start, 4, 1, -2, 0, 0, 90, 10, false, entity, false);
        assertTrue(data.usesSteppedInterpolation());
        data.appendPath(EntityPositionPath.linear(new Vec3(6, 1, -2)), 180, 0);
        SimpleCollisionBox out = new SimpleCollisionBox(9, 9, 9, 10, 10, 10, true);
        double firstMinX = Double.NaN;
        for (int tick = 0; tick < 6; tick++) {
            SimpleCollisionBox expected =
                    data.copyAdvancedForClientTick(true, true).getPossibleMovementLocationCombined();
            if (tick == 0) firstMinX = expected.minX;
            assertSame(out, data.exactMovementLocationAfterClientTick(out));
            assertBoxEquals(expected, out);
            data.tickMovement(true, true);
        }
        assertNotEquals(firstMinX, out.minX);
    }

    @Test
    void entityMovementBoxesIntoBox() throws Exception {
        PacketEntity entity = new PacketEntity(0, 1);
        entity.oldPacketLocation = data(3, 0, 1);
        entity.newPacketLocation = data(3, 2, 3);
        SimpleCollisionBox expected = ReachInterpolationData.combineCollisionBox(
                entity.oldPacketLocation.getPossibleMovementLocationCombined(),
                entity.newPacketLocation.getPossibleMovementLocationCombined());
        SimpleCollisionBox out = new SimpleCollisionBox(9, 9, 9, 10, 10, 10, true);
        assertSame(out, entity.getPossibleMovementCollisionBoxes(out, new SimpleCollisionBox()));
        assertBoxEquals(expected, out);
    }

    private static ReachInterpolationData data(int steps, int low, int high) throws Exception {
        ReachInterpolationData data = new ReachInterpolationData(START);
        set(data, "targetLocation", TARGET.copy());
        set(data, "interpolationSteps", steps);
        set(data, "interpolationStepsLowBound", low);
        set(data, "interpolationStepsHighBound", high);
        return data;
    }

    private static void set(ReachInterpolationData data, String name, Object value) throws Exception {
        var field = ReachInterpolationData.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(data, value);
    }

    private static void assertBoxEquals(SimpleCollisionBox expected, SimpleCollisionBox actual) {
        assertEquals(expected.minX, actual.minX);
        assertEquals(expected.minY, actual.minY);
        assertEquals(expected.minZ, actual.minZ);
        assertEquals(expected.maxX, actual.maxX);
        assertEquals(expected.maxY, actual.maxY);
        assertEquals(expected.maxZ, actual.maxZ);
        assertEquals(expected.isFullBlock(), actual.isFullBlock());
    }
}
