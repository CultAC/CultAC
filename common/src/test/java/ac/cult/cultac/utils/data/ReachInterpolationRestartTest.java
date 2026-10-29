package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ReachInterpolationRestartTest {
    private static final SimpleCollisionBox FIRST = new SimpleCollisionBox(-0.3, 64, -0.3, 0.3, 65.95, 0.3);
    private static final SimpleCollisionBox SECOND = new SimpleCollisionBox(2.2, 63.5, 7.45, 2.8, 65.45, 8.05);
    private static final ClientVersion[] VERSIONS = {ClientVersion.V_1_21_11, ClientVersion.V_26_3};
    private static final int[] TYPES = {
        EntityTypeIds.ZOMBIE, EntityTypeIds.ITEM, EntityTypeIds.FISHING_BOBBER, EntityTypeIds.SHULKER
    };

    @Test
    void aWornSnapshotRestartsToTheStateTakingStartBuilds() {
        for (ClientVersion version : VERSIONS) {
            CultPlayer player = player(version);
            for (int type : TYPES) {
                PacketEntity entity = new PacketEntity(type, 1);
                for (int variant = 0; variant < 4; variant++) {
                    boolean pointNine = (variant & 1) == 0;
                    boolean uncertain = (variant & 2) == 0;
                    ReachInterpolationData worn = ReachInterpolationData.takingStart(
                            player, FIRST.copy(), 0, 64, 0, 10, 5, 40, -5, true, entity, true);
                    wear(worn);
                    assertTrue(worn.restart(
                            player, SECOND.copy(), 5.5, 63.5, 7.75, 40, -5, 95, 12, pointNine, entity, uncertain));
                    ReachInterpolationData fresh = ReachInterpolationData.takingStart(
                            player, SECOND.copy(), 5.5, 63.5, 7.75, 40, -5, 95, 12, pointNine, entity, uncertain);
                    assertSameState(fresh, worn, version + " " + type + " " + variant);
                }
            }
        }
    }

    @Test
    void aFinishedSnapshotDoesNotRestart() {
        CultPlayer player = player(ClientVersion.V_1_21_11);
        PacketEntity entity = new PacketEntity(EntityTypeIds.ZOMBIE, 1);
        ReachInterpolationData finished = new ReachInterpolationData(FIRST);
        assertFalse(finished.restart(player, SECOND.copy(), 5.5, 63.5, 7.75, 0, 0, 0, 0, true, entity, true));
        assertSameState(new ReachInterpolationData(FIRST), finished, "finished");
    }

    private static CultPlayer player(ClientVersion version) {
        CultPlayer player = mock(CultPlayer.class);
        when(player.getClientVersion()).thenReturn(version);
        player.packetStateData = new PacketStateData();
        return player;
    }

    private static void wear(ReachInterpolationData data) {
        data.getPossibleMovementLocationCombined(new SimpleCollisionBox());
        if (data.usesSteppedInterpolation()) {
            data.appendPath(EntityPositionPath.linear(new Vec3(1.25, 64.5, -0.75)), 20, 3);
        }
        data.tickMovement(true);
        data.getPossibleMovementLocationCombined(new SimpleCollisionBox());
        data.setExactMovementLocation(new SimpleCollisionBox(0.2, 64, 0.2, 0.8, 65.95, 0.8));
        data.setExactRotation(15, 2);
        data.offset(feet -> new Vec3(0.125, -0.5, 0.25));
        data.resize(0.9F, 1.4F);
        data.tickMovement(true);
        if (data.beginIdleProbe()) data.settleIfIdleTickUnchanged();
        data.markPositionInexact();
        data.getPossibleMovementLocationCombined(new SimpleCollisionBox());
    }

    private static void assertSameState(ReachInterpolationData expected, ReachInterpolationData actual, String at) {
        int compared = 0;
        for (Field field : ReachInterpolationData.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object want;
            Object got;
            try {
                want = field.get(expected);
                got = field.get(actual);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            String name = at + " " + field.getName();
            if (want instanceof SimpleCollisionBox wantBox && got instanceof SimpleCollisionBox gotBox) {
                assertEquals(boxBits(wantBox), boxBits(gotBox), name);
            } else if (want instanceof ArrayDeque<?> wantQueue && got instanceof ArrayDeque<?> gotQueue) {
                assertEquals(new ArrayList<>(wantQueue), new ArrayList<>(gotQueue), name);
            } else if (want instanceof Double || want instanceof Float) {
                assertEquals(want, got, name);
            } else {
                assertTrue(Objects.equals(want, got), name + ": " + want + " != " + got);
            }
            compared++;
        }
        assertFalse(compared == 0);
    }

    private static String boxBits(SimpleCollisionBox box) {
        return Double.doubleToRawLongBits(box.minX) + "," + Double.doubleToRawLongBits(box.minY) + ","
                + Double.doubleToRawLongBits(box.minZ) + "," + Double.doubleToRawLongBits(box.maxX) + ","
                + Double.doubleToRawLongBits(box.maxY) + "," + Double.doubleToRawLongBits(box.maxZ) + ","
                + box.isFullBlock();
    }
}
