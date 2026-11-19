package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * LivingEntity#getPassengerRidingPosition adds the PASSENGER attachment of getDimensions(pose), which
 * ArmorStand#getDefaultDimensions takes from the DATA_CLIENT_FLAGS the client holds: a marker's fixed 0 x 0
 * MARKER_DIMENSIONS, a small stand's BABY_DIMENSIONS (the type's 1.975 high attachment scaled by 0.5), else the type's;
 * the scale attribute then scales all but the fixed ones. Player's 0.6 VEHICLE attachment lowers the rider below it.
 * ArmorStand#onSyncedDataUpdated refreshes the dimensions whenever the flags change, even with a rider already seated.
 */
class JavaArmorStandSeatTest {
    private static final int STAND = 100;
    private static final Vec3 STAND_AT = new Vec3(0.5, FLOOR_Y + 2.0, 0.5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @CsvSource({
        "V_26_2, 0, 1.0, 1.975", "V_26_2, 1, 1.0, 0.9875", "V_26_2, 16, 1.0, 0", "V_26_2, 17, 1.0, 0",
        "V_26_2, 1, 2.0, 0.9875", "V_26_2, 16, 2.0, 0", "V_26_3, 1, 1.0, 0.9875", "V_26_3, 16, 1.0, 0",
        "V_1_21_9, 1, 1.0, 0.9875", "V_1_21_9, 16, 1.0, 0", "V_1_21_2, 1, 1.0, 0.9875", "V_1_21_2, 16, 1.0, 0"
    })
    void theRiderSitsAtTheStandsCurrentAttachment(ClientVersion version, int flags, double scale, double attachment)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.input(NONE);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, -0.08 * 0.98F, 0));
            f.player.compensatedEntities.addEntity(STAND, EntityTypeIds.ARMOR_STAND, STAND_AT, 0, 0, 0);
            if (scale != 1.0)
                f.player.compensatedEntities.updateAttributes(
                        STAND, List.of(new AttributeSnapshot("minecraft:scale", scale, List.of())));
            f.setPassengers(STAND, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
            f.rideTick();
            assertEquals(STAND_AT.y + 1.975 * scale - 0.6, f.player.compensatedEntities.lastRideTickSeat().y, 1.0E-6);

            // The flags reach the client after the passenger link; the next positionRider reads them.
            f.pongThrough(clientFlags(f, (byte) flags));
            f.rideTick();
            Vec3 seat = f.player.compensatedEntities.lastRideTickSeat();
            assertEquals(STAND_AT.x, seat.x, 1.0E-9);
            assertEquals(STAND_AT.y + attachment * (flags >= 16 ? 1.0 : scale) - 0.6, seat.y, 1.0E-6);
            assertEquals(STAND_AT.z, seat.z, 1.0E-9);
        }
    }

    /** ArmorStand's DATA_CLIENT_FLAGS; its proof transaction. */
    private static int clientFlags(JavaTickFixture f, byte flags) {
        var packet = new EntityMetadata(STAND, List.of(EntityMetadata.Entry.byteValue(15, flags)));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
