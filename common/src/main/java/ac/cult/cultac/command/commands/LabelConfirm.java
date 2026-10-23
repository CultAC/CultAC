package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.command.commands.LabelWizard.Verdict;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

/**
 * Confirm in the label menu: the menu closes, every window posts off the staff member's thread, and one chat line says
 * what happened once every post answered. Nothing here waits for the cloud, and a staff member who is gone by then is
 * told nothing.
 */
final class LabelConfirm {
    private LabelConfirm() {}

    record Outcome(String cheat, @Nullable String refusal) {}

    /**
     * Confirms {@code windows} for {@code wizard}'s target: {@code close} closes the menu, {@code post} sends one label,
     * {@code staffThread} runs the outcome on the staff member's thread, where {@code online} says whether they are
     * still here.
     */
    static void confirm(
            LabelWizard wizard,
            Verdict verdict,
            List<LabelRequest.Window> windows,
            Sender sender,
            UUID labellerId,
            Function<JsonObject, CompletableFuture<LabelClient.Reply>> post,
            Runnable close,
            Executor staffThread,
            BooleanSupplier online) {
        close.run();
        List<CompletableFuture<Outcome>> posts = new ArrayList<>();
        for (LabelRequest.Window window : windows) {
            CompletableFuture<LabelClient.Reply> reply;
            try {
                reply = post.apply(window.body(wizard.target.id(), sender.getName(), labellerId));
            } catch (RuntimeException failed) {
                reply = CompletableFuture.failedFuture(failed);
            }
            posts.add(reply.handle((answer, error) -> error != null
                    ? new Outcome(window.cheat(), "The cloud did not answer: " + LabelClient.unanswered(error) + ".")
                    : new Outcome(window.cheat(), answer.ok() ? null : LabelClient.failure(answer))));
        }
        CompletableFuture.allOf(posts.toArray(CompletableFuture[]::new))
                .whenComplete((done, error) -> staffThread.execute(() -> {
                    if (!online.getAsBoolean()) {
                        LogUtil.debug("Label outcome for " + sender.getName() + " dropped: they left");
                        return;
                    }
                    CultLabel.tell(
                            sender,
                            line(
                                    wizard,
                                    verdict,
                                    posts.stream().map(CompletableFuture::join).toList()));
                }));
    }

    /**
     * The outcome as one line, coloured by how many saved: what was labelled, then any check the cloud refused and why.
     * When nothing saved and every check was refused for one reason, that reason is the whole answer.
     */
    static Component line(LabelWizard wizard, Verdict verdict, List<Outcome> outcomes) {
        List<String> saved = outcomes.stream()
                .filter(outcome -> outcome.refusal() == null)
                .map(Outcome::cheat)
                .toList();
        List<Outcome> refused =
                outcomes.stream().filter(outcome -> outcome.refusal() != null).toList();
        NamedTextColor color = LabelLines.resultColor(saved.size(), outcomes.size());
        String player = wizard.target.name();
        if (saved.isEmpty()) {
            List<String> reasons =
                    refused.stream().map(Outcome::refusal).distinct().toList();
            String why = reasons.size() == 1
                    ? reasons.get(0)
                    : String.join(
                            "; ", refused.stream().map(LabelConfirm::refusal).toList());
            return Component.text(LabelLines.failed(player, why), color);
        }
        String text =
                LabelLines.echo(player, verdict, saved, wizard.asksConfidence(), wizard.confidence(), wizard.days());
        if (!refused.isEmpty()) {
            text += "; "
                    + String.join(
                            "; ", refused.stream().map(LabelConfirm::refusal).toList());
        }
        return Component.text(text, color);
    }

    private static String refusal(Outcome outcome) {
        return LabelLines.name(outcome.cheat()) + " refused: " + outcome.refusal();
    }
}
