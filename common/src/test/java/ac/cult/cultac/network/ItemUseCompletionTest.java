package ac.cult.cultac.network;

import static ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item;
import static ac.cult.cultac.network.ItemUseTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintA;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.events.packets.listeners.PacketPlayerCooldown;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCooldown;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ItemUseInventory;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Player.handleEntityEvent(9) across every client reality of the completing hand and slot. */
class ItemUseCompletionTest {
    private static final String GROUP = "test:shared";

    private RecordConsumerServices services;
    private RecordReceiveFixture fixture;
    private CultPlayer player;
    private InventoryStorage storage;
    private int sequence;

    @BeforeEach
    void connect() throws Exception {
        services = new RecordConsumerServices();
        fixture = ItemUseTestSupport.fixture();
        player = fixture.player;
        player.platformPlayer = mock(PlatformPlayer.class);
        player.gamemode = GameMode.SURVIVAL;
        player.entityID = 7;
        player.food = 10;
        player.packetStateData.clientSidePosition = new Vec3(player.x, player.y, player.z);
        storage = player.getInventory().inventory.getInventoryStorage();
    }

    @AfterEach
    void disconnect() throws Exception {
        fixture.close();
        services.close();
    }

    @Test
    void mainHandCompletionKeepsOffhandCooldownPossible() {
        // The empty hotbar slots keep a silent local stop possible, so completion is uncertain.
        storage.setItem(Inventory.HOTBAR_OFFSET, food("minecraft:apple", 3, 0.5));
        storage.setItem(Inventory.SLOT_OFFHAND, food("minecraft:golden_apple", 2, 0.5));
        eat(Hand.MAIN_HAND);
        assertEquals(3, storage.getItem(Inventory.HOTBAR_OFFSET).getCount());

        // Vanilla started the shared cooldown locally; ItemStack.use returns PASS for the offhand.
        assertRefused(Hand.OFF_HAND);
        // The server may equally have refused the use, so no slowdown is owed to it.
        assertFalse(player.clientItemUse.hasObligation());
        assertEquals(2, storage.getItem(Inventory.SLOT_OFFHAND).getCount());

        ageCooldowns(10);
        assertStarts(Hand.OFF_HAND);

        // ClientboundCooldown sets the group outright; a later offhand use is then refused definitely.
        receiveCooldown(GROUP, 100);
        assertTrue(player.checkManager.getCompensatedCooldown().hasItem(storage.getItem(Inventory.SLOT_OFFHAND)));
    }

    @Test
    void unconfirmedHotbarAppliesOnlySharedEffects() {
        fillHotbar(() -> food("minecraft:apple", 3, 5));
        storage.setItem(Inventory.SLOT_OFFHAND, food("minecraft:golden_apple", 2, 5));
        player.attackStrength.markUnconfirmedHotbarSlot(4);
        use(Hand.MAIN_HAND);
        tick();
        assertTrue(player.isClientUsingItem());
        complete();

        // Every reality ate one apple and started the group; which slot shrank is unknown.
        assertEquals(14, player.food);
        assertTrue(player.checkManager.getCompensatedCooldown().hasItem(storage.getItem(Inventory.SLOT_OFFHAND)));
        for (int slot = 0; slot < 9; slot++)
            assertEquals(3, storage.getItem(Inventory.HOTBAR_OFFSET + slot).getCount());
        assertRefused(Hand.OFF_HAND);
    }

    @Test
    void confirmedHotbarCompletesTheSelectedSlot() {
        fillHotbar(() -> food("minecraft:apple", 3, 5));
        storage.setItem(Inventory.SLOT_OFFHAND, food("minecraft:golden_apple", 2, 5));
        eat(Hand.MAIN_HAND);

        assertEquals(14, player.food);
        assertEquals(2, storage.getItem(Inventory.HOTBAR_OFFSET).getCount());
        for (int slot = 1; slot < 9; slot++) {
            var stack = storage.getItem(Inventory.HOTBAR_OFFSET + slot);
            assertEquals(3, stack.getCount());
            assertFalse(player.clientItemUse.possiblyChanged(stack));
        }
        var cooldowns = player.checkManager.getCompensatedCooldown();
        assertTrue(cooldowns.hasItem(storage.getItem(Inventory.SLOT_OFFHAND)));
        assertFalse(cooldowns.possiblyHasItem(storage.getItem(Inventory.SLOT_OFFHAND)));
    }

