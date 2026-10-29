package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.labels.LabelCatalog;
import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.command.commands.LabelWizard.Page;
import ac.cult.cultac.command.commands.LabelWizard.Verdict;
import ac.cult.cultac.dialog.Dialog;
import ac.cult.cultac.dialog.DialogViewer;
import ac.cult.cultac.dialog.Dialogs;
import ac.cult.cultac.dialog.Response;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;

/**
 * The label menu for {@code /cult label <player>}: verdict, checks, confidence and span, then a review whose Confirm
 * closes the menu and leaves the outcome to chat ({@link LabelConfirm}). Each page is a {@link Dialog} drawn from the
 * {@link LabelWizard}, so Back shows the page before at once. The labels on file and the flag counts are asked for
 * ahead of the review, which opens with whatever has arrived. Every handler runs on the staff member's thread.
 */
final class LabelMenu {
    private static final int CELL_WIDTH = 110;
    private static final int CARD_WIDTH = 300;
    private static final int BACK_WIDTH = 160;
    private static final String CONFIDENCE = "confidence";
    private static final String SPAN = "span";
    private static final String NOTE = "note";
    private static final LabelClient.Reply NO_LABELS = new LabelClient.Reply(0, "");
    /** The span each staff member last labelled with, offered first next time. */
    private static final Map<UUID, Integer> LAST_DAYS = new ConcurrentHashMap<>();

    private final DialogViewer staff;
    private final Sender sender;
    private final UUID labellerId;
    private final LabelClient client;
    private final boolean mayLabelClean;
    private final LabelWizard wizard;
    private final CompletableFuture<LabelClient.Reply> labelsOnFile;
    /** Each check's flags over the last {@code flagsDays} days; 0 days until first asked. */
    private CompletableFuture<Map<String, Integer>> flags = CompletableFuture.completedFuture(Map.of());

    private int flagsDays;

    private LabelMenu(DialogViewer staff, Sender sender, UUID labellerId, LabelClient client, LabelWizard wizard) {
        this.staff = staff;
        this.sender = sender;
        this.labellerId = labellerId;
        this.client = client;
        this.mayLabelClean = sender.hasPermission(CloudArguments.LABEL_CLEAN);
        this.wizard = wizard;
        this.labelsOnFile = client.list(wizard.target.id()).exceptionally(error -> NO_LABELS);
    }

    /** {@code staffId} as the menu can be shown to them; null when they are not here or their client cannot draw it. */
    static @Nullable DialogViewer viewer(UUID staffId) {
        CultPlayer staff = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(staffId);
        return staff != null && Dialogs.canShow(staff) ? Dialogs.viewer(staff) : null;
    }

    /** Opens a fresh menu on {@code target} for {@code staff}, in place of any dialog they have open. */
    static void open(
            DialogViewer staff, Sender sender, UUID labellerId, LabelClient client, CloudArguments.Target target) {
        int days = LAST_DAYS.getOrDefault(staff.id(), LabelWizard.DEFAULT_DAYS);
        new LabelMenu(staff, sender, labellerId, client, new LabelWizard(target, days)).show(Page.VERDICT);
    }

    private void show(Page page) {
        if ((page == Page.SPAN || page == Page.REVIEW) && verdict() != Verdict.CLEAN) askFlags(wizard.days());
        CultLabel.answering(
                sender,
                () -> staff.show(
                        switch (page) {
                            case VERDICT -> verdictPage();
                            case CHECKS -> checksPage();
                            case SPAN -> spanPage();
                            case REVIEW -> reviewPage();
                        }));
    }

    private Dialog.Builder page() {
        return Dialog.titled(LabelLines.title("Label ", wizard.target.name()));
    }

