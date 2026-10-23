package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LivingEntity#handleEntityEvent(3) kills a ridden horse once the client reads it; a dead root takes no ridden input. */
class JavaDeadVehicleTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);
    private static final int HORSE = 100;
    private static final byte DEATH = 3;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void aLivingVehicleDiesWhenTheClientReadsItsDeathEvent() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity zombie = add(f, 101, EntityTypeIds.ZOMBIE);
            PacketEntity tnt = add(f, 102, EntityTypeIds.TNT);

            entityEvent(f, 102, DEATH);
            assertTrue(tnt.isDead, "a non-living entity's event 3 waits for no proof");

            entityEvent(f, 101, DEATH);
            assertFalse(zombie.isDead, "dead once the client reads it");
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(zombie.isDead);
        }
    }

    /** LivingEntity#aiStep runs travelRidden only while the root isAlive(); a dead root's immobile input is zero. */
    @Test
    void aDeadHorseDoesNotMoveOnItsRidersInput() throws Exception {
        try (var f = ridden()) {
            kill(f);
            f.input(FORWARD);
            // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
            assertTrue(exact(f.rideTick(SEAT, true)));
            PredictionResult still = f.rideTick(SEAT, true);
            assertTrue(exact(still), describe(still));
        }
    }

    /** AbstractHorse#tickRidden runs the rider's jump inside travelRidden. */
    @Test
    void aDeadHorseDoesNotJump() throws Exception {
        try (var f = ridden()) {
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE)).horseJump = 1.0F;
            kill(f);
            f.input(FORWARD_JUMP);
            // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
            assertTrue(exact(f.rideTick(SEAT, true)));
            PredictionResult still = f.rideTick(SEAT, true);
            assertTrue(exact(still), describe(still));
        }
    }

    @Test
    void aLivingHorseMovesOnItsRidersInput() throws Exception {
        try (var f = ridden()) {
            f.input(FORWARD);
            assertTrue(exact(f.rideTick(SEAT, true)));
            PredictionResult still = f.rideTick(SEAT, true);
            assertFalse(exact(still), describe(still));
        }
    }

    /**
     * LivingEntity#isAlive reads health, not the dead flag event 3 also sets (isDeadOrDying); positive
     * health lets travelRidden take the rider's input again.
     */
    @Test
    void aHealedHorseMovesOnItsRidersInputAgain() throws Exception {
        try (var f = ridden()) {
            kill(f);
            setHealth(f, HORSE, 20.0F);
            f.input(FORWARD);
            assertTrue(exact(f.rideTick(SEAT, true)));
            PredictionResult moved = f.rideTick(SEAT, true);
            assertFalse(exact(moved), describe(moved));
        }
    }

    /** A saddled horse at {@link #SEAT} mounted through set_passengers, after one stationary ridden tick. */
    private static JavaTickFixture ridden() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        ((PacketEntityHorse) add(f, HORSE, EntityTypeIds.HORSE)).hasSaddle = true;
        var packet = new ClientboundSetPassengers(HORSE, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(exact(f.rideTick(SEAT, true)));
        return f;
    }

    private static void kill(JavaTickFixture f) {
        entityEvent(f, HORSE, DEATH);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getEntity(HORSE).isDead);
    }

    private static void setHealth(JavaTickFixture f, int entityId, float health) {
        var packet = new EntityMetadata(
                entityId,
                List.of(new EntityMetadata.Entry(WatchableIndexUtil.LIVING_HEALTH, health, ByteBuffer.allocate(0))));
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

    private static PacketEntity add(JavaTickFixture f, int id, int type) {
        f.player.compensatedEntities.addEntity(id, type, SEAT, 0, 0, 0);
        return f.player.compensatedEntities.getEntity(id);
    }

    private static void entityEvent(JavaTickFixture f, int entityId, byte status) {
        var packet = new ClientboundEntityEvent(entityId, status);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }
}
