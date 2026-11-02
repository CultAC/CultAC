package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A player standing in a westward water current attacks with positive client-side knockback. Minecraft#tick runs
 * handleKeybinds, whose MultiPlayerGameMode#attack reaches Player#attack's 0.6 X/Z damping, before
 * ClientLevel#tickEntities runs LocalPlayer#tick, whose Entity#baseTick adds the current. The damping therefore scales
 * only the velocity the tick started with, never the current added after it.
 */
class JavaAttackSlowWaterCurrentTest {
    private static final ServerboundPlayerInput IDLE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    // FlowingFluid#getFlow towards the lower west neighbour, scaled by Entity#baseTick's 0.014.
    private static final double CURRENT = -0.014D;
    // LivingEntity#travelInWater's water slowdown for a player that is not sprinting.
    private static final double WATER_FRICTION = 0.8F;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_11", "V_26_3"})
    void theAttackDampsOnlyTheVelocityBeforeTheCurrent(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            Vec3 position = standInCurrent(f);
            double velocity = 0;
            for (int tick = 0; tick < 2; tick++) {
                double move = velocity + CURRENT;
                position = position.add(move, 0, 0);
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                velocity = move * WATER_FRICTION;
            }
            attack(f);
            double damped = velocity * 0.6D + CURRENT;
            position = position.add(damped, 0, 0);
            PredictionResult attacked = f.tick(position, true);
            assertTrue(exact(attacked), "attack tick: " + describe(attacked));
            // The next tick carries the damped travel's friction, with this tick's current added again.
            double carried = damped * WATER_FRICTION + CURRENT;
            PredictionResult next = f.tick(position.add(carried, 0, 0), true);
            assertTrue(exact(next), "after the attack: " + describe(next));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aDampedCurrentIsNotTheMove(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            Vec3 position = standInCurrent(f);
            double velocity = 0;
            for (int tick = 0; tick < 2; tick++) {
                double move = velocity + CURRENT;
                position = position.add(move, 0, 0);
                assertTrue(exact(f.tick(position, true)));
                velocity = move * WATER_FRICTION;
            }
            attack(f);
            PredictionResult result = f.tick(position.add((velocity + CURRENT) * 0.6D, 0, 0), true);
            assertTrue(result.hasEffectiveFlags(), describe(result));
        }
    }

    /**
     * A water source on the floor whose west neighbour is air over water: the source's flow is exactly west. The
     * player stands on the stone under the source with the attack_knockback a server attribute packet gave it.
     */
    private static Vec3 standInCurrent(JavaTickFixture f) {
        var world = f.player.compensatedWorld;
        var water = new BlockPos(4, FLOOR_Y, 8);
        world.updateBlock(water, BlockIds.WATER.defaultState());
        world.updateBlock(water.west().below(), BlockIds.WATER.defaultState());
        for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff(0);
        var attributes = new ClientboundUpdateAttributes(
                f.player.entityID, List.of(new AttributeSnapshot("minecraft:attack_knockback", 1.0, List.of())));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, attributes, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, attributes);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 90.0F;
        f.player.yRot = 0.0F;
        Vec3 start = new Vec3(water.x() + 0.5, FLOOR_Y, water.z() + 0.5);
        f.place(start, new Vec3(0, -0.0784000015258789, 0));
        f.input(IDLE);
        return start;
    }

    /** MultiPlayerGameMode#attack on an entity the server never sent: the damping is only possible. */
    private static void attack(JavaTickFixture f) {
        var attack = new ServerboundInteract(99, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        new PacketPlayerAttack().onInteract(f.event(ServerboundPackets.INTERACT, attack), f.player, attack);
    }
}