    @Test
    void anUnprovenRemainderKeepsTheNextMainHandUseUncertain() {
        fillHotbar(() -> item("minecraft:mushroom_stew"));
        eat(Hand.MAIN_HAND);
        assertEquals(16, player.food);

        // The client may hold a bowl in any slot, and BowlItem has no use.
        assertRefused(Hand.MAIN_HAND);

        // Received contents replace each stack, which proves them again.
        player.clientItemUse.release();
        fillHotbar(() -> item("minecraft:mushroom_stew"));
        assertStarts(Hand.MAIN_HAND);
    }

    @Test
    void possiblyFullFoodEndsAtTheHealthProof() {
        player.food = 16;
        storage.setItem(Inventory.HOTBAR_OFFSET, item("minecraft:apple", 3));
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:bread", 2));
        // FoodData from a health packet the client handled before the completion event.
        int earlier = health(player, 16).after();
        eat(Hand.MAIN_HAND);
        assertTrue(player.lastTransactionReceived.get() > earlier);
        assertTrue(player.clientItemUse.possiblyFull());

        int after = health(player, 16).after();
        acknowledge(player, after - 1);
        assertTrue(player.clientItemUse.possiblyFull());
        assertRefused(Hand.OFF_HAND);

        acknowledge(player, after);
        assertFalse(player.clientItemUse.possiblyFull());
        assertEquals(16, player.food);
        use(Hand.OFF_HAND);
        assertTrue(player.isClientUsingItem());
    }

    @Test
    void possiblyFullFoodAllowsAlwaysEdibleFood() {
        player.food = 16;
        storage.setItem(Inventory.HOTBAR_OFFSET, item("minecraft:apple", 3));
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:golden_apple", 2));
        eat(Hand.MAIN_HAND);
        assertStarts(Hand.OFF_HAND);
    }

    @Test
    void creativeCompletionOnlyStartsTheCooldown() {
        player.gamemode = GameMode.CREATIVE;
        player.canInstabuild = true;
        player.isInvulnerable = true;
        fillHotbar(() -> food("minecraft:apple", 3, 5));
        storage.setItem(Inventory.SLOT_OFFHAND, food("minecraft:golden_apple", 2, 5));
        eat(Hand.MAIN_HAND);

        assertTrue(player.checkManager.getCompensatedCooldown().hasItem(storage.getItem(Inventory.SLOT_OFFHAND)));
        for (int slot = 0; slot < 9; slot++)
            assertEquals(3, storage.getItem(Inventory.HOTBAR_OFFSET + slot).getCount());
        assertRefused(Hand.OFF_HAND);
    }

    @Test
    void cancelledConsumptionKeepsTheLocalCooldown() {
        var apple = food("minecraft:apple", 3, 5);
        storage.setItem(Inventory.HOTBAR_OFFSET, apple);
        storage.setItem(Inventory.SLOT_OFFHAND, food("minecraft:golden_apple", 2, 5));
        eat(Hand.MAIN_HAND);

        // A cancelled consume event resends inventory and health, never a cooldown.
        storage.setItem(Inventory.HOTBAR_OFFSET, apple.copy());
        acknowledge(player, health(player, 10).after());
        assertRefused(Hand.OFF_HAND);
    }

    @Test
    void tridentStartIsUncertainWhileCooldownPossible() {
        storage.setItem(Inventory.HOTBAR_OFFSET, food("minecraft:apple", 3, 0.5));
        storage.setItem(Inventory.SLOT_OFFHAND, food("minecraft:trident", 1, 0.5));
        eat(Hand.MAIN_HAND);

        player.actionManager.useItem(Hand.OFF_HAND);
        assertFalse(player.isClientUsingItem());
        assertFalse(player.clientItemUse.hasObligation());
        assertFalse(noSlowFlags(player));

        ageCooldowns(10);
        player.actionManager.useItem(Hand.OFF_HAND);
        assertTrue(player.isClientUsingItem());
        assertTrue(noSlowFlags(player));
    }

    @Test
    void aMetadataRestartIgnoresAPossibleCooldown() {
        storage.setItem(Inventory.HOTBAR_OFFSET, food("minecraft:apple", 3, 5));
        storage.setItem(Inventory.SLOT_OFFHAND, food("minecraft:golden_apple", 2, 5));
        eat(Hand.MAIN_HAND);

        // LocalPlayer.onSyncedDataUpdated calls startUsingItem without a cooldown check.
        ItemUseInventory.metadata(player, (byte) 3);
        assertTrue(player.isClientUsingItem());
        assertEquals(Hand.OFF_HAND, player.getClientItemUseHand());
    }

