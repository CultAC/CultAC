package ac.cult.cultac.manager;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckNames;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.command.CloudCommandService;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.List;
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
        String latest = check.getLatestName();
        String shown = check.getDisplayName();
        if (latest == null || shown == null || shown.isBlank()) return alert;
        String name = query(check, latest);
        Key key = new Key(name, shown, CloudCommandService.command("info check " + name));
        return LINKS.computeIfAbsent(key, CheckNameLink::replacement)
                .map(replacement -> linkText(alert, replacement))
                .orElse(alert);
    }

    // Two checks an older name version names alike still each link to their own explanation.
    private static String query(Check check, String latest) {
        String name = check.getCheckName();
        if (name == null) return latest;
        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        return CheckCatalog.query(name, latest, CheckNames.version(config, CheckNames.CONFIG_KEY));
    }

    private static Component linkText(Component component, TextReplacementConfig replacement) {
        if (component.clickEvent() != null) return component;
        Component linked = component.children(List.of()).replaceText(replacement);
        for (Component child : component.children()) linked = linked.append(linkText(child, replacement));
        return linked;
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
