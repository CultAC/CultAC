package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A chat check that lets through every message and command its {@code <check>.exempt-regex} setting matches. */
abstract class ChatMessageCheck extends Check implements CheckListener {
    private @Nullable Predicate<String> exemptRegex;

    ChatMessageCheck(CultPlayer player) {
        super(player);
    }

    /** Whether the setting's pattern matches all of the chat {@code message}. */
    protected final boolean matchesExemptMessage(String message) {
        return exemptRegex != null && exemptRegex.test(message);
    }

    /** Whether the setting's pattern matches all of "/" followed by {@code command}. */
    protected final boolean matchesExemptCommand(String command) {
        return exemptRegex != null && exemptRegex.test("/" + command);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        String regexString = config.getStringElse(getConfigName() + ".exempt-regex", null);
        exemptRegex = regexString == null ? null : Pattern.compile(regexString).asMatchPredicate();
    }
}
