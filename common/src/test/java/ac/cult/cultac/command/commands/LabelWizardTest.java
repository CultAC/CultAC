package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.command.commands.LabelWizard.Page;
import ac.cult.cultac.command.commands.LabelWizard.Verdict;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LabelWizardTest {
    private static final UUID STAFF = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final CloudArguments.Target NOTCH =
            new CloudArguments.Target(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch");
    private static final long DAY = LabelWizard.DAY_MS;
    private static final long NOW = 400 * DAY;

    @Test
    void cleanSkipsTheChecksAndComesBackToTheVerdict() {
        LabelWizard wizard = new LabelWizard(STAFF, NOTCH, 7);
        wizard.choose(Verdict.CHEATING);
        wizard.toggle("killaura");
        assertEquals(Page.SPAN, wizard.choose(Verdict.CLEAN));
        assertEquals(List.of(), wizard.checks());
        assertTrue(wizard.asksConfidence());
        assertEquals(Page.VERDICT, wizard.backFromSpan());
    }

    @Test
    void cleanTakesOnlyAConfidenceAndPostsNoSpan() {
        LabelWizard wizard = new LabelWizard(STAFF, NOTCH, 7);
        assertEquals(Page.SPAN, wizard.choose(Verdict.CLEAN));
        assertEquals(Page.REVIEW, wizard.window(LabelWizard.EXTREME, null, NOW));
        List<LabelRequest.Window> labels = wizard.labels("  ");
        assertEquals(List.of(LabelRequest.Window.clean("extreme", null)), labels);
        assertNull(labels.get(0).from());
        assertNull(labels.get(0).to());

        wizard.window(LabelWizard.HIGH, 3f, NOW);
        assertEquals(7, wizard.days());
        assertEquals(List.of(LabelRequest.Window.clean("high", null)), wizard.labels(null));
    }

    @Test
    void suspectedHidesTheConfidence() {
        LabelWizard wizard = new LabelWizard(STAFF, NOTCH, 7);
        assertEquals(Page.CHECKS, wizard.choose(Verdict.SUSPECTED));
        assertFalse(wizard.asksConfidence());
        wizard.toggle("autoclicker");
        assertEquals(Page.SPAN, wizard.next());
        assertEquals(Page.CHECKS, wizard.backFromSpan());
        wizard.window(LabelWizard.EXTREME, 7f, NOW);
        assertEquals(LabelWizard.HIGH, wizard.confidence());
        LabelRequest.Window label = wizard.labels("seen").get(0);
        assertEquals("suspected", label.value());
        assertNull(label.confidence());
        assertEquals("seen", label.note());
    }

    @Test
    void checksToggleInTheOrderPicked() {
        LabelWizard wizard = new LabelWizard(STAFF, NOTCH, 7);
        wizard.choose(Verdict.CHEATING);
        assertEquals(Page.CHECKS, wizard.toggle("killaura"));
        wizard.toggle("triggerbot");
        wizard.toggle("crystalaura");
        wizard.toggle("crystalaura");
        wizard.toggle("speed");
        assertEquals(List.of("killaura", "triggerbot"), wizard.checks());
        wizard.window(null, 7f, NOW);
        List<LabelRequest.Window> labels = wizard.labels(null);
        assertEquals(
                List.of(
                        LabelRequest.Window.cheat("killaura", "high", NOW - 7 * DAY, NOW, null),
                        LabelRequest.Window.cheat("triggerbot", "high", NOW - 7 * DAY, NOW, null)),
                labels);
    }

    @Test
    void nextWithNothingPickedStaysAndSaysSo() {
        LabelWizard wizard = new LabelWizard(STAFF, NOTCH, 7);
        wizard.choose(Verdict.CHEATING);
        assertEquals(Page.CHECKS, wizard.next());
        assertTrue(wizard.nothingPicked());
        wizard.toggle("other");
        assertFalse(wizard.nothingPicked());
        assertEquals(Page.SPAN, wizard.next());
    }

    @Test
    void theSpanIsWholeDaysFromOneToThirty() {
        assertEquals(7, LabelWizard.days(6.6f));
        assertEquals(1, LabelWizard.days(0f));
        assertEquals(30, LabelWizard.days(99f));
        assertEquals(LabelWizard.DEFAULT_DAYS, LabelWizard.days(Float.NaN));
        LabelWizard wizard = new LabelWizard(STAFF, NOTCH, 45);
        assertEquals(30, wizard.days());
        wizard.choose(Verdict.CHEATING);
        wizard.window(LabelWizard.HIGH, null, NOW);
        assertEquals(NOW - 30 * DAY, wizard.from());
        assertEquals(NOW, wizard.to());
    }

    @Test
    void aClickFromAnOlderMenuFindsNothing() {
        UUID staff = UUID.randomUUID();
        LabelWizard first = LabelWizard.open(staff, NOTCH, 7);
        assertSame(first, LabelWizard.current(staff, first.token));
        LabelWizard second = LabelWizard.open(staff, NOTCH, 7);
        assertNull(LabelWizard.current(staff, first.token));
        first.close();
        assertSame(second, LabelWizard.current(staff, second.token));
        LabelWizard.left(staff);
        assertNull(LabelWizard.current(staff, second.token));
    }

    @Test
    void everyCheatHasADisplayName() {
        assertEquals(LabelRequest.CHEATS, List.copyOf(LabelWizard.NAMES.keySet()));
        assertEquals("Auto Stun Slam", LabelWizard.NAMES.get("autostunslam"));
    }
}
