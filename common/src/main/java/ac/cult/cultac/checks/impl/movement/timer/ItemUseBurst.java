package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.prediction.ItemUseBurstResult;
import ac.cult.cultac.checks.impl.prediction.checks.ItemUseInput;
import ac.cult.cultac.checks.impl.prediction.checks.NoSlow;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ItemUseBurstBalance;
import ac.cult.cultac.utils.data.ServerItemUseView;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Balances the real time the server spent using an item against the slowed client ticks claimed
 * meanwhile. Stalling, slowing the tick rate, or desynchronizing the client's item so that the
 * server uses it while the client moves unslowed all leave the server's use unpaid.
 *
 * <p>The client's real-time span of a use contains the span observed here: it sent the use
 * before it arrived, and processes the server's stop after it was sent. Minecraft#runTick runs at
 * most 10 due ticks per frame and drops the rest, so one late frame at the end of a use is within
 * the default limit, while a client frozen during a use accrues real debt.
 */
public final class ItemUseBurst extends Check implements PostPredictionListener, ClientTickEndListener {
    private final ItemUseBurstBalance balance = new ItemUseBurstBalance();
    private final ServerItemUseView view;
    private final LongSupplier now;
    private float sentTickRate = 20;
    private int evidenceTick = Integer.MIN_VALUE;
    private ServerItemUseView.Evidence evidence;

    public ItemUseBurst(CultPlayer player) {
        this(player, System::nanoTime);
    }

    ItemUseBurst(CultPlayer player, LongSupplier now) {
        super(
                player,
                CheckInfo.builder()
                        .name("ItemUseBurst")
                        .stableKey("cult.movement.item_use_burst")
                        .description("Claimed fewer slowed ticks than the server spent using an item")
                        .setback(0)
                        .build());
        this.now = now;
        this.view = new ServerItemUseView(
                this::resolve,
                this::onFinished,
                this::onProgress,
                message -> player.checkManager
                        .getDebugHandler()
                        .relayItemSlow(() -> Component.text("VIEW " + message.get(), NamedTextColor.GRAY)));
        // Check's constructor reloaded before the balance was initialized.
        reload();
    }

    @Override
    public void onReload(ConfigManager config) {
        if (view == null) return;
        balance.configure(
                config.getDoubleElse("cult.prediction.item-use-burst.max-credit-ticks", 2),
                config.getDoubleElse("cult.prediction.item-use-burst.max-debt-ticks", 11));
        view.charge(config.getDoubleElse("cult.prediction.item-use-burst.charge-percent", 95) / 100);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        use(packet.hand(), packet.sequence());
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        // BrushItem#useOn starts using from a block interaction.
        use(packet.hand(), packet.sequence());
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (!player.usesModernJavaItemUse()) return;
        PlayerAction action = packet.action();
        if (action == PlayerAction.RELEASE_USE_ITEM) {
            view.release(now.getAsLong());
        } else if (action == PlayerAction.SWAP_ITEM_WITH_OFFHAND && player.gamemode != GameMode.SPECTATOR) {
            // ServerGamePacketListenerImpl#handlePlayerAction ignores spectator swaps.
            view.swapHands(now.getAsLong());
        }
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        if (player.usesModernJavaItemUse())
            view.selectSlot(packet.slot(), player.packetStateData.lastSlotSelected, now.getAsLong());
    }

    /** ClientboundSetHeldSlot, at send: the server's own selected slot changed. */
    public void serverHeldSlot(int slot) {
        if (player.usesModernJavaItemUse()) view.serverSelectSlot(slot);
    }

    /** Self DATA_LIVING_ENTITY_FLAGS, at send, with the proof the client processes it at. */
    public void serverFlags(byte flags, CultPlayer.TrackedTransaction proof) {
        if (!player.usesModernJavaItemUse()) return;
        view.period(period());
        view.serverFlags((flags & 1) != 0, (flags & 2) != 0, transaction(proof), now.getAsLong());
    }

