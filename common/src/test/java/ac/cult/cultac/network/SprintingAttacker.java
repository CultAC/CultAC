package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.combat.KeepSprint;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.SwingKind;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Optional;

/**
 * A 26.3 client sprinting forward through the air at full attack strength, with eight remote players beside it. Its
 * travel follows vanilla air physics; each step damps the carried X/Z by the attacks' 0.6 multipliers it applies.
 */
final class SprintingAttacker implements AutoCloseable {
    static final int TARGET = 200;
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.98F;
    private static final double AIR_FRICTION = 0.91F;
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 30, 0.5);

    final JavaTickFixture f;
    private final PacketPlayerAttack attacks = new PacketPlayerAttack();
    private final Vec3 input;
    private Vec3 delta;
    private Vec3 at;
    private double fall;

    SprintingAttacker(boolean knockbackAttribute) throws Exception {
        f = new JavaTickFixture();
        for (int i = 0; i < 8; i++) {
            f.addRemotePlayer(TARGET + i, new Vec3(0.5, FLOOR_Y + 30, 2.0), GameMode.SURVIVAL);
        }
        if (knockbackAttribute) {
            f.player.combatAttributes.onServerSnapshot(
                    new AttributeSnapshot("minecraft:attack_knockback", 1.0D, List.of()));
        }
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        // Player#getAttackStrengthScale reaches full strength while the player idles on the floor.
        f.place(new Vec3(START.x, FLOOR_Y, START.z), Vec3.ZERO);
        for (int tick = 0; tick < 20; tick++) tickEnd();
        fall = (0.0D - GRAVITY) * AIR_DRAG;
        f.place(START, new Vec3(0.0D, fall, 0.0D));
        f.command(PlayerCommandAction.START_SPRINTING);
        f.input(SPRINT);
        input = ClientMoveInput.of(f, SPRINT, 1.0F, 0.025999999F, 0.0F);
        delta = input;
        at = START.add(delta.x, fall, delta.z);
        assertTrue(exact(f.tick(at, false)));
        assertTrue(exact(move(1.0D)));
    }

    /** The next travel, the carried X/Z kept by {@code damping} on top of the air friction; sends its move. */
    void travel(double damping) {
        fall = (fall - GRAVITY) * AIR_DRAG;
        // LivingEntity#aiStep zeroes each horizontal axis below 0.003 before the travel.
        double x = inertia(delta.x * AIR_FRICTION * damping);
        double z = inertia(delta.z * AIR_FRICTION * damping);
        delta = new Vec3(x, 0.0D, z).add(input);
        at = at.add(delta.x, fall, delta.z);
        f.move(at, false);
    }

    private static double inertia(double axis) {
        return Math.abs(axis) < 0.003D ? 0.0D : axis;
    }

    /** One client tick: the travel and its move, then the tick end; the prediction the move committed. */
    PredictionResult move(double damping) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        travel(damping);
        tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotNull(result);
        assertTrue(result != before, "the tick committed no prediction");
        return result;
    }

    /** ServerboundClientTickEndPacket, through the attack listener and then the check manager, as registered. */
    void tickEnd() {
        var packet = ServerboundPackets.CLIENT_TICK_END.opaqueValue();
        attacks.onClientTickEnd(f.event(ServerboundPackets.CLIENT_TICK_END, packet), f.player, packet);
        f.tickEnd();
    }

    void attack(int id) {
        var packet = new ServerboundInteract(id, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        attacks.onInteract(f.event(ServerboundPackets.INTERACT, packet), f.player, packet);
    }

    void swing() {
        var packet = new ServerboundSwing(Hand.MAIN_HAND, SwingKind.PUNCH);
        attacks.onSwing(f.event(ServerboundPackets.SWING, packet), f.player, packet);
    }

    /** Spawns {@code type} as {@code id} beside the attacker; the client holds it once it answers the trailing ping. */
    void spawn(int id, String type) {
        var add = new ClientboundAddEntity(id, type, new Vec3d(at.x, at.y, at.z + 1.5), 0, 0, 0);
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, add, false);
        f.player.packetEntityReplication.onAddEntity(event, f.player, add);
        event.tasksAfter().forEach(Runnable::run);
    }

    void remove(int id) {
        var remove = new ClientboundRemoveEntities(List.of(id));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, remove, false);
        f.player.packetEntityReplication.onRemoveEntities(event, f.player, remove);
        event.tasksAfter().forEach(Runnable::run);
    }

    /** The client answers every ping sent so far. */
    void pong() {
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    double keepSprint() {
        return f.player.checkManager.getListener(KeepSprint.class).getViolations();
    }

    @Override
    public void close() throws Exception {
        f.close();
    }
}
