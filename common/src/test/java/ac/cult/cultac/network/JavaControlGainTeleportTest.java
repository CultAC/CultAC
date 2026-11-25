package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The player rides an unsaddled horse after standing on the floor. The saddle that gives the player control and a
 * teleport whose onGround is false reach the client together. ClientPacketListener#handleSetEquipment saddles the
 * horse first, so AbstractHorse#getControllingPassenger is the player when #handleTeleportEntity interpolates the
 * horse and Entity#setOnGround(false) clears its Entity#mainSupportingBlockPos. The first controlled travel is in the
 * air, at LivingEntity#getFlyingSpeed; its MoveVehicle reports the interpolation target, and the next one that target
 * shifted by the air travel.
 */
class JavaControlGainTeleportTest {
    private static final int HORSE = 100;
    private static final Vec3 HORSE_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 TARGET = new Vec3(0.5, FLOOR_Y, 1.5);
    private static final float SPEED = 0.2F;
    /** LocalPlayer's 0.98 forward impulse at getFlyingSpeed, AbstractHorse#getRiddenSpeed * 0.1F. */
    private static final double AIR_STEP = 0.98F * (double) (SPEED * 0.1F);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_2", "V_26_3"})
    void theFirstControlledTravelStartsFromTheTeleportsGround(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.input(NONE);
            f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
            f.place(HORSE_AT, new Vec3(0, -0.08 * 0.98F, 0));
            PredictionResult standing = f.tick(HORSE_AT, true);
            assertTrue(exact(standing), describe(standing));

            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, HORSE_AT, 0, 0, 0);
            f.player.compensatedEntities.updateAttributes(
                    HORSE, List.of(new AttributeSnapshot("minecraft:movement_speed", SPEED, List.of())));
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick();
            // LivingEntity#travelRidden reads the zza the rider's previous aiStep left.
            f.input(FORWARD);
            f.rideTick();
            f.rideTick();

            // Neither is answered before the client handles both, the saddle first.
            saddle(f);
            teleport(f, TARGET);
            f.pongThrough(f.player.lastTransactionSent.get());

            assertVehicleMove(f.rideTick(TARGET, false), version + " first controlled tick");
            assertVehicleMove(f.rideTick(TARGET.add(0, 0, AIR_STEP), true), version + " shifted target");
        }
    }

    /** The simulated vehicle move meets the report; the fixture's ridden GroundSpoof is not under test. */
    private static void assertVehicleMove(PredictionResult result, String name) {
        assertFalse(result.isExempt(), name + ": " + describe(result));
        assertTrue(result.getOffset() < 1.0E-5, name + ": " + describe(result));
        assertTrue(
                result.getFlags().stream()
                        .noneMatch(flag -> flag.getCheck().getCheckName().equals("VehicleOffset")
                                || flag.getCheck().getCheckName().equals("Simulation")),
                name + ": " + describe(result));
    }

    @SuppressWarnings("unchecked")
    private static void saddle(JavaTickFixture f) {
        var saddle = ItemUtil.modelItems().stack("minecraft:saddle", 1);
        var packet = new InventoryPackets.Equipment(
                HORSE, List.of(new InventoryPackets.EquipmentEntry(EquipmentSlot.SADDLE, saddle)));
        var type = (PacketType<InventoryPackets.Equipment>) TestProtocolRuntime.catalog().stream()
                .filter(candidate -> candidate.recordClass() == InventoryPackets.Equipment.class)
                .findFirst()
                .orElseThrow();
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        f.player.packetEntityReplication.onSetEquipment(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void teleport(JavaTickFixture f, Vec3 to) {
        var packet =
                new ClientboundTeleportEntity(HORSE, new Vec3d(to.x, to.y, to.z), Vec3d.ZERO, 0, 0, Set.of(), false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }
}