    /** Entity event 9 (Player#handleEntityEvent completes the use), at send, with its proof. */
    public void serverComplete(CultPlayer.TrackedTransaction proof) {
        if (!player.usesModernJavaItemUse()) return;
        view.serverComplete(transaction(proof), now.getAsLong(), (long) (1_000_000_000D / sentTickRate));
    }

    /**
     * A player slot the server sent (never a click claim it accepted). The server still holding
     * the used item in the used hand refutes a tentative local stop.
     */
    public void serverSlotSent(int storageSlot, SimItemStack item) {
        if (!player.usesModernJavaItemUse() || item == null) return;
        for (Hand hand : Hand.values()) {
            String used = view.activeItem(hand == Hand.OFF_HAND);
            if (used != null && storageSlot == storageSlot(hand) && used.equals(item.itemKey()))
                view.serverKeepsItem("server sent slot " + storageSlot);
        }
    }

    /** A click Cult cancelled: the server never saw it, so it cannot have stopped the use. */
    public void clickCancelled() {
        if (player.usesModernJavaItemUse()) view.serverKeepsItem("click cancelled");
    }

    /** ClientboundBlockChangedAck, at send. */
    public void serverAck(int sequence) {
        if (player.usesModernJavaItemUse()) view.serverAck(sequence);
    }

    /** ClientboundTickingState, at send. Until acknowledged, the slower of both rates bounds the client. */
    public void tickRateSent(float rate) {
        sentTickRate = Math.max(rate, 1F);
        if (player.usesModernJavaItemUse()) view.period(period());
    }

    public void acknowledge(int transaction) {
        view.acknowledge(transaction, now.getAsLong());
    }

    public boolean tracking() {
        return player.usesModernJavaItemUse() && view.tracking();
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (!tracking()) return;
        evidenceTick = player.packetStateData.acceptedClientTick;
        evidence = null;
        // Exempt, teleport, vehicle and fall-flying movement prove nothing about the slowdown.
        if (complete.isTeleport()
                || complete.isExempt()
                || player.gamemode == GameMode.SPECTATOR
                || player.compensatedEntities.getSelf().isDead) return;
        var realities = complete.getPredictionResult().getRealities();
        var inputs = new ArrayList<ItemUseBurstResult>(realities.size());
        for (var reality : realities) {
            var input = reality.getItemUseBurstResult();
            if (input == null) return;
            inputs.add(input);
        }
        if (inputs.isEmpty()) return;
        evidence = multiplier -> {
            double best = Double.POSITIVE_INFINITY;
            for (var input : inputs) best = Math.min(best, ItemUseInput.offset(input, multiplier));
            return best;
        };
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (!player.usesModernJavaItemUse()) return;
        long time = now.getAsLong();
        view.threshold(player.checkManager.getListener(NoSlow.class).threshold());
        view.period(period());
        // LivingEntity#updatingUsingItem stops the client once its hand holds another item. The
        // client-view inventory mirrors its own clicks and drops, and server slots at their proofs.
        for (Hand hand : Hand.values()) {
            String used = view.activeItem(hand == Hand.OFF_HAND);
            if (used != null)
                view.clientHolds(
                        used.equals(player.getInventory().getHandItem(hand).itemKey()));
        }
        var tickEvidence = evidenceTick == player.packetStateData.acceptedClientTick ? evidence : null;
        evidence = null;
        evidenceTick = Integer.MIN_VALUE;
        view.tickEnd(tickEvidence, player.playerClockAtLeast(), time);
    }

    public void reset() {
        view.reset();
        evidence = null;
        evidenceTick = Integer.MIN_VALUE;
    }

    public String describe() {
        return String.format(Locale.ROOT, "balance=%.3f flagAbove=%.3f", balance.balance(), balance.maxDebt());
    }

