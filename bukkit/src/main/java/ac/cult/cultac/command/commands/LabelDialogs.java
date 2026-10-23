package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.command.commands.LabelWizard.Page;
import ac.cult.cultac.command.commands.LabelWizard.Verdict;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import com.google.gson.JsonObject;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

/**
 * The label menu's Paper dialogs: verdict, checks, confidence and span, then review, whose Confirm closes the menu and
 * leaves the outcome to chat ({@link LabelConfirm}). Only reached on Paper
 * for a Java player ({@link CultLabelMenu}); nothing else may load this class, which names Bukkit and Paper types.
 * Clicks arrive on any thread and act on the staff member's thread, on the menu's stored state.
 */
final class LabelDialogs {
    private static final NamespacedKey SPAN_DAYS =
            Objects.requireNonNull(NamespacedKey.fromString("cultac:label_span_days"));
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(30))
            .build();
    private static final long REVIEW_WAIT_MS = 1_500L;
    private static final LabelClient.Reply NO_LABELS = new LabelClient.Reply(0, "");
    private static final int WIDE = 200;
    private static final int CELL = 110;
    private static final int REVIEW = 300;
    private static final int BACK_ON_REVIEW = 160;
    private static final String DOT = " · ";
    private static final String CONFIDENCE = "confidence";
    private static final String SPAN = "span";
    private static final String NOTE = "note";

    private final Sender sender;
    private final PlatformPlayer staff;
    private final UUID labellerId;
    private final LabelClient client;
    private final LabelWizard wizard;

    private LabelDialogs(Sender sender, PlatformPlayer staff, UUID labellerId, LabelClient client, LabelWizard wizard) {
        this.sender = sender;
        this.staff = staff;
        this.labellerId = labellerId;
        this.client = client;
        this.wizard = wizard;
    }

    /** Whether {@code sender} is a Bukkit player. */
    static boolean showable(Sender sender) {
        PlatformPlayer player = sender.getPlatformPlayer();
        return player != null && player.getNative() instanceof Player;
    }

    /** Opens a fresh menu on {@code target} for {@code sender}, on their thread. */
    static void open(Sender sender, UUID labellerId, LabelClient client, CloudArguments.Target target) {
        PlatformPlayer staff = sender.getPlatformPlayer();
        if (staff == null) return;
        onThread(staff, () -> {
            if (!(staff.getNative() instanceof Player player)) return;
            LabelWizard wizard = LabelWizard.open(player.getUniqueId(), target, storedDays(player));
            new LabelDialogs(sender, staff, labellerId, client, wizard).show(player, Page.VERDICT);
        });
    }

    private static void onThread(PlatformPlayer staff, Runnable task) {
        CultAPI.INSTANCE.getScheduler().getEntityScheduler().run(staff, CultAPI.INSTANCE.getGrimPlugin(), task, null);
    }

    private static int storedDays(Player player) {
        Integer days = player.getPersistentDataContainer().get(SPAN_DAYS, PersistentDataType.INTEGER);
        return days == null ? LabelWizard.DEFAULT_DAYS : LabelWizard.days(days);
    }

    /**
     * A click that acts on the staff member's thread while this menu is still theirs; a click from a replaced or
     * finished menu does nothing.
     */
    private DialogAction click(Consumer<Player> action) {
        return DialogAction.customClick((response, audience) -> act(action), ONCE);
    }

    private DialogAction click(ResponseAction action) {
        return DialogAction.customClick((response, audience) -> act(player -> action.accept(player, response)), ONCE);
    }

    /** Closes the dialog, whatever menu it belongs to. */
    private DialogAction close() {
        return DialogAction.customClick(
                (response, audience) -> onThread(staff, () -> {
                    if (staff.getNative() instanceof Player player) player.closeDialog();
                }),
                ONCE);
    }

    @FunctionalInterface
    private interface ResponseAction {
        void accept(Player player, DialogResponseView response);
    }

    private void act(Consumer<Player> action) {
        onThread(
                staff,
                () -> CultLabel.answering(sender, () -> {
                    if (LabelWizard.current(wizard.staff, wizard.token) != wizard) return;
                    if (staff.getNative() instanceof Player player) action.accept(player);
                }));
    }

    private void show(Player player, Page page) {
        switch (page) {
            case VERDICT -> player.showDialog(verdictPage(player));
            case CHECKS -> player.showDialog(checksPage());
            case SPAN -> player.showDialog(spanPage());
            case REVIEW -> review();
        }
    }

    private static Dialog dialog(
            Component title,
            List<? extends DialogBody> body,
            List<? extends DialogInput> inputs,
            List<ActionButton> buttons,
            @Nullable ActionButton exit,
            int columns) {
        DialogBase base = DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .inputs(inputs)
                .build();
        return Dialog.create(
                factory -> factory.empty().base(base).type(DialogType.multiAction(buttons, exit, columns)));
    }

    private static ActionButton button(Component label, int width, DialogAction action) {
        return ActionButton.create(label, null, width, action);
    }

    private Component title() {
        return LabelLines.title("Label ", wizard.target.name());
    }

    private ItemStack head() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setPlayerProfile(Bukkit.createProfile(wizard.target.id(), wizard.target.name()));
            head.setItemMeta(meta);
        }
        return head;
    }

    private DialogBody card(List<Component> lines) {
        return DialogBody.item(head())
                .description(DialogBody.plainMessage(Component.join(JoinConfiguration.newlines(), lines), REVIEW))
                .showDecorations(false)
                .showTooltip(false)
                .build();
    }

    private static @Nullable Long onlineMillis(@Nullable Player target) {
        if (target == null) return null;
        long login = target.getLastLogin();
        return login > 0 ? Math.max(0, System.currentTimeMillis() - login) : 0L;
    }

    private Dialog verdictPage(Player player) {
        Player target = Bukkit.getPlayer(wizard.target.id());
        DialogBody card = card(List.of(
                Component.text(wizard.target.name(), NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text(LabelLines.status(onlineMillis(target)), NamedTextColor.GRAY)));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(verdictButton(Verdict.CHEATING));
        buttons.add(verdictButton(Verdict.SUSPECTED));
        if (player.hasPermission(CloudArguments.LABEL_CLEAN)) buttons.add(verdictButton(Verdict.CLEAN));
        ActionButton cancel = button(Component.text("Cancel", NamedTextColor.GRAY), WIDE, close());
        return dialog(
                title(),
                List.of(
                        card,
                        DialogBody.plainMessage(Component.text("Step 1 · What's your verdict?", NamedTextColor.GRAY))),
                List.of(),
                buttons,
                cancel,
                1);
    }

    private ActionButton verdictButton(Verdict verdict) {
        return button(LabelLines.verdict(verdict), WIDE, click(player -> {
            if (verdict == Verdict.CLEAN && !player.hasPermission(CloudArguments.LABEL_CLEAN)) return;
            show(player, wizard.choose(verdict));
        }));
    }

    private Dialog checksPage() {
        Verdict verdict = wizard.verdict();
        if (verdict == null) throw new IllegalStateException("no verdict");
        TextComponent.Builder line = Component.text()
                .append(LabelLines.verdict(verdict))
                .append(Component.text(" · Step 2 · Pick every check you saw", NamedTextColor.GRAY));
        if (wizard.nothingPicked()) {
            line.append(Component.newline()).append(Component.text(LabelLines.PICK_ONE, NamedTextColor.RED));
        }
        List<String> picked = wizard.checks();
        List<ActionButton> buttons = new ArrayList<>();
        for (Map.Entry<String, String> check : LabelWizard.NAMES.entrySet()) {
            boolean on = picked.contains(check.getKey());
            Component label = on
                    ? Component.text("☑ " + check.getValue(), NamedTextColor.WHITE)
                    : Component.text("☐ " + check.getValue(), NamedTextColor.GRAY);
            buttons.add(button(label, CELL, click(player -> show(player, wizard.toggle(check.getKey())))));
        }
        buttons.add(button(LabelLines.next(), CELL, click(player -> show(player, wizard.next()))));
        ActionButton back =
                button(Component.text("Back", NamedTextColor.GRAY), WIDE, click(player -> show(player, Page.VERDICT)));
        return dialog(title(), List.of(DialogBody.plainMessage(line.asComponent())), List.of(), buttons, back, 3);
    }

    private Dialog spanPage() {
        Verdict verdict = wizard.verdict();
        if (verdict == null) throw new IllegalStateException("no verdict");
        String what = verdict == Verdict.CLEAN
                ? "all checks"
                : String.join(
                        ", ", wizard.checks().stream().map(LabelLines::name).toList());
        TextComponent.Builder line = Component.text()
                .append(LabelLines.verdict(verdict))
                .append(Component.text(DOT, NamedTextColor.GRAY))
                .append(Component.text(what, NamedTextColor.WHITE));
        List<DialogInput> inputs = new ArrayList<>();
        if (wizard.asksConfidence()) {
            line.append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text("⚠ ", NamedTextColor.GOLD))
                    .append(Component.text("Extreme", NamedTextColor.RED, TextDecoration.BOLD))
                    .append(Component.text(" needs 100% certainty,", NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text("ex: internal testing or a known client.", NamedTextColor.GRAY));
            inputs.add(DialogInput.singleOption(
                            CONFIDENCE,
                            Component.text("Confidence"),
                            List.of(
                                    SingleOptionDialogInput.OptionEntry.create(
                                            LabelWizard.HIGH,
                                            Component.text("High"),
                                            !wizard.confidence().equals(LabelWizard.EXTREME)),
                                    SingleOptionDialogInput.OptionEntry.create(
                                            LabelWizard.EXTREME,
                                            Component.text("Extreme"),
                                            wizard.confidence().equals(LabelWizard.EXTREME))))
                    .width(WIDE)
                    .build());
        }
        boolean clean = verdict == Verdict.CLEAN;
        if (clean) {
            line.append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text(LabelLines.ALWAYS, NamedTextColor.GRAY));
        } else {
            inputs.add(DialogInput.numberRange(SPAN, Component.text("Span"), LabelWizard.MIN_DAYS, LabelWizard.MAX_DAYS)
                    .step(1f)
                    .initial((float) wizard.days())
                    .width(WIDE)
                    .labelFormat("%s: last %s days")
                    .build());
        }
        ActionButton next = button(
                LabelLines.next(),
                WIDE,
                click((Player player, DialogResponseView response) -> show(
                        player,
                        wizard.window(
                                wizard.asksConfidence() ? response.getText(CONFIDENCE) : null,
                                clean ? null : response.getFloat(SPAN),
                                System.currentTimeMillis()))));
        ActionButton back = button(
                Component.text("Back", NamedTextColor.GRAY),
                WIDE,
                click(player -> show(player, wizard.backFromSpan())));
        return dialog(title(), List.of(DialogBody.plainMessage(line.asComponent())), inputs, List.of(next), back, 1);
    }

    /**
     * Asks the cloud for the window's flags and the target's labels and opens the review with what arrived within
     * {@link #REVIEW_WAIT_MS}: the review opens once, since showing it again would clear the note. Clean shows no
     * flags, so it asks only for the labels.
     */
    private void review() {
        long from = wizard.from();
        long to = wizard.to();
        CompletableFuture<Map<String, Integer>> flags = wizard.verdict() == Verdict.CLEAN
                ? CompletableFuture.completedFuture(Map.of())
                : client.flags(wizard.target.id(), from, to)
                        .exceptionally(error -> Map.of())
                        .completeOnTimeout(Map.of(), REVIEW_WAIT_MS, TimeUnit.MILLISECONDS);
        CompletableFuture<LabelClient.Reply> listed = client.list(wizard.target.id())
                .exceptionally(error -> NO_LABELS)
                .completeOnTimeout(NO_LABELS, REVIEW_WAIT_MS, TimeUnit.MILLISECONDS);
        CompletableFuture.allOf(flags, listed)
                .whenComplete((done, error) -> act(player -> {
                    if (wizard.to() != to) return;
                    player.showDialog(reviewPage(flags.getNow(Map.of()), listed.getNow(NO_LABELS)));
                }));
    }

    private Dialog reviewPage(Map<String, Integer> flags, LabelClient.Reply listed) {
        Verdict verdict = wizard.verdict();
        if (verdict == null) throw new IllegalStateException("no verdict");
        List<JsonObject> labels = listed.ok() ? LabelClient.labels(listed.body()) : List.of();
        Player target = Bukkit.getPlayer(wizard.target.id());
        List<Component> card = new ArrayList<>();
        card.add(LabelLines.presence(wizard.target.name(), onlineMillis(target)));
        if (target != null) {
            String client = client(target);
            card.add(Component.text(
                    target.getPing() + "ms" + (client.isEmpty() ? "" : DOT + client), NamedTextColor.GRAY));
        }
        String joined = LabelLines.firstJoined(
                Bukkit.getOfflinePlayer(wizard.target.id()).getFirstPlayed());
        if (joined != null) card.add(Component.text(joined, NamedTextColor.GRAY));

        List<Component> summary = new ArrayList<>();
        summary.add(LabelLines.summary(verdict, wizard.asksConfidence(), wizard.confidence(), wizard.days()));
        summary.add(Component.text(
                verdict == Verdict.CLEAN ? LabelLines.ALWAYS : LabelLines.window(wizard.from(), wizard.to()),
                NamedTextColor.GRAY));
        if (verdict != Verdict.CLEAN) {
            for (String cheat : wizard.checks()) summary.add(LabelLines.check(cheat, flags, wizard.days()));
        }
        List<DialogBody> body = new ArrayList<>();
        body.add(card(card));
        body.add(DialogBody.plainMessage(Component.join(JoinConfiguration.newlines(), summary), REVIEW));
        JsonObject conflict = LabelLines.conflict(verdict, labels, wizard.from(), wizard.to());
        if (conflict != null) {
            body.add(DialogBody.plainMessage(
                    Component.join(JoinConfiguration.newlines(), LabelLines.conflictLines(verdict, conflict, listed)),
                    REVIEW));
        }
        TextDialogInput note = DialogInput.text(NOTE, Component.text("Note (optional)", NamedTextColor.GRAY))
                .width(REVIEW)
                .labelVisible(true)
                .initial("")
                .maxLength(LabelRequest.MAX_NOTE)
                .build();
        ActionButton confirm = button(
                Component.text("Confirm", NamedTextColor.WHITE, TextDecoration.BOLD),
                REVIEW,
                click((Player player, DialogResponseView response) -> confirm(player, response.getText(NOTE))));
        ActionButton back = button(
                Component.text("Back", NamedTextColor.GRAY), BACK_ON_REVIEW, click(player -> show(player, Page.SPAN)));
        return dialog(
                LabelLines.title("Review ", wizard.target.name()), body, List.of(note), List.of(confirm), back, 1);
    }

    /** The target's brand and version, as far as CultAC knows them. */
    private static String client(Player target) {
        List<String> parts = new ArrayList<>();
        String brand = target.getClientBrandName();
        if (brand != null && !brand.isBlank()) parts.add(brand);
        CultPlayer tracked = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(target.getUniqueId());
        if (tracked != null) parts.add(tracked.getClientVersion().getReleaseName());
        return String.join(" ", parts);
    }

    private void confirm(Player player, @Nullable String note) {
        Verdict verdict = wizard.verdict();
        if (verdict == null) return;
        List<LabelRequest.Window> windows = wizard.labels(note);
        if (windows.isEmpty()) return;
        wizard.close();
        if (verdict != Verdict.CLEAN) {
            player.getPersistentDataContainer().set(SPAN_DAYS, PersistentDataType.INTEGER, wizard.days());
        }
        LabelConfirm.confirm(
                wizard,
                verdict,
                windows,
                sender,
                labellerId,
                client::post,
                player::closeDialog,
                task -> onThread(staff, task),
                () -> staff.getNative() instanceof Player online && online.isOnline());
    }
}
