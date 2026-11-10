package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A remote player boosts the saddled pig or strider it steers, dismounts, and the local player mounts it. LivingEntity
 * #aiStep calls travelRidden for any controlling player, and Pig/Strider#tickRidden advance ItemBasedSteering before
 * travelRidden tests canSimulateMovement. The observing client therefore advanced the boost on every tick of the remote
 * ride, and its own first ridden tick continues that phase. No passenger change restarts the boost.
 */
class JavaRemoteRiddenBoostTest {
    private static final int MOUNT = 100;
    private static final int REMOTE = 101;
    private static final Vec3 PLAYER = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 MOUNT_AT = new Vec3(3.5, FLOOR_Y, 0.5);
    private static final int BOOST_TIME = 200;
    private static final int REMOTE_TICKS = 20;
    private static final double STRIDER_BASE = (double) 0.175F;
    // A player standing on the floor: gravity's fall, which Entity#move ends on the floor.
    private static final Vec3 STANDING = new Vec3(0, -0.0784000015258789D, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPigContinuesTheBoostItsRemoteControllerAdvanced(String release, ClientVersion version) throws Exception {
        handOver(release, version, EntityTypeIds.PIG, EquipmentSlot.MAINHAND, REMOTE_TICKS + 1);
    }

    /** LivingEntity#isHolding reads both hands, so an off-hand fungus on a stick also steers. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aStriderContinuesTheBoostItsRemoteControllerAdvanced(String release, ClientVersion version)
            throws Exception {
        handOver(release, version, EntityTypeIds.STRIDER, EquipmentSlot.OFFHAND, REMOTE_TICKS + 1);
    }

    /** Without the steering item the remote rider is no controller: tickRidden never runs for it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aRemoteRiderWithoutTheSteeringItemLeavesTheBoostWhereItWas(String release, ClientVersion version)
            throws Exception {
        handOver(release, version, EntityTypeIds.STRIDER, null, 1);
    }

    private static void handOver(
            String release, ClientVersion version, int type, EquipmentSlot remoteHand, int boostedTicks)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(PLAYER, STANDING);
            f.player.compensatedEntities.addEntity(MOUNT, type, MOUNT_AT, 0, 0, 0);
            var mount = (PacketEntityRideable) f.player.compensatedEntities.getEntity(MOUNT);
            mount.onGround = true;
            equip(f, MOUNT, EquipmentSlot.SADDLE, "minecraft:saddle");
            String steering =
                    type == EntityTypeIds.PIG ? "minecraft:carrot_on_a_stick" : "minecraft:warped_fungus_on_a_stick";
            f.addRemotePlayer(REMOTE, MOUNT_AT, GameMode.SURVIVAL);
            if (remoteHand != null) equip(f, REMOTE, remoteHand, steering);
            f.setPassengers(MOUNT, List.of(REMOTE));
            f.pongThrough(f.player.lastTransactionSent.get());
            // Pig/Strider#onSyncedDataUpdated starts the boost on every client tracking the mount.
            var layout = WatchableIndexUtil.forModel(f.player.user.getCultConnection());
            int boostIndex = type == EntityTypeIds.PIG ? layout.pigBoostTime() : layout.striderBoostTime();
            f.player.compensatedEntities.updateEntityMetadata(
                    MOUNT, List.of(new EntityMetadata.Entry(boostIndex, BOOST_TIME, ByteBuffer.allocate(0))));
            f.input(NONE);
            for (int tick = 0; tick < REMOTE_TICKS; tick++) f.tick(PLAYER, true);

            f.setPassengers(MOUNT, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack(steering, 1));
            f.setPassengers(MOUNT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            float boost = boostFactor(version, boostedTicks);
            double move = riddenSpeed(type, boost) * (0.21600002F / (0.6F * 0.6F * 0.6F));
            // The remote ride's travelRidden left the mount at rest, so this first move has no fall for Entity#move
            // to end on the floor: the root reports no ground, and Player#tick has cleared the rider's.
            var ridden = f.rideTick(MOUNT_AT.add(0, 0, move), false);
            assertEquals(boost, mount.boost.factor(version), release);
            assertVehicleExact(ridden, REMOTE_TICKS);
        }
    }

    /** ItemBasedSteering#boostFactor once tickBoost has run {@code ticks} times. */
    private static float boostFactor(ClientVersion version, int ticks) {
        return 1.0F + 1.15F * VanillaMath.sin(version, (float) ticks / BOOST_TIME * (float) Math.PI);
    }

    /** Pig#getRiddenSpeed, or Strider#getRiddenSpeed for a strider cold on stone with Strider#setSuffocating's modifier. */
    private static float riddenSpeed(int type, float boost) {
        if (type == EntityTypeIds.PIG) return (float) (0.25D * 0.225D * (double) boost);
        double cold = STRIDER_BASE + STRIDER_BASE * (double) -0.34F;
        return (float) (cold * (double) 0.35F * (double) boost);
    }

    /** The server's set_equipment putting {@code item} in a {@code slot} of the entity {@code id}. */
    @SuppressWarnings("unchecked")
    private static void equip(JavaTickFixture f, int id, EquipmentSlot slot, String item) {
        var packet = new InventoryPackets.Equipment(
                id, List.of(new InventoryPackets.EquipmentEntry(slot, ItemUtil.modelItems().stack(item, 1))));
        var type = (PacketType<InventoryPackets.Equipment>) TestProtocolRuntime.catalog().stream()
                .filter(candidate -> candidate.recordClass() == InventoryPackets.Equipment.class)
                .findFirst()
                .orElseThrow();
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        f.player.packetEntityReplication.onSetEquipment(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
