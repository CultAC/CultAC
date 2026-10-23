package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.command.commands.LabelWizard.Verdict;
import ac.cult.cultac.platform.api.sender.Sender;
import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class LabelConfirmTest {
    private static final UUID MOD = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final CloudArguments.Target NOTCH =
            new CloudArguments.Target(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch");
    private static final long NOW = 400 * LabelWizard.DAY_MS;
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    private final List<Component> sent = new ArrayList<>();
    private final List<JsonObject> posted = new ArrayList<>();
    private final List<CompletableFuture<LabelClient.Reply>> replies = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Test
    void confirmClosesAndOneLineSaysWhatHappened() {
        confirm(true, false);

        assertTrue(closed.get());
        assertTrue(sent.isEmpty());
        assertEquals(2, posted.size());

        replies.get(0).complete(new LabelClient.Reply(201, "{\"id\":\"" + ID + "\"}"));
        assertTrue(sent.isEmpty());
        replies.get(1).complete(new LabelClient.Reply(201, "{\"id\":\"" + ID + "\"}"));

        assertEquals(List.of("Labelled Notch cheating (KillAura, Crystal Aura), high (last 1 day)"), plain());
        assertEquals(NamedTextColor.GREEN, sent.get(0).color());
        assertNull(sent.get(0).clickEvent());
    }

    @Test
    void aRefusedCheckJoinsTheSameLine() {
        confirm(true, false);
        replies.get(0).complete(new LabelClient.Reply(201, "{\"id\":\"" + ID + "\"}"));
        replies.get(1).complete(new LabelClient.Reply(409, "{\"detail\":\"overlaps an active clean label\"}"));

        assertEquals(
                List.of("Labelled Notch cheating (KillAura), high (last 1 day); "
                        + "Crystal Aura refused: HTTP 409: overlaps an active clean label"),
                plain());
        assertEquals(NamedTextColor.GOLD, sent.get(0).color());
    }

    @Test
    void nothingSavedSaysTheLabelFailedAndWhy() {
        confirm(true, false);
        replies.forEach(reply -> reply.complete(new LabelClient.Reply(403, "{\"detail\":\"suspended\"}")));
        assertEquals(
                List.of("Label failed for Notch: The cloud refused this server's key (HTTP 403: suspended)."), plain());
        assertEquals(NamedTextColor.RED, sent.get(0).color());
    }

    @Test
    void nothingSavedForDifferentReasonsNamesEachCheckOnOneLine() {
        confirm(true, false);
        replies.get(0).complete(new LabelClient.Reply(409, "{\"detail\":\"overlap\"}"));
        replies.get(1).completeExceptionally(new IllegalStateException("gone"));
        assertEquals(
                List.of("Label failed for Notch: KillAura refused: HTTP 409: overlap; "
                        + "Crystal Aura refused: The cloud did not answer: IllegalStateException."),
                plain());
    }

    @Test
    void aStaffMemberWhoLeftIsToldNothing() {
        confirm(false, false);
        replies.forEach(reply -> reply.complete(new LabelClient.Reply(201, "{}")));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aSenderThatCannotBeToldThrowsNothing() {
        confirm(true, true);
        replies.get(0).completeExceptionally(new IllegalStateException("gone"));
        replies.get(1).complete(new LabelClient.Reply(201, "{}"));
        assertTrue(sent.isEmpty());
    }

    private void confirm(boolean online, boolean refuseLater) {
        LabelWizard wizard = new LabelWizard(MOD, NOTCH, 7);
        wizard.choose(Verdict.CHEATING);
        wizard.toggle("killaura");
        wizard.toggle("crystalaura");
        wizard.window(LabelWizard.HIGH, 1f, NOW);
        List<LabelRequest.Window> windows = wizard.labels(null);
        LabelConfirm.confirm(
                wizard,
                Verdict.CHEATING,
                windows,
                sender(refuseLater),
                MOD,
                body -> {
                    posted.add(body);
                    CompletableFuture<LabelClient.Reply> reply = new CompletableFuture<>();
                    replies.add(reply);
                    return reply;
                },
                () -> closed.set(true),
                Runnable::run,
                () -> online);
    }

    private List<String> plain() {
        return sent.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }

    /** A staff member; with {@code refuseLater} every message throws, as for one who left. */
    private Sender sender(boolean refuseLater) {
        return (Sender) Proxy.newProxyInstance(
                Sender.class.getClassLoader(), new Class<?>[] {Sender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args[0] instanceof Component text) {
                        if (refuseLater) throw new IllegalStateException("left");
                        sent.add(text);
                        return null;
                    }
                    if (method.getName().equals("getName")) return "Mod";
                    if (method.getName().equals("isPlayer")) return true;
                    return method.getReturnType() == boolean.class ? true : null;
                });
    }
}
