package ac.cult.cultac.events.packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestProtocolRuntime;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Entity data queued as its packet against the capturing lambda, on two identical players fed the same packets,
 * pings, answers and other tasks: the same metadata calls with the same arguments in the same order among the other
 * tasks, and the same pings. Covers answers before the packet (the task runs in place), Bedrock proofs, self health
 * and a task that throws, both in place and queued.
 */
class EntityDataTaskEquivalenceTest {
    private static final int SELF = 7;
    private static final int ZOMBIE = 4000;
    private static final int UNKNOWN = 5000;
    private static final int THROWS = 4001;
    private static final PacketType<EntityMetadata> METADATA = metadataType();

    @Test
    void queuedPacketsApplyAsTheLambdaDid() throws Exception {
        int threwInPlace = 0;
        for (long seed = 1; seed <= 6; seed++) {
            Random random = new Random(seed);
            boolean bedrock = seed % 3 == 0;
            Side queued = new Side(bedrock, false);
            Side lambda = new Side(bedrock, true);
            try {
                List<EntityMetadata> packets = new ArrayList<>();
                int waited = 0, inPlace = 0;
                for (int step = 0; step < 3000; step++) {
                    int action = random.nextInt(10);
                    if (action < 5) {
                        EntityMetadata packet = packet(random);
                        packets.add(packet);
                        boolean answered = queued.player.lastTransactionReceived.get()
                                >= queued.player.lastTransactionSent.get() + 1;
                        queued.metadata(packet, packets.size() - 1);
                        lambda.metadata(packet, packets.size() - 1);
                        if (!answered) waited++;
                        else if (packet.id() == THROWS) threwInPlace++;
                        else inPlace++;
                    } else if (action < 7) {
                        int marker = step;
                        int offset = random.nextInt(2);
                        queued.marker(marker, offset);
                        lambda.marker(marker, offset);
                    } else if (action < 9) {
                        int through = random.nextInt(3) == 0 ? 0 : 1 + random.nextInt(3);
                        queued.acknowledge(through);
                        lambda.acknowledge(through);
                    } else if (random.nextInt(4) == 0) {
                        queued.acknowledgeAll();
                        lambda.acknowledgeAll();
                    }
                    assertEquals(lambda.log, queued.log, "seed " + seed + " step " + step);
                    assertEquals(lambda.player.lastTransactionSent.get(), queued.player.lastTransactionSent.get());
                    assertEquals(
                            lambda.player.lastTransactionReceived.get(), queued.player.lastTransactionReceived.get());
                }
                queued.acknowledgeAll();
                lambda.acknowledgeAll();
                assertEquals(lambda.log, queued.log, "seed " + seed + " drained");
                assertTrue(waited > 300 && inPlace > 20, "waited " + waited + " in place " + inPlace);
            } finally {
                queued.close();
                lambda.close();
            }
        }
        assertTrue(threwInPlace > 5, "threw in place " + threwInPlace);
    }

    private static EntityMetadata packet(Random random) {
        int pick = random.nextInt(10);
        int id = pick < 4 ? ZOMBIE : pick < 6 ? SELF : pick < 9 ? UNKNOWN : THROWS;
        List<EntityMetadata.Entry> entries = new ArrayList<>();
        byte flags = (byte) random.nextInt(4);
        entries.add(EntityMetadata.Entry.of(0, flags, new byte[] {flags}, 0, 1));
        if (id == SELF && random.nextBoolean())
            entries.add(EntityMetadata.Entry.of(
                    WatchableIndexUtil.LIVING_HEALTH, 4.0F + random.nextInt(16), new byte[4], 0, 4));
        if ((id == UNKNOWN || id == THROWS) && random.nextBoolean())
            entries.add(EntityMetadata.Entry.of(WatchableIndexUtil.ENTITY_POSE, 0, new byte[] {0}, 0, 1));
        return new EntityMetadata(id, entries);
    }

    /** One player; {@code lambda} runs the handler with the capturing lambda. */
    private static final class Side {
        final CultPlayer player;
        final boolean lambda;
        final List<String> log = new ArrayList<>();
        final Map<List<EntityMetadata.Entry>, Integer> indexOf = new IdentityHashMap<>();

        Side(boolean bedrock, boolean lambda) {
            this.lambda = lambda;
            player = player(bedrock);
            player.compensatedEntities = new LoggingEntities(player, this);
            player.packetEntityReplication.addEntity(ZOMBIE, EntityTypeIds.ZOMBIE, new Vec3(0.5, 64, 0.5), 0, 0, 0, -1);
            player.packetEntityReplication.addEntity(THROWS, EntityTypeIds.ZOMBIE, new Vec3(2.5, 64, 0.5), 0, 0, 0, -1);
            acknowledgeAll();
        }

