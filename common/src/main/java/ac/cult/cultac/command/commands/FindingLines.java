package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.findings.Finding;
import ac.cult.cultac.cloud.findings.FindingsLog;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/** How cloud findings read in chat: one compact line each, the full detail on hover. */
final class FindingLines {
    static final int MAX_DETAIL = 60;

    private FindingLines() {}

    /** A line of {@code /cult findings}: age, check, confidence, detail. */
    static Component line(Finding finding, long now) {
        return Component.text()
                .append(Component.text(ago(now - finding.at()) + " ", NamedTextColor.DARK_GRAY))
                .append(body(finding))
                .build();
    }

    /** A line of {@code /cult top}: highest confidence, then how many findings. */
    static Component ranked(int place, FindingsLog.Ranked ranked) {
        TextComponent.Builder line = Component.text()
                .append(Component.text(place + ". ", NamedTextColor.DARK_GRAY))
                .append(Component.text(
                        ranked.name() == null ? ranked.player().toString() : ranked.name(), NamedTextColor.WHITE))
                .append(Component.text(" " + confidence(ranked.max()), NamedTextColor.YELLOW))
                .append(Component.text(" ×" + ranked.count(), NamedTextColor.DARK_GRAY));
        StringBuilder checks = new StringBuilder();
        for (Map.Entry<String, Integer> check : ranked.checks()) {
            checks.append(' ').append(check.getKey()).append('×').append(check.getValue());
        }
        return line.append(Component.text(checks.toString(), NamedTextColor.GRAY))
                .build();
    }

    private static Component body(Finding finding) {
        String detail = finding.detail();
        String shown = detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL - 1) + "…" : detail;
        String category = finding.category().isEmpty() ? "" : " " + finding.category();
        TextComponent.Builder line = Component.text()
                .append(Component.text(finding.check(), NamedTextColor.WHITE))
                .append(Component.text(" " + confidence(finding.confidence()), NamedTextColor.YELLOW))
                .append(Component.text(category + " @" + finding.tick(), NamedTextColor.DARK_GRAY));
        if (!shown.isEmpty()) line.append(Component.text(" " + shown, NamedTextColor.GRAY));
        if (!detail.isEmpty()) line.hoverEvent(HoverEvent.showText(Component.text(detail, NamedTextColor.GRAY)));
        return line.build();
    }

    static String confidence(double confidence) {
        return String.format(Locale.ROOT, "%.2f", confidence);
    }

    /** {@code 45s}, {@code 12m}, {@code 2h05m}. */
    static String ago(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        return minutes / 60 + "h" + String.format(Locale.ROOT, "%02d", minutes % 60) + "m";
    }
}