    private Dialog verdictPage() {
        Dialog.Builder page = page().head(
                        wizard.target.id(),
                        wizard.target.name(),
                        lines(
                                Component.text(wizard.target.name(), NamedTextColor.WHITE, TextDecoration.BOLD),
                                Component.text(LabelLines.status(onlineMillis(), elsewhere()), NamedTextColor.GRAY)),
                        CARD_WIDTH)
                .text(gray("Step 1 · What's your verdict?"));
        for (Verdict verdict : Verdict.values()) {
            if (verdict == Verdict.CLEAN && !mayLabelClean) continue;
            page.button(LabelLines.verdict(verdict), Dialog.DEFAULT_WIDTH, click -> show(wizard.choose(verdict)));
        }
        return page.exit(gray("Cancel"), Dialog.DEFAULT_WIDTH, click -> staff.close())
                .build();
    }

    private Dialog checksPage() {
        TextComponent.Builder line = Component.text()
                .append(LabelLines.verdict(verdict()))
                .append(gray(" · Step 2 · Pick every check you saw"));
        if (wizard.nothingPicked()) {
            line.append(Component.newline()).append(Component.text(LabelLines.PICK_ONE, NamedTextColor.RED));
        }
        Dialog.Builder page = page().text(line.asComponent()).columns(3);
        List<String> picked = wizard.checks();
        for (LabelCatalog.Entry check : LabelCatalog.current().cheats()) {
            Component label = picked.contains(check.id())
                    ? Component.text("☑ " + check.name(), NamedTextColor.WHITE)
                    : gray("☐ " + check.name());
            page.button(label, CELL_WIDTH, click -> show(wizard.toggle(check.id())));
        }
        return page.button(LabelLines.next(), CELL_WIDTH, click -> show(wizard.next()))
                .exit(gray("Back"), Dialog.DEFAULT_WIDTH, click -> show(wizard.back(Page.CHECKS)))
                .build();
    }

