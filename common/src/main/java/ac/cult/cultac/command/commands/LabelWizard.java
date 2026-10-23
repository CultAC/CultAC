package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

/**
 * One staff member's label menu: the target, the verdict, the checks picked in order, the confidence and the span, and
 * the window frozen when the review opens; a Clean label has no span and takes no window. A fresh one replaces the old
 * on every {@code /cult label <player>}; it goes
 * when its staff member leaves or confirms. Each carries a token the menu's clicks quote, so a click from an older
 * menu never acts on a newer one's target.
 */
final class LabelWizard {
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 30;
    static final int DEFAULT_DAYS = 7;
    static final long DAY_MS = 86_400_000L;
    static final String HIGH = "high";
    static final String EXTREME = "extreme";

    /** Display names of {@link LabelRequest#CHEATS}, in its order. */
    static final Map<String, String> NAMES = names();

    private static final Map<UUID, LabelWizard> OPEN = new ConcurrentHashMap<>();

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
        CHECKS,
        SPAN,
        REVIEW
    }

    final UUID staff;
    final UUID token;
    final CloudArguments.Target target;
    private @Nullable Verdict verdict;
    private final Set<String> checks = new LinkedHashSet<>();
    private String confidence = HIGH;
    private int days;
    private boolean nothingPicked;
    private long from;
    private long to;

    LabelWizard(UUID staff, CloudArguments.Target target, int days) {
        this.staff = staff;
        this.token = UUID.randomUUID();
        this.target = target;
        this.days = days(days);
    }

    /** Opens a fresh menu for {@code staff}, replacing any other. */
    static LabelWizard open(UUID staff, CloudArguments.Target target, int days) {
        LabelWizard wizard = new LabelWizard(staff, target, days);
        OPEN.put(staff, wizard);
        return wizard;
    }

    /** {@code staff}'s menu while {@code token} is still its token; null once it was replaced, confirmed or left. */
    static @Nullable LabelWizard current(UUID staff, UUID token) {
        LabelWizard wizard = OPEN.get(staff);
        return wizard != null && wizard.token.equals(token) ? wizard : null;
    }

    /** Ends this menu, unless a newer one replaced it. */
    void close() {
        OPEN.remove(staff, this);
    }

    static void left(UUID staff) {
        OPEN.remove(staff);
    }

    @Nullable
    Verdict verdict() {
        return verdict;
    }

    List<String> checks() {
        return List.copyOf(checks);
    }

    String confidence() {
        return confidence;
    }

    int days() {
        return days;
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
            checks.clear();
            return Page.SPAN;
        }
        return Page.CHECKS;
    }

    /** Page 2: picks {@code cheat}, or unpicks it. */
    Page toggle(String cheat) {
        if (!NAMES.containsKey(cheat)) return Page.CHECKS;
        if (!checks.remove(cheat)) checks.add(cheat);
        nothingPicked = false;
        return Page.CHECKS;
    }

    /** Page 2's Next: on to the span, or back to the checks when none is picked. */
    Page next() {
        nothingPicked = checks.isEmpty();
        return nothingPicked ? Page.CHECKS : Page.SPAN;
    }

    /** Page 3's Back: the checks, or the verdict for Clean. */
    Page backFromSpan() {
        return verdict == Verdict.CLEAN ? Page.VERDICT : Page.CHECKS;
    }

    /**
     * Page 3's Next: takes the confidence and the span as the menu sent them and freezes the window ending at
     * {@code now}. Clean has no span, so its days stay as they were.
     */
    Page window(@Nullable String chosenConfidence, @Nullable Float chosenDays, long now) {
        if (asksConfidence() && (HIGH.equals(chosenConfidence) || EXTREME.equals(chosenConfidence))) {
            confidence = chosenConfidence;
        }
        if (chosenDays != null && verdict != Verdict.CLEAN) days = days(chosenDays);
        to = now;
        from = now - days * DAY_MS;
        return Page.REVIEW;
    }

    /** The labels page 4's Confirm posts: one per check picked over the window, or one clean label without one. */
    List<LabelRequest.Window> labels(@Nullable String note) {
        if (verdict == null) return List.of();
        String n = note == null || note.isBlank() ? null : note;
        if (verdict == Verdict.CLEAN) return List.of(LabelRequest.Window.clean(confidence, n));
        List<LabelRequest.Window> labels = new ArrayList<>();
        for (String cheat : checks) {
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

    private static Map<String, String> names() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("triggerbot", "Triggerbot");
        names.put("killaura", "KillAura");
        names.put("autoclicker", "Autoclicker");
        names.put("autototem", "Auto Totem");
        names.put("crystalaura", "Crystal Aura");
        names.put("autoanchor", "Auto Anchor");
        names.put("automace", "Auto Mace");
        names.put("shieldbreaker", "Shield Breaker");
        names.put("autostunslam", "Auto Stun Slam");
        names.put("jumpreset", "Jump Reset");
        names.put("chest-stealer", "Chest Stealer");
        names.put("other", "Other");
        if (!List.copyOf(names.keySet()).equals(LabelRequest.CHEATS)) {
            throw new IllegalStateException("every label cheat has one display name, in order");
        }
        return Collections.unmodifiableMap(names);
    }
}
