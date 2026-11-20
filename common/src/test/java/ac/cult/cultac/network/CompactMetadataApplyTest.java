package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.latency.BaseEntityMetadataApply;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Entity data applied by the current code from the entries its decoder builds leaves every entity exactly as the base
 * revision's code leaves it from the entries its decoder built, on random, duplicate, unknown-index and ill-typed data.
 */
class CompactMetadataApplyTest {
    private static final String[] TYPES = {
        "zombie",
        "pig",
        "strider",
        "horse",
        "camel",
        "llama",
        "shulker",
        "slime",
        "magma_cube",
        "phantom",
        "interaction",
        "armor_stand",
        "cat",
        "wolf",
        "item_frame",
        "glow_item_frame",
        "painting",
        "polar_bear",
        "nautilus",
        "happy_ghast",
        "firework_rocket",
        "fishing_bobber",
        "arrow",
        "spectral_arrow",
        "creaking",
        "spider",
        "cow",
        "block_display",
        "text_display",
        "player",
        "sheep",
        "ghast"
    };
    /** Rounds of unrestricted values, few enough that no throw site gets hot enough to lose its message. */
    private static final int THROWING_ROUNDS = 8;

    private static final int ROUNDS = 400;

    @Test
    void packedEntriesApplyTheStateTheBaseRevisionApplies() {
        int applied = 0, failed = 0, packed = 0;
        for (ProtocolVersion version :
                new ProtocolVersion[] {ProtocolVersion.V1_21_4, ProtocolVersion.V1_21_11, ProtocolVersion.V26_3}) {
            try (var current = fixture(version);
                    var base = fixture(version)) {
                var baseApply = new BaseEntityMetadataApply(base.player);
                List<Integer> ids = new ArrayList<>();
                for (String key : TYPES) {
                    var type = EntityTypes.defaults().byKey(key);
                    if (type == null) continue;
                    int id = 100 + ids.size();
                    ids.add(id);
                    for (var fixture : List.of(current, base))
                        fixture.player.compensatedEntities.addEntity(
                                id, type.id(), new Vec3(ids.size() * 4, 64, 0), 0, 0, 0);
                }
                var random = new Random(version.ordinal());
                for (int round = 0; round < THROWING_ROUNDS + ROUNDS; round++) {
                    boolean throwing = round < THROWING_ROUNDS;
                    for (int id : ids) {
                        var entity = current.player.compensatedEntities.getEntity(id);
                        var packet = Packet.random(random, throwing ? null : entity, version);
                        Applied expected = apply(base.player, id, packet.base(), baseApply);
                        Applied actual = apply(current.player, id, packet.decoded(), null);
                        String where = version + " entity " + id + " round " + round;
                        ImplicitThrows.assertSameThrow(expected.failure(), actual.failure(), where);
                        assertEquals(expected.state(), actual.state(), where);
                        if (packet.decoded() instanceof EntityMetadata.Entries) packed++;
                        if (expected.failure() != null) {
                            assertTrue(throwing, "values that never throw threw: " + expected);
                            failed++;
                        } else applied++;
                    }
                }
            }
        }
        assertTrue(applied > 10_000 && failed > 20 && packed > 5_000, applied + " " + failed + " " + packed);
    }

    private static RecordReceiveFixture fixture(ProtocolVersion version) {
        return version == ProtocolVersion.V26_3
                ? new RecordReceiveFixture(version)
                : RecordReceiveFixture.olderHost(version);
    }

    /** What applying threw, if anything, and the state it left. */
    private record Applied(RuntimeException failure, String state) {
        @Override
        public String toString() {
            return ImplicitThrows.describe(failure) + state;
        }
    }

    /** Applies through {@code base} when given, else through the current {@code updateEntityMetadata}. */
    private static Applied apply(
            CultPlayer player, int id, List<EntityMetadata.Entry> entries, BaseEntityMetadataApply base) {
        RuntimeException thrown = null;
        try {
            if (base == null) player.compensatedEntities.updateEntityMetadata(id, entries);
            else base.updateEntityMetadata(id, entries, player.lastTransactionSent.get() + 1);
        } catch (RuntimeException failure) {
            thrown = failure;
        }
        return new Applied(
                thrown,
                " entity " + dump(player.compensatedEntities.getEntity(id), 0, new IdentityHashMap<>())
                        + " open shulkers " + player.compensatedWorld.openShulkerBoxes.size()
                        + " fireworks " + dump(player.compensatedFireworks, 0, new IdentityHashMap<>()));
    }

