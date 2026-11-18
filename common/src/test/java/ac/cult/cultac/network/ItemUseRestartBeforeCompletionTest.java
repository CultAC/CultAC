package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.RESTING_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Speed;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.cult.cultac.events.packets.worldreader.BasePacketWorldReader;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * A client that ends a timed use on its own timer sends use_item for the next one before it has handled the server's
 * completion (entity event 9) of the use it holds. Its Minecraft#handleKeybinds starts that use as soon as the
 * completion ends the held one, so LocalPlayer#modifyInput keeps scaling the keys by the use's multiplier until the
 * client has handled the server's answer to that use_item: its start metadata and acknowledgement, or its
 * acknowledgement alone when the server refused it.
 */
class ItemUseRestartBeforeCompletionTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);
    private static final ServerboundPlayerInput SPRINT_KEY_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double FRICTION = 0.6F * 0.91F;
    private static final double WALK = (double) 0.98F * (double) 0.1F;
    private static final double SLOWED = (double) (0.98F * 0.2F) * (double) 0.1F;
    private static final double SPRINT = (double) 0.98F * (double) 0.13F;
    private static final double SLOWED_SPRINT = (double) (0.98F * 0.2F) * (double) 0.13F;
    /** LivingEntity#getFrictionInfluencedSpeed on ice. */
    private static final float ICE_SPEED = 0.1F * (0.21600002F / (0.98F * 0.98F * 0.98F));
    private static final double WALK_ON_ICE = (double) 0.98F * (double) ICE_SPEED;
    private static final double SLOWED_ON_ICE = (double) (0.98F * 0.2F) * (double) ICE_SPEED;
    /** LivingEntity#travelInWater moves by 0.02 without Depth Strider. */
    private static final double WALK_IN_WATER = (double) 0.98F * (double) 0.02F;
    private static final double SLOWED_IN_WATER = (double) (0.98F * 0.2F) * (double) 0.02F;
    private static final double KNOCKBACK = 0.3;

    /** JavaTickFixture#useItem numbers its use_item sequences from 1; the first use here is sequence 0. */
    private int uses;

    @Test
    void theRestartedUseSlowsTheKeysUntilTheClientHandlesTheServersAnswer() throws Exception {
        try (var f = new JavaTickFixture()) {
            slowedThroughTheServersAnswer(f);
        }
    }

    @Test
    void theRestartedUseSlowsTheKeysOfA1_21_11ClientUntilItHandlesTheServersAnswer() throws Exception {
        try (var f = JavaTickFixture.olderHost(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            slowedThroughTheServersAnswer(f);
        }
    }

    @Test
    void aRestartedMainHandUseSlowsTheKeysUntilTheClientHandlesTheServersAnswer() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            slowedWalk(f, walk, Hand.MAIN_HAND);
            int completion = sendCompletion(f);
            int restart = useItem(f, Hand.MAIN_HAND);
            assertExact(f, walk.next(SLOWED));
            handleCompletion(f, completion);
            for (int tick = 0; tick < 3; tick++) assertExact(f, walk.next(SLOWED));
            answer(f, restart, (byte) 1);
            assertExact(f, walk.next(SLOWED));
            assertNoFlags(f);
        }
    }

    /** The client may equally have handled the completion with its keys unslowed. */
    @Test
    void walkingUnslowedAfterTheCompletionStaysLegalDuringTheRestart() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            assertExact(f, walk.next(WALK));
            assertNoFlags(f);
        }
    }

    /** A vanilla client sends use_item only after it handled the completion; nothing extends its keys' slowdown. */
    @Test
    void aVanillaOrderChainEatIsPredictedFromTheUseItAlone() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            slowedWalk(f, walk, Hand.OFF_HAND);
            int completion = sendCompletion(f);
            assertExact(f, walk.next(SLOWED));
            handleCompletion(f, completion);
            assertFalse(f.player.clientItemUse.restartsBeforeCompletion());
            assertExact(f, walk.next(WALK));
            int next = useItem(f, Hand.OFF_HAND);
            assertExact(f, walk.next(SLOWED));
            answer(f, next, (byte) 3);
            assertExact(f, walk.next(SLOWED));
            assertNoFlags(f);
        }
    }

    /** The client has handled the server's refusal and holds no use; the keys move unslowed. */
    @Test
    void walkingAtTheUseSpeedAfterTheServersRefusalIsHandledIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            int restart = restart(f, walk, 1);
            assertExact(f, walk.next(SLOWED));
            answer(f, restart, (byte) -1);
            assertTrue(rejectsKeys(f.tick(walk.peek(SLOWED), true)));
        }
    }

    /** More use_item packets before the completion is handled: the answer to the last one ends them all. */
    @Test
    void repeatedUseItemsEndWithTheServersAnswerToTheLast() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            int last = restart(f, walk, 3);
            assertExact(f, walk.next(SLOWED));
            answer(f, last, (byte) -1);
            assertTrue(rejectsKeys(f.tick(walk.peek(SLOWED), true)));
        }
    }

    /** A use_item while the server has sent no completion, followed by the server stopping the held use. */
    @Test
    void walkingAtTheUseSpeedAfterAStopWithoutACompletionIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            slowedWalk(f, walk, Hand.OFF_HAND);
            useItem(f, Hand.OFF_HAND);
            assertFalse(f.player.clientItemUse.restartsBeforeCompletion());
            usingFlags(f, (byte) 0);
            assertTrue(rejectsKeys(f.tick(walk.peek(SLOWED), true)));
        }
    }

    /** A use_item for a hand that holds no item with a use duration starts no use. */
    @Test
    void aUseItemForAHandWithoutATimedItemKeepsNoSlowdown() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            slowedWalk(f, walk, Hand.OFF_HAND);
            int completion = sendCompletion(f);
            useItem(f, Hand.MAIN_HAND);
            assertExact(f, walk.next(SLOWED));
            handleCompletion(f, completion);
            assertTrue(rejectsKeys(f.tick(walk.peek(SLOWED), true)));
        }
    }

    @Test
    void walkingAtTheUseSpeedAfterTheClientsReleaseIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            f.releaseUsingItem();
            assertTrue(rejectsKeys(f.tick(walk.peek(SLOWED), true)));
        }
    }

    /** LivingEntity#updatingUsingItem stops a use whose hand holds another item. */
    @Test
    void walkingAtTheUseSpeedAfterSelectingAnotherItemIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var storage = f.player.getInventory().inventory.getInventoryStorage();
            storage.setItem(Inventory.HOTBAR_OFFSET + 1, ItemUtil.modelItems().stack("minecraft:stone", 1));
            var walk = new Walk();
            slowedWalk(f, walk, Hand.MAIN_HAND);
            int completion = sendCompletion(f);
            useItem(f, Hand.MAIN_HAND);
            assertExact(f, walk.next(SLOWED));
            handleCompletion(f, completion);
            f.selectHotbarSlot(1);
            assertTrue(rejectsKeys(f.tick(walk.peek(SLOWED), true)));
        }
    }

    /** Sprinting keys between the slowed and the unslowed sprinting input. */
    @Test
    void sprintingBetweenTheUseSpeedAndTheSprintingSpeedDuringTheRestartIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            f.command(PlayerCommandAction.START_SPRINTING);
            f.input(SPRINT_KEY_FORWARD);
            assertTrue(rejectsKeys(f.tick(walk.peek(SPRINT * 0.5), true)));
        }
    }

    @Test
    void sprintingFasterThanTheSprintingSpeedDuringTheRestartIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            f.command(PlayerCommandAction.START_SPRINTING);
            f.input(SPRINT_KEY_FORWARD);
            assertTrue(rejectsKeys(f.tick(walk.peek(SPRINT * 1.2), true)));
        }
    }

    /** Knockback replaces the velocity; the keys add at most the unslowed walking input to it. */
    @Test
    void movingFurtherThanTheKnockbackAndTheWalkingInputDuringTheRestartIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            f.pongThrough(f.velocity(new Vec3(0.3, RESTING_Y, 0.0)));
            PredictionResult result = f.tick(new Vec3(0.8, FLOOR_Y, walk.z + WALK * 1.5), true);
            assertTrue(flagged(result), flags(result));
        }
    }

    @Test
    void jumpingHigherThanAJumpDuringTheRestartIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            f.input(JUMP_FORWARD);
            Vec3 slowed = walk.peek(SLOWED);
            PredictionResult result = f.tick(new Vec3(slowed.x, FLOOR_Y + 0.6, slowed.z), false);
            assertTrue(flagged(result), flags(result));
        }
    }

    /** Ice lowers the ground input below the walking input of stone. */
    @Test
    void walkingAtTheStoneWalkingSpeedOnIceDuringTheRestartIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            onIce(f, walk);
            PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, walk.z + WALK), true);
            assertTrue(flagged(result), flags(result));
        }
    }

    /** Water lowers the input below the walking input on land. */
    @Test
    void walkingAtTheLandWalkingSpeedInWaterDuringTheRestartIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            inWater(f, walk);
            PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y, walk.z + WALK), true);
            assertTrue(flagged(result), flags(result));
        }
    }

    /** On ice the restart adds the ice input scaled by the use's multiplier, and no input between it and the unslowed one. */
    @Test
    void onIceTheRestartAddsOnlyTheSlowedIceInput() throws Exception {
        assertExact(afterRestart(this::onIce, walk -> new Vec3(0.5, FLOOR_Y, walk.z + SLOWED_ON_ICE), true));
        assertTrue(rejectsKeys(afterRestart(
                this::onIce, walk -> new Vec3(0.5, FLOOR_Y, walk.z + (SLOWED_ON_ICE + WALK_ON_ICE) / 2), true)));
    }

    /** In water the restart adds the fluid input scaled by the use's multiplier, and no input between it and the unslowed one. */
    @Test
    void inWaterTheRestartAddsOnlyTheSlowedFluidInput() throws Exception {
        assertExact(afterRestart(this::inWater, walk -> new Vec3(0.5, FLOOR_Y, walk.z + SLOWED_IN_WATER), true));
        assertTrue(rejectsKeys(afterRestart(
                this::inWater,
                walk -> new Vec3(0.5, FLOOR_Y, walk.z + (SLOWED_IN_WATER + WALK_IN_WATER) / 2),
                true)));
    }

    /** Knockback replaces the velocity; the restart adds only the slowed keys to it. */
    @Test
    void underKnockbackTheRestartAddsOnlyTheSlowedInput() throws Exception {
        assertExact(afterRestart(
                this::knockedBack, walk -> new Vec3(0.5 + KNOCKBACK, FLOOR_Y, walk.z + SLOWED), true));
        PredictionResult between = afterRestart(
                this::knockedBack, walk -> new Vec3(0.5 + KNOCKBACK, FLOOR_Y, walk.z + (SLOWED + WALK) / 2), true);
        assertTrue(flagged(between), flags(between));
    }

    /** A jump keeps its impulse; the restart adds only the slowed keys to the jump's horizontal move. */
    @Test
    void inAJumpTheRestartAddsOnlyTheSlowedInput() throws Exception {
        assertExact(afterRestart(this::jumping, walk -> jumped(walk.peek(SLOWED)), false));
        PredictionResult between = afterRestart(this::jumping, walk -> jumped(walk.peek((SLOWED + WALK) / 2)), false);
        assertTrue(flagged(between), flags(between));
    }

    /** Sprinting keys during the restart may move at the sprinting input scaled by the use's multiplier. */
    @Test
    void sprintingAtTheSlowedSprintingSpeedDuringTheRestartIsPredicted() throws Exception {
        assertExact(afterRestart(this::sprinting, walk -> walk.peek(SLOWED_SPRINT), true));
    }

    /** Bedrock movement models its item use apart from LocalPlayer and counts no Java completion. */
    @Test
    void aBedrockPlayerStartsNoRestart() throws Exception {
        try (var f = new JavaTickFixture()) {
            slowedWalk(f, new Walk(), Hand.OFF_HAND);
            f.player.movementPlatform = MovementPlatform.BEDROCK;
            var packet = new ClientboundEntityEvent(f.player.entityID, (byte) 9);
            var event = ItemUseTestSupport.sendEvent(f.player, ClientboundPackets.ENTITY_EVENT, packet);
            f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            assertFalse(f.player.clientItemUse.restartsBeforeCompletion());
        }
    }

    /**
     * Six slowed ticks, the completion sent, the use_item received before the client handled it and one more slowed
     * tick, then the completion handled: three slowed ticks before the server's answer and one after its start.
     */
    private void slowedThroughTheServersAnswer(JavaTickFixture f) {
        var walk = new Walk();
        int restart = restart(f, walk, 1);
        for (int tick = 0; tick < 3; tick++) assertExact(f, walk.next(SLOWED));
        answer(f, restart, (byte) 3);
        assertExact(f, walk.next(SLOWED));
        assertNoFlags(f);
    }

    /**
     * A slowed walk, the completion sent, {@code useItems} use_item packets received before the client handles it and
     * one slowed tick, then the completion handled. Returns the last use_item's sequence.
     */
    private int restart(JavaTickFixture f, Walk walk, int useItems) {
        slowedWalk(f, walk, Hand.OFF_HAND);
        int completion = sendCompletion(f);
        int last = -1;
        for (int i = 0; i < useItems; i++) last = useItem(f, Hand.OFF_HAND);
        assertExact(f, walk.next(SLOWED));
        handleCompletion(f, completion);
        return last;
    }

    /** A player walking +z from rest at z -6 through six ticks of a golden apple use in {@code hand}. */
    private void slowedWalk(JavaTickFixture f, Walk walk, Hand hand) {
        var storage = f.player.getInventory().inventory.getInventoryStorage();
        storage.setItem(hand == Hand.OFF_HAND ? Inventory.SLOT_OFFHAND : Inventory.HOTBAR_OFFSET, food());
        ItemUseTestSupport.use(f.player, hand, uses);
        usingFlags(f, hand == Hand.OFF_HAND ? (byte) 3 : (byte) 1);
        f.player.xRot = 0;
        f.place(new Vec3(0.5, FLOOR_Y, walk.z), new Vec3(0, -0.0784000015258789, 0));
        f.input(FORWARD);
        for (int tick = 0; tick < 6; tick++) assertExact(f, walk.next(SLOWED));
    }

    /** One tick to {@code to} in a fresh fixture, after a restart with one use_item and {@code setup}. */
    private PredictionResult afterRestart(BiConsumer<JavaTickFixture, Walk> setup, Function<Walk, Vec3> to, boolean onGround)
            throws Exception {
        uses = 0;
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            setup.accept(f, walk);
            return f.tick(to.apply(walk), onGround);
        }
    }

    /** The player at rest on ice. */
    private void onIce(JavaTickFixture f, Walk walk) {
        var ice = DataTables.defaults().registry().block("minecraft:ice").defaultState();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), ice);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.place(new Vec3(0.5, FLOOR_Y, walk.z), Vec3.ZERO);
    }

    /** The player at rest in water. */
    private void inWater(JavaTickFixture f, Walk walk) {
        f.water(new BlockPos(-8, FLOOR_Y, -8), new BlockPos(8, FLOOR_Y + 1, 8));
        f.place(new Vec3(0.5, FLOOR_Y, walk.z), Vec3.ZERO);
    }

    private void knockedBack(JavaTickFixture f, Walk walk) {
        f.pongThrough(f.velocity(new Vec3(KNOCKBACK, RESTING_Y, 0.0)));
    }

    private void jumping(JavaTickFixture f, Walk walk) {
        f.input(JUMP_FORWARD);
    }

    private void sprinting(JavaTickFixture f, Walk walk) {
        f.command(PlayerCommandAction.START_SPRINTING);
        f.input(SPRINT_KEY_FORWARD);
    }

    /** The first tick of a jump from the floor: LivingEntity#jumpFromGround's impulse, applied before gravity. */
    private static Vec3 jumped(Vec3 ground) {
        return new Vec3(ground.x, FLOOR_Y + (double) 0.42F, ground.z);
    }

    private int useItem(JavaTickFixture f, Hand hand) {
        f.useItem(hand, f.player.xRot, f.player.yRot);
        return ++uses;
    }

    /** Entity event 9 and its trailing ping, unanswered; the ping's id. */
    private static int sendCompletion(JavaTickFixture f) {
        var packet = new ClientboundEntityEvent(f.player.entityID, (byte) 9);
        var event = ItemUseTestSupport.sendEvent(f.player, ClientboundPackets.ENTITY_EVENT, packet);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        var ping = (ClientboundPing) event.writesAfter().getLast().packet();
        event.tasksAfter().forEach(Runnable::run);
        return ping.id();
    }

    private static void handleCompletion(JavaTickFixture f, int ping) {
        assertTrue(f.player.addTransactionResponse(ping));
        assertFalse(f.player.isClientUsingItem(), "still using after the completion");
    }

    /**
     * The server's answer to use_item {@code sequence}, handled by the client: the living flags {@code flags} when it
     * started the use (negative when it refused), then ServerGamePacketListenerImpl#handleUseItem's acknowledgement.
     */
    private static void answer(JavaTickFixture f, int sequence, byte flags) {
        if (flags >= 0) usingFlags(f, flags);
        var packet = new ClientboundBlockChangedAck(sequence);
        var event = ItemUseTestSupport.sendEvent(f.player, ClientboundPackets.BLOCK_CHANGED_ACK, packet);
        new BasePacketWorldReader().onBlockChangedAck(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void usingFlags(JavaTickFixture f, byte flags) {
        InputKeysTest.selfMetadata(f, WatchableIndexUtil.LIVING_ENTITY_FLAGS, flags);
    }

    private static SimItemStack food() {
        return ItemUtil.modelItems().stack("minecraft:golden_apple", 3);
    }

    private static void assertExact(JavaTickFixture f, Vec3 to) {
        assertExact(f.tick(to, true));
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), flags(result));
    }

    private static boolean flagged(PredictionResult result) {
        return !result.getFlags().isEmpty();
    }

    /** The travel adds exactly the input of the reported keys; what is left of a move flags Speed or Strafe. */
    private static boolean rejectsKeys(PredictionResult result) {
        return result.hasFlag(Speed.class) || result.hasFlag(Strafe.class);
    }

    private static String flags(PredictionResult result) {
        StringBuilder text = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            text.append(' ').append(flag.getCheck().getCheckName()).append('=').append(flag.getSeverity());
        }
        return text.toString();
    }

    private static void assertNoFlags(JavaTickFixture f) {
        assertEquals(0, f.player.checkManager.getCheck(Speed.class).violations);
        assertEquals(0, f.player.checkManager.getCheck(Strafe.class).violations);
        assertEquals(0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
    }

    /** A ground walk along +z at yaw 0 on stone: each move adds the carried velocity and the tick's input. */
    private static final class Walk {
        double z = -6.0;
        double velocity;

        Vec3 next(double input) {
            z += velocity + input;
            velocity = (velocity + input) * FRICTION;
            return new Vec3(0.5, FLOOR_Y, z);
        }

        Vec3 peek(double input) {
            return new Vec3(0.5, FLOOR_Y, z + velocity + input);
        }
    }
}
