package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A main-hand use whose slowdown is not provable is corrected by the ItemUseDesync policy. The rejected movement is a
 * client tick, so each correction advances the anchor by one tick and a player who keeps moving unslowed still falls.
 */
class ItemUseDesyncSetbackTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final float AIR_SPEED = 0.02F;

    @Test
    void eachCorrectionOfAnUnslowedMainHandUseLetsAFallingPlayerFall() throws Exception {
        try (var f = new JavaTickFixture()) {
            // The correction's motion reaches KnockbackHandler through the send routes, as PacketManager registers them.
            f.fixture.registrar.register(
                    routes -> f.listener.registerForwardingSendPackets(routes, PacketListenerPriority.LOW));
            f.player.nettyScheduler.removeScheduler();
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            var shield = ItemUtil.modelItems().stack("minecraft:shield", 1);
            f.player.getInventory().inventory.setHeldItem(shield);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 20, 0.5);
            Vec3 velocity = new Vec3(0, -0.5, 0);
            f.place(position, velocity);
            f.player.sendTransaction();
            f.pongThrough(f.player.lastTransactionSent.get());
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            assertFalse(teleports.insideUnloadedChunk());
            f.input(NONE);
            position = position.add(velocity);
            assertTrue(JavaTickFixture.exact(f.tick(position, false)));
            velocity = new Vec3(0, (velocity.y - 0.08) * 0.98F, 0);

            f.player.clientItemUse.start(Hand.MAIN_HAND, shield, ClientItemUseState.duration(shield), true);
            f.input(FORWARD);
            double anchorY = position.y;
            for (int cycle = 0; cycle < 3; cycle++) {
                Vec3 unslowed = velocity.add(ClientMoveInput.of(f, FORWARD, 1.0F, AIR_SPEED, f.player.yRot));
                f.tick(position.add(unslowed), false);
                SetBackData setback = teleports.getRequiredSetBack();
                assertNotNull(setback, "cycle " + cycle + ": no correction");
                assertFalse(setback.isComplete(), "cycle " + cycle + ": no correction");
                position = setback.getTeleportData().getLocation();
                velocity = setback.getVelocity();
                assertTrue(
                        position.y < anchorY,
                        "cycle " + cycle + ": setback at y " + position.y + " did not fall below " + anchorY);
                anchorY = position.y;
                acceptInOrder(f);
            }
        }
    }

    /** The client handles every sent packet in order, accepting each teleport where it puts it. */
    private static void acceptInOrder(JavaTickFixture f) {
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        TeleportData pending;
        while ((pending = teleports.pendingTeleports.peek()) != null) {
            f.pongThrough(pending.getTransaction());
            Vec3 target = pending.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    pending.getTeleportId(), new Vec3d(target.x, target.y, target.z), 0, 0);
            new PacketServerTeleport()
                    .onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet), f.player, packet);
            assertTrue(teleports.pendingTeleports.peek() != pending, "not accepted: " + target);
        }
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(teleports.getRequiredSetBack().isComplete());
    }
}