    private Dialog spanPage() {
        Verdict verdict = verdict();
        boolean clean = verdict == Verdict.CLEAN;
        String what = clean ? "all checks" : LabelLines.names(wizard.checks());
        TextComponent.Builder line = Component.text()
                .append(LabelLines.verdict(verdict))
                .append(gray(LabelLines.DOT))
                .append(Component.text(what, NamedTextColor.WHITE));
        Dialog.Builder page = page();
        if (wizard.asksConfidence()) {
            line.append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text("⚠ ", NamedTextColor.GOLD))
                    .append(Component.text("Extreme", NamedTextColor.RED, TextDecoration.BOLD))
                    .append(gray(" needs 100% certainty,"))
                    .append(Component.newline())
                    .append(gray("ex: internal testing or a known client."));
            boolean extreme = wizard.confidence().equals(LabelWizard.EXTREME);
            page.input(new Dialog.Choice(
                    CONFIDENCE,
                    Component.text("Confidence"),
                    Dialog.DEFAULT_WIDTH,
                    List.of(
                            new Dialog.Option(LabelWizard.HIGH, Component.text("High"), !extreme),
                            new Dialog.Option(LabelWizard.EXTREME, Component.text("Extreme"), extreme))));
        }
        if (clean) {
            line.append(Component.newline()).append(Component.newline()).append(gray(LabelLines.ALWAYS));
        } else {
            page.input(new Dialog.Slider(
                    SPAN,
                    Component.text("Span"),
                    "%s: last %s days",
                    Dialog.DEFAULT_WIDTH,
                    LabelWizard.MIN_DAYS,
                    LabelWizard.MAX_DAYS,
                    wizard.days()));
        }
        return page.text(line.asComponent())
                .button(
                        LabelLines.next(),
                        Dialog.DEFAULT_WIDTH,
                        click -> show(wizard.window(
                                click.text(CONFIDENCE), clean ? null : click.number(SPAN), System.currentTimeMillis())))
                .exit(gray("Back"), Dialog.DEFAULT_WIDTH, click -> show(wizard.back(Page.SPAN)))
                .build();
    }

    /** Asks for each check's flags over the last {@code days} days, unless that was already asked. */
    private void askFlags(int days) {
        if (days == flagsDays) return;
        long now = System.currentTimeMillis();
        flagsDays = days;
        flags = client.flags(wizard.target.id(), now - days * LabelWizard.DAY_MS, now)
                .exceptionally(error -> Map.of());
    }

    private Dialog reviewPage() {
        Verdict verdict = verdict();
        List<Component> card = new ArrayList<>();
        card.add(LabelLines.presence(wizard.target.name(), onlineMillis(), elsewhere()));
        CultPlayer target = local();
        if (target != null) {
            card.add(gray(target.getTransactionPing() + "ms" + LabelLines.DOT + target.getBrand() + " "
                    + target.getClientVersion().getReleaseName()));
        }

        List<Component> summary = new ArrayList<>();
        summary.add(LabelLines.summary(verdict, wizard.asksConfidence(), wizard.confidence(), wizard.days()));
        summary.add(gray(verdict == Verdict.CLEAN ? LabelLines.ALWAYS : LabelLines.window(wizard.from(), wizard.to())));
        if (verdict != Verdict.CLEAN) {
            Map<String, Integer> counts = flagsDays == wizard.days() ? flags.getNow(null) : null;
            for (String cheat : wizard.checks()) summary.add(LabelLines.check(cheat, counts, wizard.days()));
        }

        Dialog.Builder page = Dialog.titled(LabelLines.title("Review ", wizard.target.name()))
                .head(wizard.target.id(), wizard.target.name(), lines(card), CARD_WIDTH)
                .text(lines(summary), CARD_WIDTH);
        LabelClient.Reply listed = labelsOnFile.getNow(NO_LABELS);
        List<JsonObject> labels = listed.ok() ? LabelClient.labels(listed.body()) : List.of();
        JsonObject conflict = LabelLines.conflict(verdict, labels, wizard.from(), wizard.to());
        if (conflict != null) page.text(lines(LabelLines.conflictLines(verdict, conflict, listed)), CARD_WIDTH);
        return page.input(new Dialog.TextBox(NOTE, gray("Note (optional)"), CARD_WIDTH, LabelRequest.MAX_NOTE))
                .button(
                        Component.text("Confirm", NamedTextColor.WHITE, TextDecoration.BOLD),
                        CARD_WIDTH,
                        click -> confirm(click))
                .exit(gray("Back"), BACK_WIDTH, click -> show(wizard.back(Page.REVIEW)))
                .build();
    }

    private void confirm(Response click) {
        Verdict verdict = wizard.verdict();
        if (verdict == null) return;
        List<LabelRequest.Window> windows = wizard.labels(click.text(NOTE));
        if (windows.isEmpty()) return;
        if (verdict != Verdict.CLEAN) LAST_DAYS.put(staff.id(), wizard.days());
        LabelConfirm.confirm(
                wizard,
                verdict,
                windows,
                sender,
                labellerId,
                client::post,
                staff::close,
                staff::execute,
                staff::online);
    }

    private Verdict verdict() {
        Verdict verdict = wizard.verdict();
        if (verdict == null) throw new IllegalStateException("no verdict");
        return verdict;
    }

    /** The target when they are on this server. */
    private @Nullable CultPlayer local() {
        return CultAPI.INSTANCE.getPlayerDataManager().getPlayer(wizard.target.id());
    }

    /** How long the target has been on this server; null when they are not here. */
    private @Nullable Long onlineMillis() {
        CultPlayer target = local();
        return target == null ? null : Math.max(0, System.currentTimeMillis() - target.timeJoined);
    }

    /** Where else on the network the target is, "" when it does not say; null when nowhere. */
    private @Nullable String elsewhere() {
        return NetworkRouting.current()
                .locate(wizard.target.id().toString())
                .map(player -> player.server() == null ? "" : player.server())
                .orElse(null);
    }

    private static Component lines(Component... lines) {
        return lines(List.of(lines));
    }

    private static Component lines(List<Component> lines) {
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }
}