        void metadata(EntityMetadata packet, int index) throws Exception {
            indexOf.put(packet.packedItems(), index);
            var event = new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, METADATA, packet, false);
            try {
                if (lambda) Lambda.onSetEntityData(player.packetEntityReplication, event, player, packet);
                else player.packetEntityReplication.onSetEntityData(event, player, packet);
            } catch (InvocationTargetException thrown) {
                log.add("threw " + thrown.getCause());
            } catch (RuntimeException thrown) {
                log.add("threw " + thrown);
            }
            log.add("sent " + index + " writes " + event.writesAfter().size() + " tasks "
                    + event.tasksAfter().size() + " bundle " + event.isBundleRequested());
            event.tasksAfter().forEach(Runnable::run);
        }

        void marker(int marker, int offset) {
            player.latencyUtils.addRealTimeTask(
                    player.lastTransactionSent.get() + offset, () -> log.add("marker " + marker));
        }

        void acknowledge(int through) {
            int transaction =
                    Math.min(player.lastTransactionSent.get(), player.lastTransactionReceived.get() + through);
            if (transaction <= player.lastTransactionReceived.get()) return;
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        void acknowledgeAll() {
            int transaction = Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get()) + 1;
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        void close() {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) {
                embedded.runPendingTasks();
                embedded.finishAndReleaseAll();
            }
        }

        private static CultPlayer player(boolean bedrock) {
            OfflineCultTestBootstrap.installConfig();
            PlatformConnection platform = new PlatformConnection() {
                @Override
                public User.Profile authenticatedProfile() {
                    return null;
                }

                @Override
                public PlayerBinding playerBinding() {
                    return null;
                }

                @Override
                public ProtocolVersion getObservedProtocol() {
                    return ProtocolVersion.V1_21_11;
                }

                @Override
                public ProtocolVersion getClientProtocol() {
                    return ProtocolVersion.V1_21_11;
                }

                @Override
                public void disconnect(Component reason) {}

                @Override
                public void sendMessage(Component message) {}
            };
            User user = TestUsers.create(
                    new User.Profile(UUID.randomUUID(), "Entity_Data_Task"), new EmbeddedChannel(), platform);
            CultPlayer player = new CultPlayer(user);
            user.bind(Mockito.mock(PlatformPlayer.class));
            player.entityID = SELF;
            if (bedrock) player.movementPlatform = MovementPlatform.BEDROCK;
            return player;
        }
    }

    private static final class LoggingEntities extends CompensatedEntities {
        private final Side side;

        LoggingEntities(CultPlayer player, Side side) {
            super(player);
            this.side = side;
        }

        @Override
        public void updateEntityMetadata(int entityID, List<EntityMetadata.Entry> watchableObjects, int provenAt) {
            side.log.add("apply " + entityID + " packet " + side.indexOf.get(watchableObjects) + " at " + provenAt);
            if (entityID == THROWS) throw new IllegalStateException("metadata " + provenAt);
            super.updateEntityMetadata(entityID, watchableObjects, provenAt);
        }

        @Override
        public PacketEntity getEntity(int entityID) {
            side.log.add("get " + entityID);
            return super.getEntity(entityID);
        }
    }

    /** PacketEntityReplication#onSetEntityData with the capturing lambda, its proof calls reached by reflection. */
    private static final class Lambda {
        private static final Method PROVE_HANDLED;
        private static final Method ENTITY_PROOF;
        private static final Method RETIRE_POSE_AT;
        private static final Object BEFORE_NEXT_TICK;

        static {
            try {
                Class<?> proof = Class.forName(PacketEntityReplication.class.getName() + "$Proof");
                PROVE_HANDLED =
                        PacketEntityReplication.class.getDeclaredMethod("proveHandled", PacketSendEvent.class, proof);
                ENTITY_PROOF = PacketEntityReplication.class.getDeclaredMethod("entityProof");
                RETIRE_POSE_AT = PacketEntityReplication.class.getDeclaredMethod(
                        "retirePoseAt", int.class, List.class, int.class);
                PROVE_HANDLED.setAccessible(true);
                ENTITY_PROOF.setAccessible(true);
                RETIRE_POSE_AT.setAccessible(true);
                BEFORE_NEXT_TICK = Arrays.stream(proof.getEnumConstants())
                        .filter(kind -> ((Enum<?>) kind).name().equals("BEFORE_NEXT_TICK"))
                        .findFirst()
                        .orElseThrow();
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static void onSetEntityData(
                PacketEntityReplication replication,
                PacketSendEvent<EntityMetadata> event,
                CultPlayer player,
                EntityMetadata packet)
                throws ReflectiveOperationException {
            List<EntityMetadata.Entry> metadata = packet.packedItems();
            boolean selfHealth = !player.isBedrockMovement()
                    && packet.id() == player.entityID
                    && WatchableIndexUtil.has(metadata, WatchableIndexUtil.LIVING_HEALTH);
            int proven = (int) PROVE_HANDLED.invoke(
                    replication, event, selfHealth ? BEFORE_NEXT_TICK : ENTITY_PROOF.invoke(replication));
            player.latencyUtils.addRealTimeTask(
                    proven, () -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata, proven));
            RETIRE_POSE_AT.invoke(replication, packet.id(), metadata, proven);
        }
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
