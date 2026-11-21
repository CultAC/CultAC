package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * PacketEntity's memoized movement bounds always equal the bounds read from its snapshots: every write to either
 * snapshot, and every replacement of one, reaches the entity's memo.
 */
class MovementBoundsMemoTest {
    private static final int[] TYPES = {
        EntityTypeIds.ZOMBIE,
        EntityTypeIds.OAK_BOAT,
        EntityTypeIds.ARMOR_STAND,
        EntityTypes.defaults().byKey("minecraft:minecart").id(),
        EntityTypeIds.PIG
    };
    private static final Method MEMO = memoMethod();

    private static Method memoMethod() {
        try {
            Method method = PacketEntity.class.getDeclaredMethod(
                    "interpolationMovementBounds", SimpleCollisionBox.class, SimpleCollisionBox.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void memoMatchesSnapshotsAfterEveryWrite() throws Exception {
        int memoHits = 0;
        for (ClientVersion version : List.of(ClientVersion.V_26_3, ClientVersion.V_1_21_4)) {
            for (long seed = 1; seed <= 6; seed++) memoHits += run(seed * 31 + version.ordinal(), version, 600);
        }
        assertTrue(memoHits > 0, "no operation ever changed an entity's bounds");
    }

    // The memo keeps no snapshot alive past the fields that hold it.
    @Test
    void memoHoldsNoSnapshot() {
        for (Field field : PacketEntity.class.getDeclaredFields()) {
            if (field.getName().startsWith("movementBounds"))
                assertTrue(field.getType().isPrimitive(), field.getName());
        }
    }

    private int run(long seed, ClientVersion version, int steps) throws Exception {
        int dropped = 0;
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            CompensatedEntities entities = player.compensatedEntities;
            JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", version);
            f.place(new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5), Vec3.ZERO);
            List<List<ReachInterpolationData>> history = new ArrayList<>();
            for (int t = 0; t < TYPES.length; t++) {
                entities.addEntity(10 + t, TYPES[t], new Vec3(t * 2 + 0.5, 64, 2.5), 0, 0, 0);
                history.add(new ArrayList<>());
            }
            Random random = new Random(seed);
            for (int step = 0; step < steps; step++) {
                for (int id = 10; id < 10 + TYPES.length; id++) {
                    if (random.nextInt(3) != 0) memo(entities.getEntity(id));
                }
                List<String> before = new ArrayList<>();
                for (int id = 10; id < 10 + TYPES.length; id++) before.add(fresh(entities.getEntity(id)));
                apply(player, entities, history, random);
                for (int id = 10; id < 10 + TYPES.length; id++) {
                    PacketEntity entity = entities.getEntity(id);
                    String want = fresh(entity);
                    assertEquals(want, memo(entity), version + " seed=" + seed + " step=" + step + " id=" + id);
                    if (!want.equals(before.get(id - 10))) dropped++;
                }
            }
        }
        return dropped;
    }

    private static void apply(
            CultPlayer player, CompensatedEntities entities, List<List<ReachInterpolationData>> history, Random random) {
        int index = random.nextInt(TYPES.length);
        PacketEntity entity = entities.getEntity(10 + index);
        ReachInterpolationData data = random.nextInt(4) == 0 && entity.oldPacketLocation != null
                ? entity.oldPacketLocation
                : entity.newPacketLocation;
        int kind = random.nextInt(100);
        if (kind < 35) {
            boolean reliably = random.nextInt(5) != 0;
            for (int id = 10; id < 10 + TYPES.length; id++) entities.getEntity(id).onMovement(player, reliably);
        } else if (kind < 70) {
            history.get(index).add(data);
            mutate(player, entity, data, random);
        } else if (kind < 75) {
            history.get(index).add(entity.newPacketLocation);
            entity.tickPacketInterpolationPreservingPhysical(player, feet(box(player, entity, random)), data);
        } else if (kind < 80) {
            List<ReachInterpolationData> earlier = history.get(index);
            if (!earlier.isEmpty()) entity.newPacketLocation = earlier.get(random.nextInt(earlier.size()));
        } else if (kind < 88) {
            entity.oldPacketLocation = entity.newPacketLocation.copy();
        } else if (kind < 92) {
            entity.oldPacketLocation = entity.newPacketLocation;
        } else {
            entity.onSecondTransaction();
        }
    }

    private static void mutate(CultPlayer player, PacketEntity entity, ReachInterpolationData data, Random random) {
        float yaw = random.nextInt(360) - 180;
        float pitch = random.nextInt(60) - 30;
        Vec3 movement = new Vec3(
                random.nextInt(9) / 8.0 - 0.5, random.nextInt(5) / 8.0 - 0.25, random.nextInt(9) / 8.0 - 0.5);
        switch (random.nextInt(15)) {
            case 0 -> {
                if (!data.usesSteppedInterpolation()) return;
                EntityPositionPath path = random.nextBoolean()
                        ? EntityPositionPath.linear(feet(box(player, entity, random)))
                        : new EntityPositionPath(
                                feet(box(player, entity, random)),
                                List.of(
                                        new EntityPositionPath.Step(feet(box(player, entity, random)), 1),
                                        new EntityPositionPath.Step(feet(box(player, entity, random)), 2)));
                if (random.nextBoolean())
                    data.setPhysicalBeforePathAppend(path, yaw, pitch, feet(box(player, entity, random)), yaw, pitch);
                data.appendPath(path, yaw, pitch);
            }
            case 1 -> data.setExactMovementLocation(box(player, entity, random));
            case 2 -> data.shiftTargetIfCollisionFree(player, movement);
            case 3 -> data.shiftRootVehicleTargetIfCollisionFree(player, entity, movement);
            case 4 -> data.setExactRotation(yaw, pitch);
            case 5 -> data.updatePossibleStartingLocation(box(player, entity, random));
            case 6 -> data.tickMovement(random.nextBoolean());
            case 7 -> data.offset(v -> v.add(movement));
            case 8 -> data.resize(0.25F + random.nextInt(8) / 8.0F, 0.25F + random.nextInt(16) / 8.0F);
            case 9 -> data.holdBedrockShownLocation(box(player, entity, random));
            case 10 -> {
                data.dueBedrockStoredFeet(y -> y + movement.y);
                data.rebaseBedrock(1.0 + random.nextInt(8) / 8.0);
            }
            case 11 -> data.resetFinished(box(player, entity, random));
            case 12 -> {
                SimpleCollisionBox start = box(player, entity, random);
                Vec3 to = feet(box(player, entity, random));
                data.restart(player, start, to.x, to.y, to.z, yaw, pitch, yaw, pitch, entity);
            }
            case 13 -> data.setCurrentAndTargetRotation(yaw, pitch);
            default -> {
                if (!data.usesSteppedInterpolation()) return;
                data.setPhysicalBeforePathAppend(
                        EntityPositionPath.linear(feet(box(player, entity, random))),
                        yaw,
                        pitch,
                        feet(box(player, entity, random)),
                        yaw,
                        pitch);
            }
        }
    }

    private static String memo(PacketEntity entity) throws ReflectiveOperationException {
        return bits((SimpleCollisionBox)
                MEMO.invoke(entity, new SimpleCollisionBox(), new SimpleCollisionBox()));
    }

    private static String fresh(PacketEntity entity) {
        SimpleCollisionBox out = entity.newPacketLocation.getPossibleMovementLocationCombined(new SimpleCollisionBox());
        if (entity.oldPacketLocation != null) {
            ReachInterpolationData.combineCollisionBox(
                    entity.oldPacketLocation.getPossibleMovementLocationCombined(new SimpleCollisionBox()), out, out);
        }
        return bits(out);
    }

    private static String bits(SimpleCollisionBox box) {
        return Long.toHexString(Double.doubleToRawLongBits(box.minX)) + ' '
                + Long.toHexString(Double.doubleToRawLongBits(box.minY)) + ' '
                + Long.toHexString(Double.doubleToRawLongBits(box.minZ)) + ' '
                + Long.toHexString(Double.doubleToRawLongBits(box.maxX)) + ' '
                + Long.toHexString(Double.doubleToRawLongBits(box.maxY)) + ' '
                + Long.toHexString(Double.doubleToRawLongBits(box.maxZ)) + ' '
                + box.isFullBlock();
    }

    private static SimpleCollisionBox box(CultPlayer player, PacketEntity entity, Random random) {
        return GetBoundingBox.getPacketEntityBoundingBox(
                player, coordinate(random, -4), 64 + coordinate(random, 0), coordinate(random, -4), entity);
    }

    private static double coordinate(Random random, double offset) {
        return offset + (random.nextBoolean() ? random.nextInt(65) / 8.0 : random.nextDouble() * 8);
    }

    private static Vec3 feet(SimpleCollisionBox box) {
        return new Vec3((box.minX + box.maxX) / 2.0D, box.minY, (box.minZ + box.maxZ) / 2.0D);
    }
}