    /**
     * One packet's entries: as the base revision's decoder built them ({@link List#of} of {@link
     * EntityMetadata.Entry#of} objects) and as the current one does.
     */
    private record Packet(List<EntityMetadata.Entry> base, List<EntityMetadata.Entry> decoded) {
        /**
         * Random entries; for a non-null {@code entity}, values at the indices its metadata casts unchecked have the
         * type it casts to.
         */
        static Packet random(Random random, PacketEntity entity, ProtocolVersion version) {
            int size = switch (random.nextInt(10)) {
                case 0 -> random.nextInt(3);
                case 1 -> 20 + random.nextInt(40);
                default -> 3 + random.nextInt(6);
            };
            int[] indices = new int[size], offsets = new int[size], lengths = new int[size];
            Object[] values = new Object[size];
            boolean gap = random.nextInt(20) == 0;
            for (int i = 0, cursor = 2; i < size; i++) {
                indices[i] = switch (random.nextInt(12)) {
                    case 0 -> 64 + random.nextInt(191);
                    case 1, 2 -> random.nextInt(8);
                    case 3 -> i > 0 ? indices[i - 1] : 0;
                    default -> 8 + random.nextInt(14);
                };
                values[i] = entity == null ? value(random) : typed(random, entity, version, indices[i]);
                offsets[i] = cursor;
                lengths[i] = 1 + random.nextInt(3);
                cursor += lengths[i] + (gap && i == 1 ? 1 : 0);
            }
            byte[] payload = new byte[size == 0 ? 2 : offsets[size - 1] + lengths[size - 1] + 2];
            random.nextBytes(payload);
            var plain = new EntityMetadata.Entry[size];
            for (int i = 0; i < size; i++)
                plain[i] = EntityMetadata.Entry.of(indices[i], values[i], payload, offsets[i], lengths[i]);
            return new Packet(
                    List.of(plain), EntityMetadata.entries(size, indices, values, offsets, lengths, payload, 0));
        }

        private static Object typed(Random random, PacketEntity entity, ProtocolVersion version, int index) {
            if (entity instanceof PacketEntityShulker) {
                if (index == WatchableIndexUtil.SHULKER_ATTACH_FACE)
                    return Direction.values()[random.nextInt(Direction.values().length)];
                if (index == WatchableIndexUtil.SHULKER_PEEK) return (byte) random.nextInt(101);
            }
            if (entity instanceof PacketEntityRideable) {
                var layout = WatchableIndexUtil.forModel(version);
                int saddle = entity.type == EntityTypeIds.PIG ? layout.pigSaddle() : layout.striderSaddle();
                if (index == saddle) return random.nextBoolean();
                if (index == WatchableIndexUtil.MOB_FLAGS) return (byte) random.nextInt();
            }
            return value(random);
        }
    }

    private static Object value(Random random) {
        return switch (random.nextInt(14)) {
            case 0 -> null;
            case 1 -> (byte) random.nextInt();
            case 2 -> random.nextInt(40) - 5;
            case 3 -> random.nextFloat() * 30 - 5;
            case 4 -> random.nextBoolean();
            case 5 -> EntityPose.values()[random.nextInt(EntityPose.values().length)];
            case 6 -> random.nextBoolean() ? OptionalInt.empty() : OptionalInt.of(random.nextInt(200));
            case 7 -> random.nextBoolean() ? Optional.empty() : Optional.of(new BlockPos(random.nextInt(9), 64, 2));
            case 8 -> Direction.values()[random.nextInt(Direction.values().length)];
            case 9 -> random.nextLong();
            case 10 -> "text";
            case 11 -> new PaintingSize(1 + random.nextInt(3), 1);
            case 12 -> (byte) (random.nextBoolean() ? 0 : 1);
            default -> random.nextInt(3);
        };
    }

    private static String dump(Object value, int depth, Map<Object, Boolean> seen) {
        if (value == null) return "null";
        Class<?> type = value.getClass();
        if (value instanceof CultPlayer || value instanceof User) return type.getSimpleName();
        if (type.isEnum()
                || type.isRecord()
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof CharSequence
                || value instanceof Optional<?>
                || value instanceof OptionalInt
                || value instanceof BlockPos
                || value instanceof Vec3) return value.toString();
        if (value instanceof Collection<?> collection) {
            if (depth > 3) return "size " + collection.size();
            var text = new StringBuilder("[");
            for (Object element : collection)
                text.append(dump(element, depth + 1, seen)).append(',');
            return text.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) return "map " + map.size();
        if (type.isArray()) return type.getComponentType().getSimpleName() + "[]";
        if (depth > 3 || seen.put(value, true) != null || type.getName().startsWith("java.")) return type.getName();
        var text = new StringBuilder(type.getSimpleName()).append('{');
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    text.append(field.getName())
                            .append('=')
                            .append(dump(field.get(value), depth + 1, seen))
                            .append(',');
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    text.append(field.getName()).append("=?,");
                }
            }
        return text.append('}').toString();
    }
}