    @Test
    void aMetadataRestartOfAPossiblyConsumedStackIsUncertain() {
        fillHotbar(() -> item("minecraft:mushroom_stew"));
        player.attackStrength.markUnconfirmedHotbarSlot(4);
        eat(Hand.MAIN_HAND);

        // LivingEntity.startUsingItem uses whatever the slot holds, possibly a bowl.
        ItemUseInventory.metadata(player, (byte) 1);
        assertFalse(player.isClientUsingItem());
        assertFalse(noSlowFlags(player));
    }

    @Test
    void aDefiniteOffhandCompletionStillAppliesItsRemainder() {
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:mushroom_stew"));
        use(Hand.OFF_HAND);
        tick();
        assertTrue(player.isClientUsingItem());
        complete();
        assertEquals("minecraft:bowl", storage.getItem(Inventory.SLOT_OFFHAND).itemKey());
        assertEquals(16, player.food);
    }

    @Test
    void definiteCompletionAddsToTheHighestFood() {
        player.food = 12;
        storage.setItem(Inventory.HOTBAR_OFFSET, item("minecraft:apple", 3));
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:apple", 3));
        eat(Hand.MAIN_HAND);
        player.clientItemUse.release();
        assertEquals(16, player.food);

        use(Hand.OFF_HAND);
        tick();
        assertTrue(player.isClientUsingItem());
        complete();
        player.clientItemUse.release();
        assertEquals(20, player.food);
        assertRefused(Hand.OFF_HAND);
    }

    @Test
    void possibleMealAboveSixPermitsSprinting() {
        player.food = 4;
        storage.setItem(Inventory.HOTBAR_OFFSET, item("minecraft:bread", 3));
        eat(Hand.MAIN_HAND);
        assertEquals(9, player.food);
        player.isSprinting = true;
        assertFalse(sprintAFlags());

        // ClientboundSetHealth proves the hunger level, and sprinting at 4 flags again.
        acknowledge(player, health(player, 4).after());
        assertTrue(sprintAFlags());
    }

    @Test
    void possiblyConsumedArrowKeepsBowStartUncertain() {
        var arrow = edibleArrow(1);
        storage.setItem(Inventory.HOTBAR_OFFSET, arrow);
        storage.setItem(Inventory.HOTBAR_OFFSET + 2, item("minecraft:stone"));
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:bow"));
        eat(Hand.MAIN_HAND);
        assertTrue(player.clientItemUse.possiblyChanged(arrow));
        player.getInventory().selectHotbarSlot(1);

        // BowItem.use fails without ammo in the reality that ate the only arrow.
        assertRefused(Hand.OFF_HAND);
    }

    @Test
    void droppingPossiblyConsumedArrowsKeepsThemUnproven() {
        storage.setItem(Inventory.HOTBAR_OFFSET, edibleArrow(2));
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:bow"));
        eat(Hand.MAIN_HAND);
        player.clientItemUse.release();
        player.getInventory().dropHeldItem(false);
        assertEquals(1, storage.getItem(Inventory.HOTBAR_OFFSET).getCount());

        assertRefused(Hand.OFF_HAND);
    }

    @Test
    void anOffhandSwapClickCarriesAPossiblyConsumedArrowStack() {
        fillHotbar(() -> item("minecraft:bow"));
        storage.setItem(Inventory.HOTBAR_OFFSET, edibleArrow(2));
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:bow"));
        eat(Hand.MAIN_HAND);
        player.clientItemUse.release();

        // A chest menu does not expose the offhand, so the swapped offhand is inferred.
        openChest(1);
        var bow = item("minecraft:bow");
        var click = new InventoryClick(1, 0, 54, 40, WindowClickType.SWAP, Map.of(54, bow), SimItemStack.EMPTY);
        assertTrue(player.getInventory().applyContainerClick(click));
        player.getInventory().closeContainer();
        assertEquals("minecraft:arrow", storage.getItem(Inventory.SLOT_OFFHAND).itemKey());

        assertRefused(Hand.MAIN_HAND);
    }

    @Test
    void aBowWithProvenAmmoStillStartsAndUnslowedWalkingFlags() {
        storage.setItem(Inventory.HOTBAR_OFFSET, edibleArrow(1));
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:bow"));
        player.getInventory().selectHotbarSlot(1);
        assertStarts(Hand.OFF_HAND);
    }

    @Test
    void completionAfterConfigurationStillRefusesEating() {
        player.food = 16;
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:bread", 2));
        use(Hand.OFF_HAND);
        tick();
        assertTrue(player.isClientUsingItem());

        int proof = sendCompletion();
        startConfiguration();
        // The previous LocalPlayer completes the bread and answers the proof before the configuration.
        assertTrue(player.addTransactionResponse(proof));
        assertEquals(20, player.food);
        assertEquals(1, storage.getItem(Inventory.SLOT_OFFHAND).count());
        assertRefused(Hand.OFF_HAND);
    }

