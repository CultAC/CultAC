package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelCatalog;
import ac.cult.cultac.cloud.labels.LabelRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

/**
 * What a staff member has chosen in the label menu so far: the target, the verdict, the checks picked in order, the
 * confidence and the span, and the window frozen when the review opens; a Clean label has no span and takes no window.
 * Each step returns the page the menu shows next.
 */
final class LabelWizard {
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 30;
    static final int DEFAULT_DAYS = 7;
    static final long DAY_MS = 86_400_000L;
    static final String HIGH = "high";
    static final String EXTREME = "extreme";

    enum Verdict {
        CHEATING("Cheating", NamedTextColor.RED),
        SUSPECTED("Suspected", NamedTextColor.GOLD),
        CLEAN("Clean", NamedTextColor.GREEN);

        final String display;
        final NamedTextColor color;

        Verdict(String display, NamedTextColor color) {
            this.display = display;
            this.color = color;
        }
    }

    enum Page {
        VERDICT,
        CHEATS,
        SPAN,
        REVIEW
    }

    final CloudArguments.Target target;
    private @Nullable Verdict verdict;
    private final Set<String> cheats = new LinkedHashSet<>();
    private String confidence = HIGH;
    private int days;
    private String note = "";
    private boolean nothingPicked;
    private long from;
    private long to;

    LabelWizard(CloudArguments.Target target, int days) {
        this.target = target;
        this.days = days(days);
    }

    @Nullable
    Verdict verdict() {
        return verdict;
    }

    List<String> cheats() {
        return List.copyOf(cheats);
    }

    String confidence() {
        return confidence;
    }

    int days() {
        return days;
    }

    String note() {
        return note;
    }

    long from() {
        return from;
    }

    long to() {
        return to;
    }

    /** Whether the checks page was last left by Next with nothing picked. */
    boolean nothingPicked() {
        return nothingPicked;
    }

    /** Whether the menu asks for a confidence: every verdict but Suspected. */
    boolean asksConfidence() {
        return verdict != Verdict.SUSPECTED;
    }

    /** Page 1: the verdict. Clean skips the checks, since it covers all of them. */
    Page choose(Verdict chosen) {
        verdict = chosen;
        nothingPicked = false;
        if (chosen == Verdict.CLEAN) {
            cheats.clear();
            return Page.SPAN;
        }
        return Page.CHEATS;
    }

    /** Page 2: picks {@code cheat}, or unpicks it. */
    Page toggle(String cheat) {
        if (!LabelCatalog.current().pickable(cheat)) return Page.CHEATS;
        if (!cheats.remove(cheat)) cheats.add(cheat);
        nothingPicked = false;
        return Page.CHEATS;
    }

    /** Page 2's Next: on to the span, or back to the checks when none is picked. */
    Page next() {
        nothingPicked = cheats.isEmpty();
        return nothingPicked ? Page.CHEATS : Page.SPAN;
    }

    /** The page Back on {@code page} shows: the one before it, which for Clean's span is the verdict. */
    Page back(Page page) {
        return switch (page) {
            case VERDICT, CHEATS -> Page.VERDICT;
            case SPAN -> verdict == Verdict.CLEAN ? Page.VERDICT : Page.CHEATS;
            case REVIEW -> Page.SPAN;
        };
    }

    /** Page 3's inputs as the menu sent them, kept on Next and on Back. Clean has no span, so its days stay. */
    void keep(@Nullable String chosenConfidence, @Nullable Float chosenDays) {
        if (asksConfidence() && (HIGH.equals(chosenConfidence) || EXTREME.equals(chosenConfidence))) {
            confidence = chosenConfidence;
        }
        if (chosenDays != null && verdict != Verdict.CLEAN) days = days(chosenDays);
    }

    /** Page 4's note as the menu sent it, kept on Back. */
    void keepNote(@Nullable String typed) {
        note = typed == null ? "" : typed;
    }

    /** Page 3's Next: keeps the confidence and the span and freezes the window ending at {@code now}. */
    Page window(@Nullable String chosenConfidence, @Nullable Float chosenDays, long now) {
        keep(chosenConfidence, chosenDays);
        to = now;
        from = now - days * DAY_MS;
        return Page.REVIEW;
    }

    /**
     * The labels page 4's Confirm posts: one per check picked over the window that the catalog still offers, or one
     * clean label without one.
     */
    List<LabelRequest.Window> labels(@Nullable String note) {
        if (verdict == null) return List.of();
        String n = note == null || note.isBlank() ? null : note;
        if (verdict == Verdict.CLEAN) return List.of(LabelRequest.Window.clean(confidence, n));
        List<LabelRequest.Window> labels = new ArrayList<>();
        for (String cheat : cheats) {
            if (!LabelCatalog.current().pickable(cheat)) continue;
            labels.add(
                    verdict == Verdict.CHEATING
                            ? LabelRequest.Window.cheat(cheat, confidence, from, to, n)
                            : LabelRequest.Window.suspected(cheat, from, to, n));
        }
        return labels;
    }

    /** The slider's value as a whole number of days within the span's range; it arrives as a float. */
    static int days(float raw) {
        if (Float.isNaN(raw)) return DEFAULT_DAYS;
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, Math.round(raw)));
    }
}
