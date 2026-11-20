package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.elytra.ElytraI;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A glide started on the tick after a swim stroke leaves the water. LivingEntity#baseTick refreshes
 * Entity#wasTouchingWater at the end of the last move before LocalPlayer#aiStep calls Player#tryToStartFallFlying.
 */
class JavaWaterSurfaceGlideStartTest {
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    /** Feet 0.021 below the surface of a water source on the floor. */
    private static final Vec3 BOBBING = new Vec3(0.5, FLOOR_Y + 0.8676, 0.5);

    @Test
    void aStrokeOutOfTheWaterStartsTheGlide() throws Exception {
        try (var f = new JavaTickFixture()) {
            bob(f, 0.0604);
            startGlide(f);
            assertEquals(0.0, f.player.checkManager.getCheck(ElytraI.class).violations, 0.0);
        }
    }

    @Test
    void aStrokeThatStaysInTheWaterRejectsTheGlide() throws Exception {
        try (var f = new JavaTickFixture()) {
            bob(f, -0.0604);
            startGlide(f);
            assertEquals(1.0, f.player.checkManager.getCheck(ElytraI.class).violations, 0.0);
        }
    }

    /** One tick holding jump at the surface: LivingEntity#jumpInLiquid adds 0.04 before LivingEntity#travelInFluid. */
    private static void bob(JavaTickFixture f, double velocityY) {
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.WATER.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player
                .getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));
        f.place(BOBBING, new Vec3(0, velocityY, 0));
        f.input(JUMP);
        var result = f.tick(BOBBING.add(new Vec3(0, velocityY + 0.04, 0)), false);
        assertTrue(exact(result), describe(result));
    }

    private static void startGlide(JavaTickFixture f) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
        PacketReceiveEvent<ServerboundPlayerCommand> event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        new PacketEntityAction().onPlayerCommand(event, f.player, packet);
        f.player.checkManager.getCheck(ElytraI.class).onPlayerCommand(event, f.player, packet);
    }
}
