package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketReceiveEvent;
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
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A client sprints forward at yaw 22.5 into a wall at z = 1, 22.5 degrees off the wall: a hard collision. The block
 * entities tick after the player's travel, and an opening shulker box's ShulkerBoxBlockEntity#moveCollidedEntities
 * moves the player with Entity#move(MoverType.SHULKER_BOX), which sets horizontalCollision from its own movement: none
 * for a shove up. LocalPlayer#aiStep reads it in the next tick before that tick's travel, so a player the lid shoves
 * keeps sprinting. From 26.3 Minecraft#tick sends the position after the block entities, so the move carries the
 * shove after its travel; before, the next move carries it before its own travel.
 */
class JavaShulkerBoxSprintTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final float YAW = 22.5F;
    private static final float GROUND_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    // Player#getFlyingSpeed while sprinting.
    private static final float AIR_SPEED = 0.025999999F;
    // LivingEntity#travelInAir's ground friction.
    private static final double GROUND_FRICTION = 0.6F * 0.91F;

    @Test
    void aPlayerTheLidShovesKeepsSprintingIntoTheWall() throws Exception {
        try (var f = new JavaTickFixture()) {
            BlockPos box = new BlockPos(0, FLOOR_Y, 0);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1, 1.0 - 0.3F);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            SprintE sprintE = wall(f, box, FLOOR_Y + 1);
            open(f, box);

            Vec3 ground = ClientMoveInput.of(f, SPRINT, 1.0F, GROUND_SPEED, YAW);
            f.input(SPRINT);
            command(f, sprintE, PlayerCommandAction.START_SPRINTING);
            double lid = FLOOR_Y + 1.0;
            position = new Vec3(position.x + ground.x, shove(lid, 0.0F, 0.1F), position.z);
            PredictionResult shoved = f.tick(position, false);
            assertTrue(exact(shoved), describe(shoved));
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(shoved), describe(shoved));

            Vec3 air = ClientMoveInput.of(f, SPRINT, 1.0F, AIR_SPEED, YAW);
            lid = FLOOR_Y + 1.0 + (double) (0.5F * 0.1F);
            position = new Vec3(position.x + ground.x * GROUND_FRICTION + air.x, shove(lid, 0.1F, 0.2F), position.z);
            PredictionResult kept = f.tick(position, false);
            assertTrue(exact(kept), describe(kept));
            assertEquals(0.0, sprintE.violations);
        }
    }

    /** Before 26.3 the first move is the travel alone, and the next carries the shove before its travel. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_1", "V_26_2"})
    void anOlderPlayerTheLidShovesKeepsSprintingIntoTheWall(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            BlockPos box = new BlockPos(0, FLOOR_Y, 0);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1, 1.0 - 0.3F);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            SprintE sprintE = wall(f, box, FLOOR_Y + 1);
            open(f, box);

            Vec3 ground = ClientMoveInput.of(f, SPRINT, 1.0F, GROUND_SPEED, YAW);
            f.input(SPRINT);
            command(f, sprintE, PlayerCommandAction.START_SPRINTING);
            position = new Vec3(position.x + ground.x, FLOOR_Y + 1.0, position.z);
            PredictionResult blocked = f.tick(position, true);
            assertTrue(exact(blocked), describe(blocked));

            Vec3 air = ClientMoveInput.of(f, SPRINT, 1.0F, AIR_SPEED, YAW);
            double lid = FLOOR_Y + 1.0 + (double) (0.5F * 0.1F);
            position = new Vec3(position.x + ground.x * GROUND_FRICTION + air.x, lid, position.z);
            PredictionResult kept = f.tick(position, true);
            assertTrue(exact(kept), describe(kept));
            assertEquals(0.0, sprintE.violations);
        }
    }

    /** A lid that opens beside the player shoves nothing, so the hard collision stops the sprint. */
    @Test
    void aPlayerBesideAnOpeningLidStopsSprinting() throws Exception {
        try (var f = new JavaTickFixture()) {
            BlockPos box = new BlockPos(3, FLOOR_Y, 0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 1.0 - 0.3F);
            f.place(position, Vec3.ZERO);
            SprintE sprintE = wall(f, box, FLOOR_Y);
            open(f, box);

            Vec3 ground = ClientMoveInput.of(f, SPRINT, 1.0F, GROUND_SPEED, YAW);
            f.input(SPRINT);
            command(f, sprintE, PlayerCommandAction.START_SPRINTING);
            position = position.add(ground.x, 0.0, 0.0);
            PredictionResult blocked = f.tick(position, true);
            assertTrue(exact(blocked), describe(blocked));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(blocked), describe(blocked));

            position = position.add(ground.x * GROUND_FRICTION + ground.x, 0.0, 0.0);
            PredictionResult kept = f.tick(position, true);
            assertTrue(exact(kept), describe(kept));
            assertEquals(1.0, sprintE.violations);
        }
    }

    /**
     * Places the upward shulker box at {@code box} and a wall at z = 1 up to {@code bottom + 1}, puts the placed player
     * on the ground at the test's yaw, and enables SprintE.
     */
    private static SprintE wall(JavaTickFixture f, BlockPos box, int bottom) {
        f.player.compensatedWorld.updateBlock(
                box, REGISTRY.block("minecraft:shulker_box").defaultState());
        for (int x = -8; x <= 8; x++) {
            for (int y = FLOOR_Y; y <= bottom + 1; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 1), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.xRot = YAW;
        f.player.yRot = 0.0F;
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.player.setExperimentalChecks(true);
        SprintE sprintE = f.player.checkManager.getCheck(SprintE.class);
        sprintE.violations = 0;
        return sprintE;
    }

    /** The block event that opens the box, handled before the next client tick. */
    private static void open(JavaTickFixture f, BlockPos box) {
        int state = REGISTRY.block("minecraft:shulker_box").defaultState();
        var packet = new ClientboundBlockEvent(box, 1, 1, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /**
     * The player's feet on the lid at {@code y} after ShulkerBoxBlockEntity#moveCollidedEntities from
     * {@code progressOld} to {@code progress}: up by the height of Shulker#getProgressDeltaAabb plus 0.01.
     */
    private static double shove(double y, float progressOld, float progress) {
        double bottom = FLOOR_Y + 1.0 + (double) progressOld;
        double top = FLOOR_Y + 1.0 + (double) progress;
        return y + top - bottom + 0.01;
    }

    private static void command(JavaTickFixture f, SprintE sprintE, PlayerCommandAction action) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        PacketReceiveEvent<ServerboundPlayerCommand> event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        new PacketEntityAction().onPlayerCommand(event, f.player, packet);
        sprintE.onPlayerCommand(event, f.player, packet);
    }
}
