package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderJ;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Optional;
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

    /** One tick of the captures: attack on the entity {@code offset} from the feet, use_item, then the tick end. */
    private static double violations(int type, Vec3 offset) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.setExperimentalChecks(true);
            f.place(STAND, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(TARGET, type, STAND.add(offset), 0, 0, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();
            var attack =
                    new ServerboundInteract(TARGET, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            CheckManagerListener.receiveGeneric(f.event(ServerboundPackets.INTERACT, attack), f.player);
            f.useItem(Hand.MAIN_HAND, 0, 0);
            f.tickEnd();
            return f.player.checkManager.getListener(PacketOrderJ.class).getViolations();
        }
    }
}