    private void use(Hand hand, int sequence) {
        if (!player.usesModernJavaItemUse()) return;
        view.period(period());
        view.use(hand == Hand.OFF_HAND, sequence, now.getAsLong(), List.of(serverHandStack(hand)));
    }

    private ServerItemUseView.Profile resolve(boolean offhand, List<SimItemStack> captured) {
        var stacks = new ArrayList<SimItemStack>(captured);
        stacks.add(serverHandStack(offhand ? Hand.OFF_HAND : Hand.MAIN_HAND));
        return ServerItemUseView.profile(stacks, player.getClientVersion());
    }

    /** The stack the server most likely holds in a hand; it only chooses the use's effects. */
    private SimItemStack serverHandStack(Hand hand) {
        int slot = storageSlot(hand);
        SimItemStack server = player.getInventory().serverViewSlot(slot);
        if (server != null) return server;
        SimItemStack local =
                player.getInventory().inventory.getInventoryStorage().getItem(slot);
        return local == null ? SimItemStack.EMPTY : local;
    }

    private int storageSlot(Hand hand) {
        return hand == Hand.OFF_HAND
                ? Inventory.SLOT_OFFHAND
                : Inventory.HOTBAR_OFFSET + view.serverSlot(player.packetStateData.lastSlotSelected);
    }

    private void onFinished(ServerItemUseView.Outcome outcome) {
        double before = balance.balance();
        boolean exceeded = balance.add(outcome.debt());
        relay(outcome, before, exceeded);
        if (exceeded) punish(outcome);
    }

    private boolean onProgress(ServerItemUseView.Outcome outcome) {
        double before = balance.balance();
        if (!balance.settleIfExceeded(outcome.debt())) return false;
        relay(outcome, before, true);
        punish(outcome);
        return true;
    }

    private void punish(ServerItemUseView.Outcome outcome) {
        if (flag(String.format(
                Locale.ROOT,
                "expected=%.2f,ticks=%d,unslowed=%d,debt=%.2f%s",
                outcome.expected(),
                outcome.ticks(),
                outcome.unslowed(),
                outcome.debt(),
                outcome.inProgress() ? ",using" : ""))) setbackIfAboveSetbackVLNonSimulating();
    }

    private void relay(ServerItemUseView.Outcome outcome, double before, boolean exceeded) {
        var debug = player.checkManager.getDebugHandler();
        if (!debug.hasItemSlowListeners()) return;
        double after = balance.balance();
        debug.relayItemSlow(() -> Component.text(
                String.format(
                        Locale.ROOT,
                        "%s expected=%.2f ticks=%d unslowed=%d debt=%+.2f balance=%.2f -> %.2f flagAbove=%.2f%s",
                        outcome.inProgress() ? "USING" : "USE",
                        outcome.expected(),
                        outcome.ticks(),
                        outcome.unslowed(),
                        outcome.debt(),
                        before,
                        after,
                        balance.maxDebt(),
                        exceeded ? " flagged" : ""),
                exceeded || outcome.debt() > 0 ? NamedTextColor.RED : NamedTextColor.GREEN));
    }

    private static int transaction(CultPlayer.TrackedTransaction proof) {
        return proof == null ? ServerItemUseView.NO_PROOF : proof.transaction();
    }

    private long period() {
        boolean frozen =
                player.packetStateData.serverTicksFrozen && player.packetStateData.serverFrozenTickStepsRemaining == 0;
        return Math.max(periodNanos(player.packetStateData.serverTickRate, frozen), periodNanos(sentTickRate, false));
    }

    public static long periodNanos(float rate, boolean frozen) {
        // TickRateManager#setTickRate -> millisecondsPerTick -> Minecraft#getTickTargetMillis.
        // Preserve the client's long-to-float rounding and minimum rate, rather than 1000/rate.
        long nanos = (long) (1_000_000_000D / Math.max(rate, 1F));
        float millis = frozen ? 50F : Math.max(50F, (float) nanos / 1_000_000F);
        return (long) (millis * 1_000_000D);
    }
}
