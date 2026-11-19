package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A Shulker attached to the wall west of a saddled horse the player rides opens east. It ticks before the horse in
 * ClientLevel#tickEntities, so Shulker#onPeekAmountChange moves the horse with Entity#move(MoverType.SHULKER) before
 * its controlled travel, and LocalPlayer#tick then sends the rider's Rot followed by the horse's MoveVehicle. The Rot
 * carries no vehicle travel: the shove belongs to the MoveVehicle that follows it.
 */
class JavaRiddenShulkerPeekTest {
    private static final int SHULKER = 100;
    private static final int VEHICLE = 101;
    private static final Vec3 SHULKER_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    /** The horse's west face 0.001 east of the closed shell, so the first peek step already reaches it. */
    private static final Vec3 HORSE_AT = new Vec3(1.001 + 1.3964844F / 2.0, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_2", "V_26_3"})
    void theRidersRotLeavesTheShoveToTheVehicleMove(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(HORSE_AT, Vec3.ZERO);
            f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, java.util.Map.of()));
            // ClientLevel#tickEntities runs the entities in the order they were added.
            f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, SHULKER_AT, 0, 0, 0);
            attachFace(f, Direction.WEST);
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.HORSE, HORSE_AT, 0, 0, 0);
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            // Past the vehicle-switch window.
            for (int tick = 0; tick < 6; tick++) assertExact(f.rideTick(HORSE_AT, true), version + " still " + tick);

            f.pongThrough(peek(f, (byte) 40));
            float peek = 0.0F;
            double x = HORSE_AT.x;
            for (int tick = 0; tick < 8; tick++) {
                float old = peek;
                peek = Math.min(peek + 0.05F, 0.4F);
                // Shulker#onPeekAmountChange pushes by the physical peek's growth.
                x += physical(version, peek) - physical(version, old);
                assertExact(f.rideTick(new Vec3(x, FLOOR_Y, HORSE_AT.z), true), version + " opening " + tick);
            }
        }
    }

    private static float physical(ClientVersion version, float amount) {
        return 0.5F - VanillaMath.sin(version, (0.5F + amount) * (float) Math.PI) * 0.5F;
    }

    /** The simulated vehicle move meets the report; the fixture's ridden GroundSpoof is not under test. */
    private static void assertExact(PredictionResult result, String name) {
        assertFalse(result.isExempt(), name + ": " + describe(result));
        assertTrue(result.getOffset() < 1.0E-5, name + ": " + describe(result));
        assertTrue(result.getFlags().stream().noneMatch(flag -> flag.getCheck().getCheckName().equals("VehicleOffset")
                        || flag.getCheck().getCheckName().equals("Simulation")),
                name + ": " + describe(result));
    }

    /** The shulker's DATA_ATTACH_FACE_ID; its proof transaction. */
    private static int attachFace(JavaTickFixture f, Direction face) {
        var entry = new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_ATTACH_FACE, face, ByteBuffer.wrap(new byte[] {
            (byte) WatchableIndexUtil.SHULKER_ATTACH_FACE, 11, (byte) face.ordinal()
        }));
        return metadata(f, new EntityMetadata(SHULKER, List.of(entry)));
    }

    /** The shulker's DATA_PEEK_ID; its proof transaction. */
    private static int peek(JavaTickFixture f, byte peek) {
        return metadata(
                f,
                new EntityMetadata(
                        SHULKER, List.of(EntityMetadata.Entry.byteValue(WatchableIndexUtil.SHULKER_PEEK, peek))));
    }

    private static int metadata(JavaTickFixture f, EntityMetadata packet) {
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
