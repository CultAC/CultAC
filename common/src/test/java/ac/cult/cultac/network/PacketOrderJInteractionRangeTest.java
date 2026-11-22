package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderJ;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 26.3 Minecraft#startUseItem sends MultiPlayerGameMode#interact for a picked entity only within
 * Player#isWithinEntityInteractionRange, the uninflated box from the eye; otherwise it falls through to
 * MultiPlayerGameMode#useItem. LocalPlayer#raycastHitResult picks past that range through a projectile's pick radius
 * or an item's attack_range, so Minecraft#startAttack can attack the entity in the same tick.
 */
class PacketOrderJInteractionRangeTest {
    private static final Vec3 STAND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final int TARGET = 9002;
    // A floor-attached shulker below and ahead: its closed box lies outside interaction range, its open shell inside.
    private static final Vec3 SHULKER = new Vec3(0, -2, 2.5);

    @Test
    void attackingAndUsingOnAFireballOutsideInteractionRangeDoesNotFlag() throws Exception {
        assertEquals(0, violations(EntityTypeIds.FIREBALL, new Vec3(0, 1.12, 4)));
    }

    @Test
    void attackingAndUsingOnAPigOutsideInteractionRangeDoesNotFlag() throws Exception {
        assertEquals(0, violations(EntityTypeIds.PIG, new Vec3(0, 0, 4)));
    }

    @Test
    void attackingAndUsingWithoutInteractingWithAFireballInRangeFlags() throws Exception {
        assertEquals(1, violations(EntityTypeIds.FIREBALL, new Vec3(0, 1.12, 3)));
    }

    @Test
    void attackingAndUsingWithoutInteractingWithAPigInRangeFlags() throws Exception {
        assertEquals(1, violations(EntityTypeIds.PIG, new Vec3(0, 0, 3)));
    }

    /** Shulker#makeBoundingBox opens toward the player; Player#isWithinEntityInteractionRange measures the shell. */
    @Test
    void attackingAndUsingWithoutInteractingWithAnOpenShulkerInRangeFlags() throws Exception {
        assertEquals(1, violations(EntityTypeIds.SHULKER, SHULKER, f -> {
            f.pongThrough(sendPeek(f, 100));
            ticks(f, 25);
        }));
    }

    @Test
    void attackingAndUsingOnAClosedShulkerOutsideInteractionRangeDoesNotFlag() throws Exception {
        assertEquals(0, violations(EntityTypeIds.SHULKER, SHULKER, f -> ticks(f, 25)));
    }

    /** Until the client answers the ping after DATA_PEEK_ID, its peek may lie anywhere the animation has passed. */
    @Test
    void attackingAndUsingOnAShulkerThatMayStillBeClosedDoesNotFlag() throws Exception {
        assertEquals(0, violations(EntityTypeIds.SHULKER, SHULKER, f -> {
            sendPeek(f, 100);
            ticks(f, 25);
        }));
    }

    /** Four steps of an unanswered close from fully open leave every peek the client may hold in range. */
    @Test
    void attackingAndUsingWithoutInteractingWithAShulkerClosingFromOpenInRangeFlags() throws Exception {
        assertEquals(1, violations(EntityTypeIds.SHULKER, SHULKER, f -> {
            f.pongThrough(sendPeek(f, 100));
            ticks(f, 25);
            sendPeek(f, 0);
            ticks(f, 3);
        }));
    }

    /**
     * A wall-attached shulker opening away from the player: every shell its unanswered opening may leave contains the
     * closed box, which lies within interaction range.
     */
    @Test
    void attackingAndUsingWithoutInteractingWithAShulkerOpeningAwayInRangeFlags() throws Exception {
        assertEquals(1, violations(EntityTypeIds.SHULKER, new Vec3(3, 0.5, 0), f -> {
            ((PacketEntityShulker) f.player.compensatedEntities.getEntity(TARGET)).facing = Direction.WEST;
            sendPeek(f, 100);
            ticks(f, 25);
        }));
    }

    private static double violations(int type, Vec3 offset) throws Exception {
        return violations(type, offset, f -> {});
    }

    /**
     * One tick of the captures after {@code setup}: attack on the entity {@code offset} from the feet, use_item, then
     * the tick end.
     */
    private static double violations(int type, Vec3 offset, Consumer<JavaTickFixture> setup) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.setExperimentalChecks(true);
            f.place(STAND, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(TARGET, type, STAND.add(offset), 0, 0, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            setup.accept(f);
            f.tickEnd();
            var attack =
                    new ServerboundInteract(TARGET, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            CheckManagerListener.receiveGeneric(f.event(ServerboundPackets.INTERACT, attack), f.player);
            f.useItem(Hand.MAIN_HAND, 0, 0);
            f.tickEnd();
            return f.player.checkManager.getListener(PacketOrderJ.class).getViolations();
        }
    }

    /** The target's DATA_PEEK_ID and the ping after it; the ping's transaction. */
    private static int sendPeek(JavaTickFixture f, int peek) {
        int proof = f.player.lastTransactionSent.get() + 1;
        f.player.compensatedEntities.updateEntityMetadata(
                TARGET,
                List.of(new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_PEEK, (byte) peek, ByteBuffer.allocate(0))),
                proof);
        assertEquals(proof, f.player.sendTrailingTransaction());
        return proof;
    }

    private static void ticks(JavaTickFixture f, int ticks) {
        for (int tick = 0; tick < ticks; tick++) f.tickEnd();
    }
}
