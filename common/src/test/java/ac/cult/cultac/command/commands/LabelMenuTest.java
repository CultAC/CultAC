package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.labels.LabelCatalog;
import ac.cult.cultac.cloud.labels.LabelClients;
import ac.cult.cultac.dialog.Dialog;
import ac.cult.cultac.dialog.DialogViewer;
import ac.cult.cultac.dialog.Response;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.network.RoutingHolder;
import ac.cult.cultac.platform.api.sender.Sender;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LabelMenuTest {
    private static final CloudArguments.Target NOTCH =
            new CloudArguments.Target(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch");
    private static final Response NOTHING = new Response(Map.of());

    private final Screen screen = new Screen(UUID.randomUUID());
    private final NetworkRouting nobodyElsewhere = new NetworkRouting() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String instanceId() {
            return "test";
        }

        @Override
        public Optional<NetworkPlayer> locate(String nameOrUuid) {
            return Optional.empty();
        }

        @Override
        public Collection<NetworkPlayer> roster() {
            return List.of();
        }

        @Override
        public CompletableFuture<Boolean> run(Sender sender, String line, NetworkPlayer where) {
            return CompletableFuture.completedFuture(false);
        }
    };

    @BeforeEach
    void routeNowhere() {
        RoutingHolder.install(nobodyElsewhere);
    }

    @AfterEach
    void restoreRouting() {
        RoutingHolder.uninstall(nobodyElsewhere);
    }

    @Test
    void theVerdictPageOffersCleanOnlyWithItsPermission() {
        open(false);
        assertEquals(List.of("Cheating", "Suspected"), labels(screen.page()));
        open(true);
        assertEquals(List.of("Cheating", "Suspected", "Clean"), labels(screen.page()));
        assertEquals("Cancel", plain(screen.page().exit().label()));
    }

    @Test
    void cancelClosesTheMenu() {
        open(false);
        exit();
        assertTrue(screen.closed);
    }

    @Test
    void theChecksAreTheCatalogsWithNextLast() {
        open(false);
        click("Cheating");
        List<String> labels = labels(screen.page());
        assertEquals(LabelCatalog.current().cheats().size() + 1, labels.size());
        assertEquals("☐ " + LabelCatalog.current().cheats().get(0).name(), labels.get(0));
        assertEquals(plain(LabelLines.next()), labels.get(labels.size() - 1));
    }

    @Test
    void nextWithNothingPickedStaysAndSaysWhy() {
        open(false);
        click("Cheating");
        click(next());
        assertTrue(body(screen.page()).contains(LabelLines.PICK_ONE));
    }

    @Test
    void backFromTheChecksShowsTheVerdicts() {
        open(false);
        click("Cheating");
        exit();
        assertEquals(List.of("Cheating", "Suspected"), labels(screen.page()));
    }

    @Test
    void backFromTheSpanKeepsTheTicks() {
        open(false);
        click("Cheating");
        click("☐ " + first());
        click(next());
        assertEquals(List.of("confidence", "span"), keys(screen.page()));
        exit();
        assertTrue(labels(screen.page()).contains("☑ " + first()));
    }

    @Test
    void backFromTheReviewKeepsTheConfidenceAndSpan() {
        open(false);
        click("Cheating");
        click("☐ " + first());
        click(next());
        click(next(), new Response(Map.of("confidence", "extreme", "span", 3f)));
        assertEquals(List.of("note"), keys(screen.page()));
        exit();
        assertEquals(3, ((Dialog.Slider) input("span")).initial());
        assertTrue(((Dialog.Choice) input("confidence")).options().get(1).initial());
    }

    @Test
    void cleanSkipsTheChecksAndTakesNoSpan() {
        open(true);
        click("Clean");
        assertEquals(List.of("confidence"), keys(screen.page()));
        exit();
        assertEquals(List.of("Cheating", "Suspected", "Clean"), labels(screen.page()));
    }

    @Test
    void backKeepsWhatWasSetOnThePageItLeaves() {
        open(false);
        click("Cheating");
        click("☐ " + first());
        click(next());
        exit(new Response(Map.of("confidence", "extreme", "span", 3f)));
        click(next());
        assertEquals(3, ((Dialog.Slider) input("span")).initial());
        assertTrue(((Dialog.Choice) input("confidence")).options().get(1).initial());
        click(next(), new Response(Map.of("confidence", "extreme", "span", 3f)));
        exit(new Response(Map.of("note", "seen twice")));
        click(next(), new Response(Map.of("confidence", "extreme", "span", 3f)));
        assertEquals("seen twice", ((Dialog.TextBox) input("note")).initial());
    }

    @Test
    void theReviewOpensWithoutWaitingForTheCloudAndSumsUpTheLabel() {
        open(false);
        click("Suspected");
        click("☐ " + first());
        click(next());
        assertEquals(List.of("span"), keys(screen.page()));
        click(next(), new Response(Map.of("span", 3f)));
        assertEquals("Review Notch", plain(screen.page().title()));
        String body = body(screen.page());
        assertTrue(body.contains("Suspected"), body);
        assertTrue(body.contains("last 3 days"), body);
        assertTrue(body.contains(first()), body);
        assertTrue(body.contains("To undo it: "), body);
        assertEquals(List.of("note"), keys(screen.page()));
        assertEquals(List.of("Confirm"), labels(screen.page()));
    }

    @Test
    void confirmClosesTheMenu() {
        open(false);
        click("Cheating");
        click("☐ " + first());
        click(next());
        click(next(), new Response(Map.of("confidence", "high", "span", 7f)));
        assertFalse(screen.closed);
        click("Confirm", new Response(Map.of("note", "seen twice")));
        assertTrue(screen.closed);
    }

    private static String first() {
        return LabelCatalog.current().cheats().get(0).name();
    }

    private static String next() {
        return plain(LabelLines.next());
    }

    private Dialog.Input input(String key) {
        return screen.page().inputs().stream()
                .filter(input -> input.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " not in " + keys(screen.page())));
    }

    private void open(boolean mayLabelClean) {
        LabelMenu.open(screen, sender(mayLabelClean), screen.id(), LabelClients.silent(), NOTCH);
    }

    private void click(String label) {
        click(label, NOTHING);
    }

    private void click(String label, Response response) {
        Dialog page = screen.page();
        Dialog.Button button = page.buttons().stream()
                .filter(candidate -> plain(candidate.label()).equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError(label + " not in " + labels(page)));
        int shown = screen.pages.size();
        button.onClick().accept(response);
        assertTrue(screen.pages.size() > shown || screen.closed, label + " showed nothing");
    }

    private void exit() {
        exit(NOTHING);
    }

    private void exit(Response response) {
        Dialog.Button exit = screen.page().exit();
        assertNotNull(exit);
        exit.onClick().accept(response);
    }

    private static List<String> labels(Dialog page) {
        return page.buttons().stream().map(button -> plain(button.label())).toList();
    }

    private static List<String> keys(Dialog page) {
        return page.inputs().stream().map(Dialog.Input::key).toList();
    }

    private static String body(Dialog page) {
        StringBuilder text = new StringBuilder();
        for (Dialog.Body part : page.body()) {
            if (part instanceof Dialog.Text block)
                text.append(plain(block.text())).append('\n');
        }
        return text.toString();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Sender sender(boolean mayLabelClean) {
        return (Sender) Proxy.newProxyInstance(
                Sender.class.getClassLoader(),
                new Class<?>[] {Sender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> mayLabelClean || !CloudArguments.LABEL_CLEAN.equals(args[0]);
                    case "sendMessage" -> throw new AssertionError(plain((Component) args[0]));
                    case "getName" -> "Mod";
                    case "isPlayer" -> true;
                    default -> null;
                });
    }

    /** The staff member's screen: every page shown, and whether it was closed. */
    private static final class Screen implements DialogViewer {
        private final UUID id;
        private final List<Dialog> pages = new ArrayList<>();
        private boolean closed;

        private Screen(UUID id) {
            this.id = id;
        }

        Dialog page() {
            return pages.get(pages.size() - 1);
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public void show(Dialog dialog) {
            pages.add(dialog);
            closed = false;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public boolean online() {
            return true;
        }
    }
}
