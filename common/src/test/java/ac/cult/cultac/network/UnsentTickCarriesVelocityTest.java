package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * From 1.21.2 ServerboundClientTickEndPacket ends every client tick, and LocalPlayer#sendPosition withholds only the
 * position: LivingEntity#travel still ran with the carried velocity, so an unsent tick never restarts from rest.
 */
class UnsentTickCarriesVelocityTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final Vec3 AIR = new Vec3(0.5, FLOOR_Y + 26, 0.5);
    private static final Vec3 GROUND = new Vec3(0.5, FLOOR_Y, 0.5);

    /** From rest the first LivingEntity#travelInAir moves by zero and only then applies gravity. */
    @Test
    void tickEndsAloneInTheAirAreJudgedAsAFall() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(AIR, Vec3.ZERO);
            f.input(NONE);
            f.tickEnd();
            assertClean(last(f));
            assertUnsentTicksFallUntilSetBack(f);
        }
    }

    @Test
    void tickEndsAloneAfterTheFloorIsRemovedAreJudgedAsAFall() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(GROUND, Vec3.ZERO);
            f.input(NONE);
            for (int tick = 0; tick < 10; tick++) {
                f.tickEnd();
                assertClean(last(f));
            }
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), BlockIds.AIR.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.statusTick(false, false);
            assertRejected(last(f));
            assertUnsentTicksFallUntilSetBack(f);
        }
    }

    @Test
    void standingIdleOnStoneStaysClean() throws Exception {
        idleOn(BlockIds.STONE.defaultState(), NONE, GROUND, 200);
    }

    @Test
    void standingIdleOnSlimeStaysClean() throws Exception {
        idleOn(BlockIds.SLIME_BLOCK.defaultState(), NONE, GROUND, 100);
    }

    @Test
    void standingIdleOnHoneyStaysClean() throws Exception {
        idleOn(BlockIds.HONEY_BLOCK.defaultState(), NONE, new Vec3(0.5, FLOOR_Y - 0.0625, 0.5), 100);
    }

    @Test
    void standingIdleOnSoulSandStaysClean() throws Exception {
        idleOn(BlockIds.SOUL_SAND.defaultState(), NONE, new Vec3(0.5, FLOOR_Y - 0.125, 0.5), 100);
    }

    @Test
    void sneakingIdleOverAnEdgeStaysClean() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.isSneaking = true;
            f.player.refreshPlayerPose();
            f.place(new Vec3(9.2, FLOOR_Y, 0.5), Vec3.ZERO);
            f.input(SNEAK);
            for (int tick = 0; tick < 100; tick++) {
                f.tickEnd();
                assertClean(last(f));
            }
        }
    }

    @Test
    void standingIdleUnderWaterStaysClean() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.water(new BlockPos(-2, FLOOR_Y, -2), new BlockPos(2, FLOOR_Y + 3, 2));
            f.place(GROUND, Vec3.ZERO);
            f.input(NONE);
            for (int tick = 0; tick < 100; tick++) {
                f.tickEnd();
                assertClean(last(f));
            }
            assertEquals(GROUND.y, f.player.y);
        }
    }

    /** LivingEntity#handleOnClimbable stops the slide of a crouching player on a ladder at zero. */
    @Test
    void sneakingIdleOnALadderStaysClean() throws Exception {
        // A north-facing ladder's shape starts 13/16 into its block; the 0.6 wide box touches it from here.
        sneakingIdleOn(BlockIds.LADDER.defaultState(), new Vec3(0.5, FLOOR_Y + 3, 0.8125 - 0.3));
    }

    @Test
    void sneakingIdleOnAVineStaysClean() throws Exception {
        sneakingIdleOn(BlockIds.VINE.defaultState(), new Vec3(0.5, FLOOR_Y + 3, 0.5));
    }

    /** ScaffoldingBlock#getCollisionShape gives its stable top to a player above it who is not descending. */
    @Test
    void standingIdleInsideAScaffoldingTowerStaysClean() throws Exception {
        try (var f = new JavaTickFixture()) {
            var registry = DataTables.defaults().registry();
            int scaffolding =
                    registry.with(registry.with(BlockIds.SCAFFOLDING.defaultState(), "distance", "0"), "bottom", "false");
            for (int y = FLOOR_Y; y <= FLOOR_Y + 6; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), scaffolding);
            }
            settle(f);
            Vec3 at = new Vec3(0.5, FLOOR_Y + 4, 0.5);
            f.place(at, Vec3.ZERO);
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);
            idle(f, 100);
            assertEquals(at.y, f.player.y);
        }
    }

    /** PowderSnowBlock#getCollisionShape is solid under leather boots for a player above it who is not descending. */
    @Test
    void standingIdleOnPowderSnowInLeatherBootsStaysClean() throws Exception {
        try (var f = new JavaTickFixture()) {
            leatherBoots(f);
            idleOnBlock(f, BlockIds.POWDER_SNOW.defaultState(), NONE, GROUND, 100);
        }
    }

    /** A crouching player in leather boots sinks through powder snow and stands stuck in it on the floor. */
    @Test
    void sneakingIdleInPowderSnowInLeatherBootsStaysClean() throws Exception {
        try (var f = new JavaTickFixture()) {
            leatherBoots(f);
            for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        f.player.compensatedWorld.updateBlock(
                                new BlockPos(x, y, z), BlockIds.POWDER_SNOW.defaultState());
                    }
                }
            }
            settle(f);
            f.player.isSneaking = true;
            f.player.refreshPlayerPose();
            f.place(GROUND, Vec3.ZERO);
            f.input(SNEAK);
            idle(f, 100);
            assertEquals(GROUND.y, f.player.y);
        }
    }

    /** BubbleColumnBlock#entityInside drags a player standing on the column's floor down every tick. */
    @Test
    void standingIdleOnTheFloorOfADraggingBubbleColumnStaysClean() throws Exception {
        try (var f = new JavaTickFixture()) {
            bubbleColumn(f, true, FLOOR_Y + 6);
            f.place(GROUND, Vec3.ZERO);
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);
            idle(f, 100);
            assertEquals(GROUND.y, f.player.y);
        }
    }

    /** A rising bubble column under a solid block presses a player against it every tick. */
    @Test
    void idleUnderACeilingInARisingBubbleColumnStaysClean() throws Exception {
        try (var f = new JavaTickFixture()) {
            int ceiling = FLOOR_Y + 4;
            bubbleColumn(f, false, ceiling - 1);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, ceiling, 0), BlockIds.STONE.defaultState());
            settle(f);
            pressAgainstTheCeiling(f, ceiling);
        }
    }

    /** LivingEntity#travelInAir lifts a levitating player into a ceiling that stops it every tick. */
    @Test
    void levitatingIdleAgainstACeilingStaysClean() throws Exception {
        try (var f = new JavaTickFixture()) {
            int ceiling = FLOOR_Y + 4;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, ceiling, z), BlockIds.STONE.defaultState());
                }
            }
            settle(f);
            levitation(f);
            pressAgainstTheCeiling(f, ceiling);
        }
    }

    /** Knockback stays possible until its ping returns; the ticks before that carry the velocity the client had. */
    @Test
    void idleTicksWhileAVelocityIsInFlightStayClean() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(GROUND, Vec3.ZERO);
            f.input(NONE);
            Vec3 knockback = new Vec3(0.2, 0.36, 0.0);
            int proof = f.velocity(knockback);
            idle(f, 20);
            assertEquals(GROUND, new Vec3(f.player.x, f.player.y, f.player.z));
            f.pongThrough(proof);
            PredictionResult result = f.tick(GROUND.add(knockback), false);
            assertTrue(JavaTickFixture.exact(result), describe(result));
            assertEquals(0.0, f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    /** A client older than 1.21.2 ends no tick the server sees, so a tick it never reported may still have run. */
    @Test
    void aClientWithoutTickEndsMayStartFromRest() throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21);
            f.place(AIR, Vec3.ZERO);
            f.input(NONE);
            f.tickEnd();
            assertClean(last(f));
            f.tickEnd();
            assertClean(last(f));
        }
    }

    private static void idleOn(int block, ServerboundPlayerInput input, Vec3 at, int ticks) throws Exception {
        try (var f = new JavaTickFixture()) {
            idleOnBlock(f, block, input, at, ticks);
        }
    }

    private static void idleOnBlock(JavaTickFixture f, int block, ServerboundPlayerInput input, Vec3 at, int ticks) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), block);
            }
        }
        settle(f);
        f.place(at, Vec3.ZERO);
        f.player.onGround = f.player.lastOnGround = true;
        f.input(input);
        idle(f, ticks);
        assertEquals(at.y, f.player.y);
    }

    private static void sneakingIdleOn(int climbable, Vec3 at) throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 6; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), climbable);
            }
            settle(f);
            f.player.isSneaking = true;
            f.player.refreshPlayerPose();
            f.place(at, Vec3.ZERO);
            f.input(SNEAK);
            idle(f, 100);
            assertEquals(at.y, f.player.y);
        }
    }

    /** Water from the floor to {@code top} with a dragging or rising bubble column in its middle cell. */
    private static void bubbleColumn(JavaTickFixture f, boolean drag, int top) {
        f.water(new BlockPos(-1, FLOOR_Y, -1), new BlockPos(1, top, 1));
        int column = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), drag);
        for (int y = FLOOR_Y; y <= top; y++) f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), column);
        settle(f);
    }

    /** Places the player with its head on the underside of {@code ceiling} and idles it there. */
    private static void pressAgainstTheCeiling(JavaTickFixture f, int ceiling) {
        Vec3 at = new Vec3(0.5, ceiling - (double) 1.8F, 0.5);
        f.place(at, Vec3.ZERO);
        f.input(NONE);
        idle(f, 100);
        assertEquals(at.y, f.player.y, 1.0E-6);
    }

    private static void levitation(JavaTickFixture f) {
        var packet = new ClientboundUpdateMobEffect(f.player.entityID, "minecraft:levitation", 0);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
        f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().potionsMap.containsKey(MovementEffect.LEVITATION));
    }

    private static void leatherBoots(JavaTickFixture f) {
        f.player
                .getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.SLOT_BOOTS, new SimItemStack(ItemTypes.LEATHER_BOOTS, 1, Components.EMPTY));
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void idle(JavaTickFixture f, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            PredictionResult before = last(f);
            f.tickEnd();
            PredictionResult result = last(f);
            assertTrue(result != before, "tick " + tick + " committed no prediction");
            assertTrue(result.getFlags().isEmpty() && !result.isExempt(), "tick " + tick + ": " + describe(result));
        }
    }

    private static void assertUnsentTicksFallUntilSetBack(JavaTickFixture f) {
        int ticks = 0;
        while (!f.player.getSetbackTeleportUtil().isPendingSetback()) {
            assertTrue(++ticks <= 20, "no setback after " + ticks + " ticks");
            f.tickEnd();
            assertRejected(last(f));
        }
    }

    private static void assertRejected(PredictionResult result) {
        assertFalse(result.getFlags().isEmpty() || result.isExempt(), describe(result));
        assertTrue(result.getOffset() > 0.07, describe(result));
    }

    private static PredictionResult last(JavaTickFixture f) {
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    private static void assertClean(PredictionResult result) {
        assertTrue(result.getFlags().isEmpty() && !result.isExempt(), describe(result));
    }
}
