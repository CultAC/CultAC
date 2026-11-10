package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class JavaGlidePistonWaterPrecisionTest {
    /**
     * Official 26.3 client tick 10290, 2026-10-25 14:39:15 CDT, translated by (-350,-24,+80).
     * SELF returns standing/onGround; pose then changes; PISTON returns gliding/off-ground with +0.014 current;
     * its explicit inside-block pass adds +0.1 from the bubble surface. Constants are native method captures.
     */
    @Test
    void oneTickStartsGlidingThenMovesIntoFlowAndBubblesWithTheNewBox() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_3);
            var blocks = DataTables.defaults().registry();
            BlockPos pistonAt = new BlockPos(-2, FLOOR_Y, 0);
            int piston = blocks.with(BlockIds.STICKY_PISTON.defaultState(), "facing", "east");
            f.player.compensatedWorld.updateBlock(pistonAt, piston);
            f.player.compensatedWorld.updateBlock(new BlockPos(-1, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 2, 0), BlockIds.STONE.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 2, 0), BlockIds.SOUL_SAND.defaultState());
            int column = blocks.with(BlockIds.BUBBLE_COLUMN.defaultState(), "drag", "false");
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 0), column);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), column);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(2, FLOOR_Y, 0), blocks.with(BlockIds.WATER.defaultState(), "level", "1"));
            for (int x = 0; x <= 1; x++)
                for (int y = FLOOR_Y - 1; y <= FLOOR_Y + 2; y++)
                    for (int z : new int[] {-1, 1})
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.GLASS.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));
            Vec3 start = new Vec3(0.3191098914759, FLOOR_Y, 0.5);
            f.place(start, new Vec3(0.004685016811260053, -0.0784000015258789, 0));
            f.player.onGround = f.player.lastOnGround = false;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            f.player.xRot = -90;
            f.player.yRot = 13;
            f.input(new ServerboundPlayerInput(false, false, false, false, true, false, false));
            var glide =
                    new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, glide), f.player, glide);
            var packet = new ClientboundBlockEvent(pistonAt, 0, 5, blocks.blockIndex(piston));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
            new PacketBlockAction().onBlockEvent(event, f.player, packet);
            new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());

            var result = f.tick(new Vec3(0.810000011920929, FLOOR_Y, 0.5), false);
            assertFalse(result.isExempt());
            assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
            assertEquals(0, result.getOffset(), 1e-13);
            var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(0.013218782561605719, move.attempted().x, 1e-13);
            assertEquals(-0.08996974385374998, move.attempted().y, 1e-13);
            assertTrue(move.verticalCollisionBelow());
            assertEquals(Pose.STANDING, result.getSimulationContext().getPose());
            var pass = JavaMovementEngine.passAfterTravel(result);
            assertNotNull(pass);
            assertEquals(Pose.FALL_FLYING, pass.pose());
            assertFalse(pass.result().state().onGround());
            assertEquals(0.027218782561605717, pass.result().state().velocity().x, 1e-13);
            assertEquals(0.1, pass.result().state().velocity().y, 1e-13);
            assertTrue(pass.result().state().inWater());
            assertEquals(0.5100000000000000, f.player.boundingBox.minX, 1e-13);
            assertEquals(FLOOR_Y + 0.6000000238418579, f.player.boundingBox.maxY, 1e-13);
        }
    }
}
