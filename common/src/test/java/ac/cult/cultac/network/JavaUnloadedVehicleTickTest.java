package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ClientLevel#tickEntities ticks only entities in tickingEntities, which a ClientLevel#unload of their chunk removes.
 * A root vehicle outside it is not ticked, and neither are its passengers: no LivingEntity#aiStep scales its delta
 * movement and LocalPlayer#tick sends nothing, so those tick ends say nothing about who controls it. When the chunk
 * returns, the root resumes from the delta movement it stopped with.
 */
class JavaUnloadedVehicleTickTest {
    private static final int PIG = 9400;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    /** A saddled pig's carried ground velocity: 0.1 forward and the gravity its next move collides away. */
    private static final Vec3 CARRIED = new Vec3(0.1, -0.0784000015258789, 0);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_11", "V_26_3"})
    void aControlledPigResumesWithTheVelocityItStoppedWith(ClientVersion version) throws Exception {
        Vec3 resumed;
        try (var reference = mounted(version, true)) {
            reference.player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(pig(reference), CARRIED);
            var predicted = reference.rideTick(START, true);
            resumed = START.add(predicted.getAcceptedClosestToTarget());
        }
        assertTrue(resumed.x > START.x + 0.09, "the reference pig must carry its velocity forward: " + resumed);
        for (int skipped : new int[] {1, 20}) {
            try (var f = mounted(version, true)) {
                PacketEntity pig = pig(f);
                f.player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(pig, CARRIED);
                skipTicks(f, skipped);
                assertEquals(CARRIED, pig.deltaMovement, skipped + " skipped ticks");
                assertEquals(
                        List.of(CARRIED),
                        List.copyOf(
                                f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels()),
                        skipped + " skipped ticks");
                assertVehicleExact(f.rideTick(resumed, true), skipped);
            }
        }
    }

    /** From 26.3 a passive living root's aiStep scales its delta movement, but only on a tick that ticks it. */
    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void aPassivePigKeepsItsVelocityWhileItsChunkDoesNotTick(int skipped) throws Exception {
        try (var f = mounted(ClientVersion.V_26_3, false)) {
            PacketEntity pig = pig(f);
            assertFalse(f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(pig));
            pig.deltaMovement = CARRIED;
            skipTicks(f, skipped);
            assertEquals(CARRIED, pig.deltaMovement);
        }
    }

    /** The client forgets the root's chunk, ends {@code ticks} client ticks without ticking it, then reloads it. */
    private static void skipTicks(JavaTickFixture f, int ticks) {
        var world = f.player.compensatedWorld;
        var chunk = world.getChunk(0, 0);
        world.removeChunkLater(0, 0, f.player.lastTransactionSent.get());
        f.pongThrough(f.player.lastTransactionSent.get());
        assertFalse(world.isClientEntityTickingChunk(0, 0));
        for (int tick = 0; tick < ticks; tick++) f.tickEnd();
        world.putChunk(0, 0, chunk);
        assertTrue(world.isClientEntityTickingChunk(0, 0));
    }

    private static PacketEntity pig(JavaTickFixture f) {
        return f.player.compensatedEntities.getEntity(PIG);
    }

    private static JavaTickFixture mounted(ClientVersion version, boolean controlled) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.nettyScheduler.removeScheduler();
        f.place(START, Vec3.ZERO);
        f.teleport(START, new RelativeFlag(0));
        f.player.compensatedEntities.addEntity(PIG, EntityTypeIds.PIG, START, 0, 0, 0);
        ((PacketEntityRideable) f.player.compensatedEntities.getEntity(PIG)).hasSaddle = true;
        if (controlled) {
            f.player
                    .getInventory()
                    .inventory
                    .setHeldItem(ItemUtil.modelItems().stack("minecraft:carrot_on_a_stick", 1));
        }
        f.setPassengers(PIG, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        return f;
    }
}
