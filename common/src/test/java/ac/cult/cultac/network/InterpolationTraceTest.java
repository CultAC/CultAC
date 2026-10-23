package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Every entity box, position and rotation after each step of random packet and tick sequences, as one digest. */
class InterpolationTraceTest {
    static final int[] TYPES = {
        EntityTypeIds.ZOMBIE,
        EntityTypeIds.OAK_BOAT,
        EntityTypeIds.PIG,
        EntityTypeIds.ARMOR_STAND,
        EntityTypeIds.TNT,
        EntityTypeIds.SHULKER,
        EntityTypeIds.ITEM_DISPLAY,
        EntityTypeIds.FISHING_BOBBER,
        EntityTypeIds.PLAYER,
        EntityTypeIds.SLIME
    };

    @Test
    void legacyTrace() throws Exception {
        assertEquals(-1609902577819013976L, trace(ClientVersion.V_1_21_4, 11L));
    }

    @Test
    void oldClientTrace() throws Exception {
        assertEquals(3415501462072160507L, trace(ClientVersion.V_1_8, 12L));
    }

    @Test
    void steppedTrace() throws Exception {
        assertEquals(7915571620591440211L, trace(ClientVersion.V_26_3, 13L));
    }

    private static long trace(ClientVersion version, long seed) throws Exception {
        try (Workload w = new Workload(version, seed, 24)) {
            long digest = 0xcbf29ce484222325L;
            for (int i = 0; i < 6000; i++) {
                w.randomStep();
                digest = w.digest(digest);
            }
            return digest;
        }
    }

    /** Tracked entities fed packet movement and client ticks the way PacketEntityReplication applies them. */
    static final class Workload implements AutoCloseable {
        final JavaTickFixture fixture;
        final CultPlayer player;
        final CompensatedEntities tracker;
        final List<PacketEntity> entities = new ArrayList<>();
        final Random random;

        Workload(ClientVersion version, long seed, int count) throws Exception {
            fixture = new JavaTickFixture();
            player = fixture.player;
            JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", version);
            tracker = player.compensatedEntities;
            random = new Random(seed);
            for (int i = 0; i < count; i++) {
                double x = random.nextInt(9) - 4 + 0.5;
                double z = random.nextInt(9) - 4 + 0.5;
                int id = 100 + i;
                tracker.addEntity(id, TYPES[i % TYPES.length], new Vec3(x, JavaTickFixture.FLOOR_Y, z), 0, 0, 0);
                entities.add(tracker.getEntity(id));
            }
        }

        void tick(boolean reliably) {
            tracker.tickClientEntities(entity -> entity.onMovement(player, reliably));
        }

        void randomStep() {
            PacketEntity entity = entities.get(random.nextInt(entities.size()));
            Float yaw = random.nextInt(3) == 0 ? null : (float) (random.nextInt(16) * 22.5 - 180);
            Float pitch = yaw == null ? null : (float) (random.nextInt(7) * 15 - 45);
            double dx = (random.nextInt(65) - 32) / 64.0;
            double dy = random.nextInt(5) == 0 ? (random.nextInt(9) - 4) / 16.0 : 0.0;
            double dz = (random.nextInt(65) - 32) / 64.0;
            switch (random.nextInt(12)) {
                case 0, 1 -> entity.onFirstTransaction(true, random.nextInt(6) != 0, dx, dy, dz, yaw, pitch, player);
                case 2 -> entity.onBundleFirstTransaction(true, true, dx, dy, dz, yaw, pitch, player);
                case 3 ->
                    entity.onBundledPositionSyncTransaction(
                            entity.desyncClientPos.x + dx,
                            entity.desyncClientPos.y + dy,
                            entity.desyncClientPos.z + dz,
                            yaw,
                            pitch,
                            player);
                case 4 -> entity.onPositionPath(path(entity, dx, dy, dz), true, yaw, pitch, player, false);
                case 5 ->
                    entity.onPositionPath(path(entity, dx, dy, dz), random.nextBoolean(), yaw, pitch, player, true);
                case 6, 7 -> entity.onSecondTransaction();
                case 8 -> {
                    entity.isBaby = !entity.isBaby;
                    entity.refreshDimensions(player);
                }
                case 9 -> tick(false);
                default -> tick(true);
            }
        }

        private EntityPositionPath path(PacketEntity entity, double dx, double dy, double dz) {
            Vec3 base = entity.desyncClientPos;
            if (random.nextBoolean()) return EntityPositionPath.linear(new Vec3(base.x + dx, base.y + dy, base.z + dz));
            List<PositionPath.Step> steps = new ArrayList<>();
            int count = 1 + random.nextInt(3);
            for (int i = 1; i <= count; i++) {
                steps.add(new PositionPath.Step(
                        new Vec3d(base.x + dx * i / count, base.y + dy * i / count, base.z + dz * i / count),
                        1 + random.nextInt(2)));
            }
            return EntityPositionPath.fromProtocol(new PositionPath.Stepped(steps));
        }

        long digest(long digest) {
            for (PacketEntity entity : entities) {
                digest = mix(digest, entity.getPossibleCollisionBoxes());
                digest = mix(digest, entity.getPossibleMovementCollisionBoxes());
                digest = mix(digest, entity.getExactMovementCollisionBox());
                for (SimpleCollisionBox box : entity.getPossibleMovementCollisionBoxCandidates()) {
                    digest = mix(digest, box);
                }
                digest = mix(digest, entity.desyncClientPos);
                digest = mix(digest, entity.clientPhysicalPosition);
                digest = mix(digest, entity.clientPhysicalPositionExact ? 1 : 0);
                digest = mix(digest, Float.floatToRawIntBits(entity.clientPhysicalYaw));
                digest = mix(digest, Float.floatToRawIntBits(entity.clientPhysicalPitch));
                digest = mix(digest, entity.newPacketLocation);
                digest = mix(digest, entity.oldPacketLocation);
            }
            return digest;
        }

        private static long mix(long digest, ReachInterpolationData data) {
            if (data == null) return mix(digest, 7L);
            digest = mix(digest, data.toString().hashCode());
            digest = mix(digest, data.hasActiveInterpolationTarget() ? 1 : 0);
            digest = mix(digest, data.getExactInterpolationStep());
            digest = mix(digest, Float.floatToRawIntBits(data.getCurrentYaw()));
            digest = mix(digest, Float.floatToRawIntBits(data.getCurrentPitch()));
            return mix(digest, data.getTargetLocation());
        }

        private static long mix(long digest, SimpleCollisionBox box) {
            if (box == null) return mix(digest, 3L);
            digest = mix(digest, Double.doubleToRawLongBits(box.minX));
            digest = mix(digest, Double.doubleToRawLongBits(box.minY));
            digest = mix(digest, Double.doubleToRawLongBits(box.minZ));
            digest = mix(digest, Double.doubleToRawLongBits(box.maxX));
            digest = mix(digest, Double.doubleToRawLongBits(box.maxY));
            digest = mix(digest, Double.doubleToRawLongBits(box.maxZ));
            return mix(digest, box.isFullBlock() ? 1 : 0);
        }

        private static long mix(long digest, Vec3 position) {
            if (position == null) return mix(digest, 5L);
            digest = mix(digest, Double.doubleToRawLongBits(position.x));
            digest = mix(digest, Double.doubleToRawLongBits(position.y));
            return mix(digest, Double.doubleToRawLongBits(position.z));
        }

        private static long mix(long digest, long value) {
            return (digest ^ value) * 0x100000001b3L;
        }

        @Override
        public void close() throws Exception {
            fixture.close();
        }
    }
}