    @Test
    void aMealProvenAfterStartConfigurationPermitsSprinting() {
        player.food = 4;
        storage.setItem(Inventory.SLOT_OFFHAND, item("minecraft:bread", 2));
        use(Hand.OFF_HAND);
        tick();
        player.isSprinting = true;
        assertTrue(sprintAFlags());

        int proof = sendCompletion();
        startConfiguration();
        assertTrue(player.addTransactionResponse(proof));
        assertEquals(9, player.food);
        assertFalse(sprintAFlags());
    }

    private static SimItemStack food(String key, int count, double seconds) {
        var stack = item(key, count);
        stack.setComponent(
                "minecraft:use_cooldown",
                CanonicalSnbt.parse("{seconds:" + seconds + "f,cooldown_group:'" + GROUP + "'}"));
        return stack;
    }

    private static SimItemStack edibleArrow(int count) {
        var stack = item("minecraft:arrow", count);
        stack.setComponent("minecraft:consumable", CanonicalSnbt.parse("{consume_seconds:1.6f}"));
        return stack;
    }

    private void fillHotbar(Supplier<SimItemStack> stack) {
        for (int slot = 0; slot < 9; slot++) storage.setItem(Inventory.HOTBAR_OFFSET + slot, stack.get());
    }

    private void use(Hand hand) {
        player.compensatedWorld.advanceClientPredictionSequence();
        PlaceHandler.handleQueuedUseItem(player, new ServerboundUseItem(hand, ++sequence, player.xRot, player.yRot));
    }

    private void tick() {
        player.packetStateData.acceptedClientTick++;
        ItemUseInventory.prepare(player);
    }

    /** Starts a use, binds it at an entity tick, and completes it at a proven status 9. */
    private void eat(Hand hand) {
        use(hand);
        tick();
        complete();
    }

    /** The use does not start, and unslowed walking after it is legal. */
    private void assertRefused(Hand hand) {
        use(hand);
        assertFalse(player.isClientUsingItem());
        assertFalse(noSlowFlags(player));
    }

    private void assertStarts(Hand hand) {
        use(hand);
        assertTrue(player.isClientUsingItem());
        assertTrue(noSlowFlags(player));
    }

    private void ageCooldowns(int ticks) {
        for (int tick = 0; tick < ticks; tick++)
            player.checkManager.getCompensatedCooldown().onPlayerTickEnd(null);
    }

    private void complete() {
        assertTrue(player.addTransactionResponse(sendCompletion()));
    }

    /** Sends status 9 with its proof and returns the proof's ping ID, unanswered. */
    private int sendCompletion() {
        var packet = new ClientboundEntityEvent(player.entityID, (byte) 9);
        var event = sendEvent(player, ClientboundPackets.ENTITY_EVENT, packet);
        player.packetEntityReplication.onEntityEvent(event, player, packet);
        return sendProof(event);
    }

    private void startConfiguration() {
        var packet = new Opaque(ClientboundPackets.START_CONFIGURATION);
        new PacketServerRegistries()
                .onStartConfiguration(
                        sendEvent(player, ClientboundPackets.START_CONFIGURATION, packet), player, packet);
    }

    private void openChest(int id) {
        var packet = new ClientboundOpenScreen(id, "minecraft:generic_9x3");
        var event = sendEvent(player, ClientboundPackets.OPEN_SCREEN, packet);
        player.getInventory().onOpenScreen(event, player, packet);
        assertTrue(player.addTransactionResponse(sendProof(event)));
    }

    private void receiveCooldown(String group, int ticks) {
        var packet = new ClientboundCooldown(group, ticks);
        var event = sendEvent(player, ClientboundPackets.COOLDOWN, packet);
        new PacketPlayerCooldown().onCooldown(event, player, packet);
        assertTrue(player.addTransactionResponse(sendProof(event)));
    }

    /** Runs the event's after-send tasks and returns its trailing proof's ping ID. */
    private static int sendProof(PacketSendEvent<?> event) {
        var ping = (ClientboundPing) event.writesAfter().getLast().packet();
        event.tasksAfter().forEach(Runnable::run);
        return ping.id();
    }

    private boolean sprintAFlags() {
        var check = player.checkManager.getListener(SprintA.class);
        double before = check.violations;
        var realities = new ArrayList<PredictionResult>();
        var result =
                new PredictionResult(player, new PredVector(Vec3.ZERO), null, Vec3.ZERO, null, realities, realities);
        realities.add(result);
        check.onPredictionComplete(new PredictionComplete(result));
        return check.violations > before;
    }
}
