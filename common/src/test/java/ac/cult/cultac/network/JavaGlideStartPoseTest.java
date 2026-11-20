package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class JavaGlideStartPoseTest {
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final double GRAVITY = 0.08;
    // WebBlock#entityInside: Entity#makeStuckInBlock(new Vec3(0.25, 0.05F, 0.25)).
    private static final Vec3 WEB = new Vec3(0.25, 0.05F, 0.25);

    /**
     * LocalPlayer#aiStep starts gliding before travel, but Player#updatePlayerPose switches to the gliding pose only
     * after LivingEntity#aiStep's applyEffectsFromBlocks. The glide-start tick's inside-block pass still uses the
     * standing box, so a web reached only by the standing head keeps slowing the next tick's move.
     */
    @Test
    void theGlideStartTickKeepsTheStandingBoxForInsideBlocks() throws Exception {
        try (var f = new JavaTickFixture()) {
            // The standing box reaches into this web; the gliding box stays below it.
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 2, 0), BlockIds.COBWEB.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));

            Vec3 position = new Vec3(0.5, FLOOR_Y + 0.15, 0.5);
            double rising = 0.3332;
            f.place(position, new Vec3(0, rising, 0));
            f.input(JUMP);

            // LivingEntity#travelInAir: the move enters the web, then gravity and 0.98F drag.
            position = position.add(new Vec3(0, rising, 0));
            assertExact(f.tick(position, false));
            Vec3 velocity = new Vec3(0, (rising - GRAVITY) * 0.98F, 0);

            // Entity#move scales the move by the stuck multiplier and clears deltaMovement.
            position = position.add(velocity.multiply(WEB));
            assertExact(f.tick(position, false));
            velocity = new Vec3(0, (0 - GRAVITY) * 0.98F, 0);
            position = position.add(velocity.multiply(WEB));
            assertExact(f.tick(position, false));

            command(f, PlayerCommandAction.START_FLYING_WITH_ELYTRA);
            position = position.add(glide(velocity).multiply(WEB));
            assertExact(f.tick(position, false));
            assertEquals(Pose.FALL_FLYING, f.player.pose);

            // The standing box ended the glide-start tick inside the web.
            position = position.add(glide(Vec3.ZERO).multiply(WEB));
            assertExact(f.tick(position, false));

            // The gliding box ended the last tick below the web.
            position = position.add(glide(Vec3.ZERO));
            assertExact(f.tick(position, false));
        }
    }

    /** LivingEntity#updateFallFlyingMovement with yaw 0 and pitch 0: look (0, 0, 1), lift force 1. */
    private static Vec3 glide(Vec3 movement) {
        double moveHorLength = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        movement = movement.add(new Vec3(0, GRAVITY * (-1.0 + 0.75), 0));
        if (movement.y < 0.0) {
            double convert = movement.y * -0.1;
            movement = movement.add(new Vec3(0, convert, convert));
        }
        movement = movement.add(new Vec3(-movement.x * 0.1, 0, (moveHorLength - movement.z) * 0.1));
        return movement.multiply(0.99F, 0.98F, 0.99F);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, packet), f.player, packet);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
