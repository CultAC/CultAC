package ac.cult.cultac.manager;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.command.CloudCommandService;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;

/** Makes a check's name in an alert run {@code /cult info check} for it when clicked. */
final class CheckNameLink {
    private record Key(String name, String shown, String command) {}

    private static final Map<Key, Optional<TextReplacementConfig>> LINKS = new ConcurrentHashMap<>();

    private CheckNameLink() {}

    /** {@code alert} with the check's shown name, wherever it stands as a word, linked to its explanation. */
    static Component link(Component alert, Check check) {
        String name = check.getCheckName();
        String shown = check.getDisplayName();
        if (name == null || shown == null || shown.isBlank()) return alert;
        Key key = new Key(name, shown, CloudCommandService.command("info check " + name));
        return LINKS.computeIfAbsent(key, CheckNameLink::replacement)
                .map(alert::replaceText)
                .orElse(alert);
    }

    private static Optional<TextReplacementConfig> replacement(Key key) {
        if (CheckCatalog.find(key.name()) == null) return Optional.empty();
        ClickEvent click = ClickEvent.runCommand(key.command());
        return Optional.of(TextReplacementConfig.builder()
                .match(Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(key.shown()) + "(?![\\p{L}\\p{N}_])"))
                .replacement(text -> text.clickEvent(click))
                .build());
    }
}
