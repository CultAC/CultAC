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
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.NoSlow;
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
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A client may send use_item for its next use before it has handled the server's completion (entity event 9) of the use
 * it holds. Its Minecraft#handleKeybinds starts that use as soon as the completion ends the held one, so
 * LocalPlayer#modifyInput keeps scaling the keys by the use's multiplier. Such a client keeps a restarted consumable
 * slowing whatever the server answers; vanilla completes a use only on the server (LivingEntity#updateUsingItem),
 * so the item's use duration caps the restart, and LocalPlayer#canStartSprinting proves the use no longer slows a
 * sprint start.
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
    /** A knockback across, against and along the walk, as set_entity_motion sends them to a player on the floor. */
    private static final List<Vec3> KNOCKBACKS = List.of(
            new Vec3(KNOCKBACK, RESTING_Y, 0.0),
            new Vec3(0.0, RESTING_Y, -KNOCKBACK),
            new Vec3(0.0, RESTING_Y, KNOCKBACK));
    /** An explosion's knockback across, against and along the walk. */
    private static final List<Vec3> EXPLOSIONS = List.of(
            new Vec3(KNOCKBACK, 0.0, 0.0), new Vec3(0.0, 0.0, -KNOCKBACK), new Vec3(0.0, 0.0, KNOCKBACK));
    /** Shares of a knockback a client may take instead of all of it. */
    private static final double[] PART_TAKEN = {0.0, 0.5, 0.9};
    /** Item#getUseDuration of a golden apple: its consumable component's consume_seconds in ticks. */
    private static final int USE_DURATION = 32;

    private static final Vec3 GLIDE_FROM = new Vec3(8.5, FLOOR_Y + 40, 8.5);
    private static final Vec3 GLIDE_CARRIED = new Vec3(0.5, -0.1, 0.8);
    private static final int HORSE = 100;

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

    /** The client's own use keeps slowing the keys after it has handled the server's refusal. */
    @Test
    void theRestartedUseSlowsTheKeysPastTheServersRefusal() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            int restart = restart(f, walk, 1);
            for (int tick = 0; tick < 2; tick++) assertExact(f, walk.next(SLOWED));
            answer(f, restart, (byte) -1);
            for (int tick = 0; tick < 6; tick++) assertExact(f, walk.next(SLOWED));
            assertExact(f, walk.next(WALK));
            assertNoFlags(f);
        }
    }

    /** The cap counts the use duration as the server's LivingEntity#updateUsingItem counts it. */
    @Test
    void walkingAtTheUseSpeedOnceTheUseDurationHasPassedIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            int restart = restart(f, walk, 1);
            answer(f, restart, (byte) -1);
            for (int tick = 1; tick < USE_DURATION - 1; tick++) assertExact(f, walk.next(SLOWED));
            assertTrue(rejectsKeys(f.tick(walk.peek(SLOWED), true)));
        }
    }

    /** A use_item once the restarted use has run its duration starts no restart; the server's stop ends that use. */
    @Test
    void aUseItemAfterTheRestartedUseRanItsDurationEndsWithTheServersStop() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            for (int tick = 1; tick < USE_DURATION - 1; tick++) assertExact(f, walk.next(SLOWED));
            assertExact(f, walk.next(WALK));
            int next = useItem(f, Hand.OFF_HAND);
            assertFalse(f.player.clientItemUse.restartsBeforeCompletion());
            answer(f, next, (byte) 3);
            assertExact(f, walk.next(SLOWED));
            usingFlags(f, (byte) 0);
            assertTrue(rejectsKeys(f.tick(walk.peek(SLOWED), true)));
        }
    }

    /** Each use_item before the completion is handled starts the use anew; the duration runs from the last one. */
    @Test
    void repeatedUseItemsRunTheUseDurationFromTheLast() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            slowedWalk(f, walk, Hand.OFF_HAND);
            int completion = sendCompletion(f);
            useItem(f, Hand.OFF_HAND);
            for (int tick = 0; tick < 10; tick++) assertExact(f, walk.next(SLOWED));
            int last = useItem(f, Hand.OFF_HAND);
            assertExact(f, walk.next(SLOWED));
            handleCompletion(f, completion);
            answer(f, last, (byte) -1);
            for (int tick = 1; tick < USE_DURATION - 1; tick++) assertExact(f, walk.next(SLOWED));
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

    /** A release_using_item the server receives does not reach the client's own use of a consumable. */
    @Test
    void theRestartedUseSlowsTheKeysPastAReleaseItSends() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            restart(f, walk, 1);
            f.releaseUsingItem();
            for (int tick = 0; tick < 8; tick++) assertExact(f, walk.next(SLOWED));
            assertExact(f, walk.next(WALK));
            assertNoFlags(f);
        }
    }

    /** The client's own use of a consumable runs whatever item its selected slot shows the server. */
    @Test
    void theRestartedUseSlowsTheKeysWhileTheHandShowsAnotherItem() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            mainHandRestart(f, walk, sword());
            f.selectHotbarSlot(1);
            f.releaseUsingItem();
            for (int tick = 0; tick < 8; tick++) assertExact(f, walk.next(SLOWED));
            assertExact(f, walk.next(WALK));
            assertNoFlags(f);
        }
    }

    /** The server's completion took the last item; the client's use runs on through its refusal and a slot change. */
    @Test
    void theRestartedUseOfTheLastItemSlowsTheKeysOnceTheHandIsEmpty() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            int restart = mainHandRestart(f, walk, SimItemStack.EMPTY);
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET, SimItemStack.EMPTY);
            for (int tick = 0; tick < 2; tick++) assertExact(f, walk.next(SLOWED));
            answer(f, restart, (byte) -1);
            for (int tick = 0; tick < 3; tick++) assertExact(f, walk.next(SLOWED));
            f.selectHotbarSlot(1);
            for (int tick = 0; tick < 3; tick++) assertExact(f, walk.next(SLOWED));
            assertExact(f, walk.next(WALK));
            assertNoFlags(f);
        }
    }

    @Test
    void walkingBetweenTheUseSpeedAndTheWalkingSpeedWhileTheHandShowsAnotherItemIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            mainHandRestart(f, walk, sword());
            f.selectHotbarSlot(1);
            assertTrue(rejectsKeys(f.tick(walk.peek((SLOWED + WALK) / 2), true)));
        }
    }

    @Test
    void walkingBetweenTheUseSpeedAndTheWalkingSpeedOnceTheHandIsEmptyIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            mainHandRestart(f, walk, SimItemStack.EMPTY);
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET, SimItemStack.EMPTY);
            assertTrue(rejectsKeys(f.tick(walk.peek((SLOWED + WALK) / 2), true)));
        }
    }

    /** Once the server started the restarted use, the walking speed flags NoSlow as for a use held without a restart. */
    @Test
    void walkingAtTheWalkingSpeedWhileTheServerStartedUseIsHeldIsFlaggedAsWithoutARestart() throws Exception {
        String held;
        double heldViolations;
        uses = 0;
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            slowedWalk(f, walk, Hand.OFF_HAND);
            for (int tick = 0; tick < 3; tick++) assertExact(f, walk.next(SLOWED));
            held = flags(f.tick(walk.peek(WALK), true));
            heldViolations = f.player.checkManager.getCheck(NoSlow.class).violations;
        }
        uses = 0;
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            int restart = restart(f, walk, 1);
            answer(f, restart, (byte) 3);
            for (int tick = 0; tick < 2; tick++) assertExact(f, walk.next(SLOWED));
            assertEquals(held, flags(f.tick(walk.peek(WALK), true)));
            double violations = f.player.checkManager.getCheck(NoSlow.class).violations;
            assertTrue(violations > 0);
            assertEquals(heldViolations, violations);
        }
    }

    /** LivingEntity#travelFallFlying adds no keys, so the restart changes nothing a glide decides. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void aGlideDuringTheRestartIsJudgedAsWithoutIt(Phase phase) throws Exception {
        assertSameDecision(phase, (f, walk) -> {
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));
            f.place(GLIDE_FROM, GLIDE_CARRIED);
            f.command(PlayerCommandAction.START_FLYING_WITH_ELYTRA);
            assertTrue(f.player.isGliding);
            return f.tick(GLIDE_FROM.add(GLIDE_CARRIED.x, 0.0, GLIDE_CARRIED.z + 1.0), false);
        });
    }

    /** LocalPlayer#modifyInput leaves a passenger's keys unscaled, so the restart changes nothing a ride decides. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void aRideDuringTheRestartIsJudgedAsWithoutIt(Phase phase) throws Exception {
        assertSameDecision(phase, (f, walk) -> {
            Vec3 horseAt = new Vec3(0.5, FLOOR_Y, 3.5);
            f.place(horseAt.add(0, 1, 0), Vec3.ZERO);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, horseAt, 0, 0, 0);
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE)).hasSaddle = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            return f.rideTick(horseAt.add(0, 0, 1.0), true);
        });
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
    @ParameterizedTest
    @EnumSource(Phase.class)
    void movingFurtherThanTheKnockbackAndTheWalkingInputDuringTheRestartIsFlagged(Phase phase) throws Exception {
        PredictionResult result = afterRestart(
                phase, this::knockedBack, walk -> new Vec3(0.5 + KNOCKBACK, FLOOR_Y, walk.z + WALK * 1.5), true);
        assertTrue(flagged(result), flags(result));
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void jumpingHigherThanAJumpDuringTheRestartIsFlagged(Phase phase) throws Exception {
        PredictionResult result =
                afterRestart(phase, this::jumping, walk -> new Vec3(0.5, FLOOR_Y + 0.6, walk.peek(SLOWED).z), false);
        assertTrue(flagged(result), flags(result));
    }

    /** Ice lowers the ground input below the walking input of stone. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void walkingAtTheStoneWalkingSpeedOnIceDuringTheRestartIsFlagged(Phase phase) throws Exception {
        PredictionResult result = afterRestart(phase, this::onIce, walk -> new Vec3(0.5, FLOOR_Y, walk.z + WALK), true);
        assertTrue(flagged(result), flags(result));
    }

    /** Water lowers the input below the walking input on land. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void walkingAtTheLandWalkingSpeedInWaterDuringTheRestartIsFlagged(Phase phase) throws Exception {
        PredictionResult result =
                afterRestart(phase, this::inWater, walk -> new Vec3(0.5, FLOOR_Y, walk.z + WALK), true);
        assertTrue(flagged(result), flags(result));
    }

    /** On ice the restart adds the ice input scaled by the use's multiplier, and no input between it and the unslowed one. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void onIceTheRestartAddsOnlyTheSlowedIceInput(Phase phase) throws Exception {
        assertExact(afterRestart(phase, this::onIce, walk -> new Vec3(0.5, FLOOR_Y, walk.z + SLOWED_ON_ICE), true));
        assertTrue(rejectsKeys(afterRestart(
                phase, this::onIce, walk -> new Vec3(0.5, FLOOR_Y, walk.z + (SLOWED_ON_ICE + WALK_ON_ICE) / 2), true)));
    }

    /** In water the restart adds the fluid input scaled by the use's multiplier, and no input between it and the unslowed one. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void inWaterTheRestartAddsOnlyTheSlowedFluidInput(Phase phase) throws Exception {
        assertExact(afterRestart(phase, this::inWater, walk -> new Vec3(0.5, FLOOR_Y, walk.z + SLOWED_IN_WATER), true));
        assertTrue(rejectsKeys(afterRestart(
                phase,
                this::inWater,
                walk -> new Vec3(0.5, FLOOR_Y, walk.z + (SLOWED_IN_WATER + WALK_IN_WATER) / 2),
                true)));
    }

    /** Knockback replaces the velocity; the restart adds only the slowed keys to it. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void underKnockbackTheRestartAddsOnlyTheSlowedInput(Phase phase) throws Exception {
        assertExact(afterRestart(
                phase, this::knockedBack, walk -> new Vec3(0.5 + KNOCKBACK, FLOOR_Y, walk.z + SLOWED), true));
        PredictionResult between = afterRestart(
                phase,
                this::knockedBack,
                walk -> new Vec3(0.5 + KNOCKBACK, FLOOR_Y, walk.z + (SLOWED + WALK) / 2),
                true);
        assertTrue(flagged(between), flags(between));
    }

    /** A jump keeps its impulse; the restart adds only the slowed keys to the jump's horizontal move. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void inAJumpTheRestartAddsOnlyTheSlowedInput(Phase phase) throws Exception {
        assertExact(afterRestart(phase, this::jumping, walk -> jumped(walk.peek(SLOWED)), false));
        PredictionResult between =
                afterRestart(phase, this::jumping, walk -> jumped(walk.peek((SLOWED + WALK) / 2)), false);
        assertTrue(flagged(between), flags(between));
    }

    /** A client that takes the server's knockback in full and keeps the slowed keys passes AntiKB. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void aFullKnockbackWithTheSlowedKeysPassesAntiKb(Phase phase) throws Exception {
        for (Vec3 knockback : KNOCKBACKS) {
            assertKnockback(phase, knockback, walk -> knocked(walk, knockback, 1.0, SLOWED), false);
        }
    }

    /** Knockback the client did not take in full flags AntiKB, whichever input the restart allows it adds. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void aKnockbackTheClientDidNotTakeInFullFlagsAntiKb(Phase phase) throws Exception {
        for (Vec3 knockback : KNOCKBACKS) {
            for (double input : new double[] {SLOWED, WALK}) {
                for (double taken : PART_TAKEN) {
                    assertKnockback(phase, knockback, walk -> knocked(walk, knockback, taken, input), true);
                }
                assertKnockback(phase, knockback, walk -> walk.peek(input), true);
            }
        }
    }

    /** A client that takes an explosion's knockback in full and keeps the slowed keys passes AntiExplosion. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void aFullExplosionWithTheSlowedKeysPassesAntiExplosion(Phase phase) throws Exception {
        for (Vec3 explosion : EXPLOSIONS) {
            assertExplosion(phase, explosion, walk -> exploded(walk, explosion, 1.0, SLOWED), false);
        }
    }

    /** An explosion the client did not take in full flags AntiExplosion, whichever input the restart allows it adds. */
    @ParameterizedTest
    @EnumSource(Phase.class)
    void anExplosionTheClientDidNotTakeInFullFlagsAntiExplosion(Phase phase) throws Exception {
        for (Vec3 explosion : EXPLOSIONS) {
            for (double input : new double[] {SLOWED, WALK}) {
                for (double taken : PART_TAKEN) {
                    assertExplosion(phase, explosion, walk -> exploded(walk, explosion, taken, input), true);
                }
            }
        }
    }

    /** A use_item with no completion in flight opens no restart and leaves the open restart's cap where it was. */
    @Test
    void aUseItemWithoutACompletionInFlightLeavesTheRestartsCap() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            int restart = restart(f, walk, 1);
            answer(f, restart, (byte) -1);
            for (int tick = 2; tick <= 10; tick++) assertExact(f, walk.next(SLOWED));
            int next = useItem(f, Hand.OFF_HAND);
            assertFalse(f.player.clientItemUse.restartsBeforeCompletion());
            assertExact(f, walk.next(SLOWED));
            answer(f, next, (byte) 0);
            for (int tick = 12; tick < USE_DURATION; tick++) assertExact(f, walk.next(SLOWED));
            assertTrue(rejectsKeys(f.tick(walk.peek(SLOWED), true)));
        }
    }

    /**
     * The client releases and changes its slot before it has handled the completion, and takes a knockback sent before
     * that completion at the release tick: its restarted use slows the keys through all of it.
     */
    @Test
    void theRestartedUseSlowsTheKeysThroughAReleaseAndASlotChangeBeforeTheCompletionIsHandled() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            var drift = new Drift();
            int completion = releaseBeforeTheCompletion(f, walk);
            walk.velocity = 0.0;
            assertExact(f, drift.knocked(walk.next(SLOWED)));
            assertExact(f, drift.next(walk.next(SLOWED)));
            f.selectHotbarSlot(1);
            assertExact(f, drift.next(walk.next(SLOWED)));
            handleCompletion(f, completion);
            for (int tick = 0; tick < 2; tick++) assertExact(f, drift.next(walk.next(SLOWED)));
            f.releaseUsingItem();
            for (int tick = 0; tick < 4; tick++) assertExact(f, drift.next(walk.next(SLOWED)));
            assertExact(f, drift.next(walk.next(WALK)));
            assertNoFlags(f);
            assertEquals(0, antiKb(f));
        }
    }

    /** The same order with a jump at the release tick: the jump's impulse, the knockback and the slowed keys. */
    @Test
    void aKnockbackAndAJumpAtAReleaseBeforeTheCompletionIsHandledTakeTheSlowedKeys() throws Exception {
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            releaseBeforeTheCompletion(f, walk);
            f.input(JUMP_FORWARD);
            PredictionResult result =
                    f.tick(new Vec3(0.5 + KNOCKBACK, FLOOR_Y + (double) 0.42F, walk.z + SLOWED), false);
            assertExact(result);
            assertEquals(0, antiKb(f), flags(result));
        }
    }

    /** LocalPlayer#canStartSprinting proves the restarted use no longer slows the keys. */
    @Test
    void sprintingAtTheSlowedSprintingSpeedAfterASprintStartDuringTheRestartIsFlagged() throws Exception {
        assertTrue(rejectsKeys(afterRestart(this::sprinting, walk -> walk.peek(SLOWED_SPRINT), true)));
    }

    @Test
    void sprintingAtTheSprintingSpeedAfterASprintStartDuringTheRestartIsPredicted() throws Exception {
        assertExact(afterRestart(this::sprinting, walk -> walk.peek(SPRINT), true));
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
        return restartAfterSlowedWalk(f, walk, Hand.OFF_HAND, useItems);
    }

    /** A restart of a main hand use while hotbar slot 1 holds {@code other}; the use_item's sequence. */
    private int mainHandRestart(JavaTickFixture f, Walk walk, SimItemStack other) {
        f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET + 1, other);
        slowedWalk(f, walk, Hand.MAIN_HAND);
        return restartAfterSlowedWalk(f, walk, Hand.MAIN_HAND, 1);
    }

    private int restartAfterSlowedWalk(JavaTickFixture f, Walk walk, Hand hand, int useItems) {
        int completion = sendCompletion(f);
        int last = -1;
        for (int i = 0; i < useItems; i++) last = useItem(f, hand);
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
    private PredictionResult afterRestart(
            BiConsumer<JavaTickFixture, Walk> setup, Function<Walk, Vec3> to, boolean onGround) throws Exception {
        return afterRestart(Phase.COMPLETED, setup, to, onGround);
    }

    /** One tick to {@code to} in a fresh fixture, after a restart left open at {@code phase} and {@code setup}. */
    private PredictionResult afterRestart(
            Phase phase, BiConsumer<JavaTickFixture, Walk> setup, Function<Walk, Vec3> to, boolean onGround)
            throws Exception {
        uses = 0;
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            openRestart(f, walk, phase);
            setup.accept(f, walk);
            return f.tick(to.apply(walk), onGround);
        }
    }

    /** Where in an open restart the next tick falls. */
    private enum Phase {
        /** Right after the client handled the completion. */
        COMPLETED,
        /** After the client handled the server's refusal of the restarted use_item. */
        REFUSED,
        /** After a release and a main-hand slot change. */
        RELEASED,
        /** After the refusal, two ticks before the use duration ends. */
        LATE
    }

    /** A restart with one use_item, left open at {@code phase} with every tick slowed. */
    private void openRestart(JavaTickFixture f, Walk walk, Phase phase) {
        int restart = restart(f, walk, 1);
        switch (phase) {
            case COMPLETED -> {}
            case REFUSED -> {
                answer(f, restart, (byte) -1);
                assertExact(f, walk.next(SLOWED));
            }
            case RELEASED -> {
                f.releaseUsingItem();
                f.selectHotbarSlot(1);
                assertExact(f, walk.next(SLOWED));
            }
            case LATE -> {
                answer(f, restart, (byte) -1);
                for (int tick = 2; tick < USE_DURATION - 2; tick++) assertExact(f, walk.next(SLOWED));
            }
        }
    }

    /** One tick to {@code to} after {@code knockback} at {@code phase}: AntiKB flags it, or it is predicted exactly. */
    private void assertKnockback(Phase phase, Vec3 knockback, Function<Walk, Vec3> to, boolean flags) throws Exception {
        uses = 0;
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            openRestart(f, walk, phase);
            f.pongThrough(f.velocity(knockback));
            PredictionResult result = f.tick(to.apply(walk), true);
            if (flags) {
                assertTrue(antiKb(f) > 0, phase + " " + knockback + " " + flags(result));
            } else {
                assertExact(result);
                assertEquals(0, antiKb(f), phase + " " + knockback + " " + flags(result));
            }
        }
    }

    /** One tick to {@code to} after {@code explosion} at {@code phase}: AntiExplosion flags it, or it is exact. */
    private void assertExplosion(Phase phase, Vec3 explosion, Function<Walk, Vec3> to, boolean flags) throws Exception {
        uses = 0;
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            openRestart(f, walk, phase);
            f.explosion(explosion);
            PredictionResult result = f.tick(to.apply(walk), true);
            double violations = f.player.checkManager.getExplosionHandler().violations;
            if (flags) {
                assertTrue(violations > 0, phase + " " + explosion + " " + flags(result));
            } else {
                assertExact(result);
                assertEquals(0, violations, phase + " " + explosion + " " + flags(result));
            }
        }
    }

    /** A knockback replaces the velocity: {@code taken} of it and the keys' {@code input}. */
    private static Vec3 knocked(Walk walk, Vec3 knockback, double taken, double input) {
        return new Vec3(0.5 + taken * knockback.x, FLOOR_Y, walk.z + taken * knockback.z + input);
    }

    /** An explosion adds to the velocity: the carried walk, {@code taken} of it and the keys' {@code input}. */
    private static Vec3 exploded(Walk walk, Vec3 explosion, double taken, double input) {
        return new Vec3(0.5 + taken * explosion.x, FLOOR_Y, walk.z + walk.velocity + taken * explosion.z + input);
    }

    private static double antiKb(JavaTickFixture f) {
        return f.player.checkManager.getKnockbackHandler().violations;
    }

    /**
     * A main-hand restart whose completion the server sent after a knockback; the client takes one slowed tick, sends
     * RELEASE_USE_ITEM and handles the knockback, but not yet the completion. Returns the completion's ping.
     */
    private int releaseBeforeTheCompletion(JavaTickFixture f, Walk walk) {
        f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET + 1, sword());
        slowedWalk(f, walk, Hand.MAIN_HAND);
        int knockback = f.velocity(new Vec3(KNOCKBACK, RESTING_Y, 0.0));
        int completion = sendCompletion(f);
        useItem(f, Hand.MAIN_HAND);
        assertExact(f, walk.next(SLOWED));
        f.releaseUsingItem();
        f.pongThrough(knockback);
        return completion;
    }

    /**
     * One tick from {@code tick} in two fresh fixtures, after a completion the client handled without a restart and
     * with one left open at {@code phase}: both decide the same and flag.
     */
    private void assertSameDecision(Phase phase, BiFunction<JavaTickFixture, Walk, PredictionResult> tick)
            throws Exception {
        String without;
        uses = 0;
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            slowedWalk(f, walk, Hand.OFF_HAND);
            int completion = sendCompletion(f);
            assertExact(f, walk.next(SLOWED));
            handleCompletion(f, completion);
            without = flags(tick.apply(f, walk));
        }
        uses = 0;
        try (var f = new JavaTickFixture()) {
            var walk = new Walk();
            openRestart(f, walk, phase);
            PredictionResult with = tick.apply(f, walk);
            assertTrue(flagged(with), flags(with));
            // The ticks before a later phase carry into the tick's offset, not into the checks that flag it.
            if (phase == Phase.COMPLETED) assertEquals(without, flags(with));
            else assertEquals(checks(without), checks(flags(with)));
        }
    }

    /** The names of the checks a {@link #flags} text lists. */
    private static List<String> checks(String flags) {
        return Arrays.stream(flags.split(" "))
                .filter(word -> word.contains("="))
                .map(word -> word.substring(0, word.indexOf('=')))
                .toList();
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

    private static SimItemStack sword() {
        return ItemUtil.modelItems().stack("minecraft:diamond_sword", 1);
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

    /** The x of a walk the knockback pushed across; the walk keeps the horizontal velocity above aiStep's cutoff. */
    private static final class Drift {
        double x = 0.5;
        double velocity;

        Vec3 knocked(Vec3 at) {
            x += KNOCKBACK;
            velocity = KNOCKBACK * FRICTION;
            return new Vec3(x, at.y, at.z);
        }

        Vec3 next(Vec3 at) {
            x += velocity;
            velocity *= FRICTION;
            return new Vec3(x, at.y, at.z);
        }
    }
}
