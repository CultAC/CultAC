package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundDamageEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Strider#getPassengerAttachmentPoint raises the client's passenger point by
 * {@code 0.12 * cos(position * 1.5) * 2 * min(0.25, speed)} of the strider's walk animation, which
 * LivingEntity#calculateEntityAnimation updates at the end of each client tick's LivingEntity#aiStep.
 */
class JavaStriderSeatBobTest {
    private static final int STRIDER = 100;
    private static final byte DEATH = 3;

    @Test
    void aDrivenStridersSeatFollowsItsWalkAnimationUntilItDies() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++)
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.LAVA.defaultState());
                }
            }
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(STRIDER, EntityTypeIds.STRIDER, position, 0, 0, 0);
            var strider = (PacketEntityStrider) f.player.compensatedEntities.getEntity(STRIDER);
            strider.hasSaddle = true;
            strider.commitFluidHeights(ClientFluidHeights.capture(f.player,
                    GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.9F, 1.7F)));
            f.player.getInventory().inventory.setHeldItem(
                    ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            strider.onGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            Vec3 velocity = new Vec3(0, -0.0784000015258789D, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
            boolean onGround = true;
            float riddenSpeed = (float) ((double) 0.175F * 0.55F);
            float walkSpeed = 0.0F;
            float walkPosition = 0.0F;
            for (int tick = 0; tick < 8; tick++) {
                if (tick == 4) {
                    damage(f);
                    // LivingEntity#handleDamageEvent.
                    walkSpeed = 1.5F;
                }
                double y = Math.abs(velocity.y) < 0.003D ? 0 : velocity.y;
                double z = Math.abs(velocity.z) < 0.003D ? 0 : velocity.z;
                float speed = onGround ? riddenSpeed : riddenSpeed * 0.1F;
                Vec3 movement = new Vec3(0, position.y == FLOOR_Y && y < 0 ? 0 : y, z + speed);
                boolean landed = y < 0 && movement.y == 0;
                Vec3 from = position;
                position = position.add(movement);
                var result = f.rideTick(position, landed);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));

                // LivingEntity#updateWalkAnimation and WalkAnimationState#update(target, 0.4F, 1.0F).
                double dx = position.x - from.x;
                double dz = position.z - from.z;
                float target = Math.min((float) Math.sqrt(dx * dx + dz * dz) * 4.0F, 1.0F);
                walkSpeed += (target - walkSpeed) * 0.4F;
                walkPosition += walkSpeed;
                assertSeat(f, position.y, bob(f, walkSpeed, walkPosition), "tick " + tick);

                float friction = onGround ? 0.6F * 0.91F : 0.91F;
                velocity = new Vec3(0, (movement.y - 0.08D) * 0.98F, movement.z * friction)
                        .scale(0.5D).add(0, 0.05D, 0);
                onGround = landed;
            }

            // A dead strider cannot use its saddle; LivingEntity#calculateEntityAnimation stops the animation.
            kill(f);
            f.rideTick();
            assertSeat(f, strider.clientPhysicalPosition.y, 0.0F, "the tick after its death");
        }
    }

    /** Entity#positionRider: the strider's 1.7 passenger point raised by the bob, less the player's 0.6 attachment. */
    private static void assertSeat(JavaTickFixture f, double striderY, float bob, String tick) {
        Vec3 seat = f.player.compensatedEntities.lastRideTickSeat();
        assertEquals(striderY + (1.7F + (double) bob) - 0.6D, seat.y, 1.0E-9, tick);
    }

    private static float bob(JavaTickFixture f, float speed, float position) {
        return 0.12F * VanillaMath.cos(f.player.getClientVersion(), position * 1.5F) * 2.0F * Math.min(0.25F, speed);
    }

    private static void damage(JavaTickFixture f) {
        var packet = new ClientboundDamageEvent(STRIDER);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.DAMAGE_EVENT, packet, false);
        f.player.packetEntityReplication.onDamageEvent(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void kill(JavaTickFixture f) {
        var packet = new ClientboundEntityEvent(STRIDER, DEATH);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
