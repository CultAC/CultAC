package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An unmounted, tamed, saddled horse on stone, whose movement_speed the server sent with a minecraft:powder_snow
 * modifier, mounted by a set_passengers alone after two client ticks, the last walking forward. Through 1.21.4
 * LivingEntity#aiStep runs removeFrost and tryAddFrost for the remote horse too, so each of its client ticks replaces
 * that modifier from the synced DATA_TICKS_FROZEN and the first ridden travel reads the replacement. From 1.21.5 only
 * the server replaces it, so the client keeps the packet's modifier.
 */
class JavaRemoteRideableFrostTest {
    private static final int HORSE = 100;
    private static final Vec3 PLAYER = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 HORSE_AT = new Vec3(3.5, FLOOR_Y, 0.5);
    private static final double BASE = 0.225F;
    /** Standing on the floor: gravity's last move collided with it. */
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);

    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /** A thawed horse: removeFrost drops the packet's full frost, and tryAddFrost adds none. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void remoteTicksRemoveTheServersFrost(String release, ClientVersion version) throws Exception {
        firstRiddenMoveReads(release, version, 0, 140, replaces(version) ? 0.0D : frost(140));
    }

    /** A frozen horse on stone: tryAddFrost re-adds the synced count's frost in place of the packet's. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void remoteTicksReplaceTheServersFrostFromTheSyncedCount(String release, ClientVersion version)
            throws Exception {
        firstRiddenMoveReads(release, version, 70, 12, frost(replaces(version) ? 70 : 12));
    }

    private static void firstRiddenMoveReads(
            String release, ClientVersion version, int syncedTicksFrozen, int modifierTicksFrozen, double frost)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(PLAYER, GROUNDED);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, HORSE_AT, 0, 0, 0);
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.onGround = true;
            freeze(f, syncedTicksFrozen, modifierTicksFrozen);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            var idle = f.tick(PLAYER, true);
            assertTrue(exact(idle), release + " idle: " + describe(idle));
            // The root horse's travelRidden reads the zza of its passenger's previous LocalPlayer#aiStep.
            f.input(FORWARD);
            var walk = f.tick(PLAYER.add(0, 0, (double) 0.98F * 0.1F), true);
            assertTrue(exact(walk), release + " walk: " + describe(walk));

            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            // Released, so the passenger's own travel stays put on its floor; the horse still reads the walk's zza.
            f.input(NONE);
            assertVehicleExact(f.rideTick(new Vec3(HORSE_AT.x, FLOOR_Y, HORSE_AT.z + move(frost)), true), 0);
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), release);
        }
    }

    private static boolean replaces(ClientVersion version) {
        return version.isOlderThan(ClientVersion.V_1_21_5);
    }

    /** The server's SetEntityData with DATA_TICKS_FROZEN, then its UpdateAttributes with a frost. */
    private static void freeze(JavaTickFixture f, int syncedTicksFrozen, int modifierTicksFrozen) {
        f.player.compensatedEntities.updateEntityMetadata(
                HORSE,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_TICKS_FROZEN, syncedTicksFrozen, ByteBuffer.allocate(0))));
        f.player.compensatedEntities.updateAttributes(
                HORSE,
                List.of(new AttributeSnapshot(
                        "minecraft:movement_speed",
                        BASE,
                        List.of(new AttributeModifier(
                                "minecraft:powder_snow",
                                frost(modifierTicksFrozen),
                                AttributeModifier.Operation.ADD_VALUE)))));
    }

    /** LivingEntity#tryAddFrost's modifier amount for {@code ticksFrozen}. */
    private static double frost(int ticksFrozen) {
        return -0.05F * (Math.min(ticksFrozen, 140) / 140F);
    }

    /** The first ridden move along +z from rest on stone, with {@code frost} on the horse's movement_speed. */
    private static double move(double frost) {
        float speed = (float) (BASE + frost);
        return (double) 0.98F * (speed * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    }
}
