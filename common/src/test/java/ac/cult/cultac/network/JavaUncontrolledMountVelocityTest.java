package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * A rider mounts a tamed horse that has no saddle, so AbstractHorse#getControllingPassenger names nobody and the client
 * ticks the horse as a remote entity, then saddles it. The first controlled travel starts from the horse's own
 * Entity#getDeltaMovement: what its last set_entity_motion left after LivingEntity#aiStep scaled it by 0.98 and zeroed
 * each component below 0.003 on every tick it did not interpolate, before the mount and after it. Entity#rideTick zeroes
 * the rider's own delta movement each tick, so the rider's on-foot motion never reaches that travel.
 */
class JavaUncontrolledMountVelocityTest {
    private static final int HORSE = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    /** The skeleton horse's passenger attachment over the rider's vehicle attachment, as the server seats it. */
    private static final Vec3 SEAT = START.add(0, 0.71875002384186, 0);
    /** A resting LivingEntity's gravity, as ServerEntity#sendChanges sends it the tick after a summon. */
    private static final Vec3 FALL = new Vec3(0, -0.0784000015258789, 0);

    /**
     * Summoned at the rider's feet, mounted after two seconds and saddled two seconds later: 80 damped ticks leave the
     * horse falling, so its first controlled Entity#move collides with the floor and reports the ground. The seat's
     * player_position zeroes the rider's own motion first.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aHorseSaddledSoonAfterItsSummonStillFalls(String release, ClientVersion version) throws Exception {
        assertFirstControlledTick(version, 40, 40);
    }

    /**
     * Mounted after its fall has damped to nothing: its first controlled Entity#move is zero, so
     * Entity#move leaves the controlled root off the ground.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aHorseIdleLongerThanItsFallDampsStartsStill(String release, ClientVersion version) throws Exception {
        assertFirstControlledTick(version, 170, 5);
    }

    private static void assertFirstControlledTick(ClientVersion version, int footTicks, int rideTicks)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(START, FALL);
            var add = new ClientboundAddEntity(
                    HORSE, "minecraft:skeleton_horse", new Vec3d(START.x, START.y, START.z), 0, 0, 0);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, add, false);
            f.player.packetEntityReplication.onAddEntity(event, f.player, add);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.entityMotion(HORSE, FALL);
            f.pongThrough(f.player.lastTransactionSent.get());
            // AbstractHorse's DATA_ID_FLAGS with FLAG_TAME: AbstractHorse#canUseSlot opens the saddle slot.
            f.player.compensatedEntities.updateEntityMetadata(
                    HORSE, List.of(new EntityMetadata.Entry(18, (byte) 0x02, ByteBuffer.allocate(0))));

            // What the client holds: the motion as its release encodes it, before any client tick damps it.
            Vec3 expected = f.player.compensatedEntities.getEntity(HORSE).deltaMovement;
            assertEquals(FALL.y, expected.y, 1.0E-4);
            for (int tick = 0; tick < footTicks; tick++) {
                if (tick % 100 == 99) f.resetTimerBalances();
                assertTrue(JavaTickFixture.exact(f.tick(START, true)), "foot tick " + tick);
                expected = damped(expected);
            }

            // ServerPlayer#startRiding: the seat's player_position, then set_passengers.
            f.playerPosition(SEAT, Vec3.ZERO, Set.of(Relative.Y_ROT, Relative.X_ROT));
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
            for (int tick = 0; tick < rideTicks; tick++) {
                f.rideTick();
                expected = damped(expected);
            }

            f.equip(HORSE, EquipmentSlot.SADDLE, ItemUtil.modelItems().stack("minecraft:saddle", 1));
            assertTrue(f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(
                    f.player.compensatedEntities.getEntity(HORSE)));
            assertEquals(expected, f.player.compensatedEntities.getEntity(HORSE).deltaMovement);
            assertEquals(Set.of(expected), f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());

            // Through 1.21.4 LivingEntity#aiStep damps once more behind !isEffectiveAi before travelRidden.
            double fall = version.isOlderThan(ClientVersion.V_1_21_5) ? damped(expected).y : expected.y;
            boolean onGround = fall < 0;
            assertEquals(footTicks + rideTicks < 100, onGround, "the case must keep its own ground");
            assertVehicleExact(f.rideTick(START, onGround), f.player.packetStateData.acceptedClientTick);
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    /** LivingEntity#aiStep's tick of a remote entity that does not interpolate. */
    private static Vec3 damped(Vec3 velocity) {
        Vec3 scaled = velocity.multiply(0.98D, 0.98D, 0.98D);
        return new Vec3(
                Math.abs(scaled.x) < 0.003D ? 0.0D : scaled.x,
                Math.abs(scaled.y) < 0.003D ? 0.0D : scaled.y,
                Math.abs(scaled.z) < 0.003D ? 0.0D : scaled.z);
    }
}
