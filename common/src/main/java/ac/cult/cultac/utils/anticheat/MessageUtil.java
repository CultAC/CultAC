package ac.cult.cultac.utils.anticheat;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundDisconnect;
import ac.grim.grimac.api.GrimUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@UtilityClass
public class MessageUtil {
    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)" + '§' + "[0-9A-FK-ORX]");
    private final Pattern HEX_PATTERN = Pattern.compile("([&§]#[A-Fa-f0-9]{6})|([&§]x([&§][A-Fa-f0-9]){6})");
    private final char PLACEHOLDER_ESCAPE_CHAR = '\uFFFF'; // this specific character holds no significance
    /** The characters a template gives meaning to, and the noncharacters {@link #plainText} writes in their place. */
    private final String TEXT_CHARS = "<>\\'\":&%" + PLACEHOLDER_ESCAPE_CHAR;

    private final String TEXT_STAND_INS = "\uFDD0\uFDD1\uFDD2\uFDD3\uFDD4\uFDD5\uFDD6\uFDD7\uFDD8";
    /** The click tag with the characters {@link #plainText} stood in for put back in its value. */
    private final TagResolver WRITTEN_CLICK = TagResolver.resolver("click", MessageUtil::writtenClick);

    /** Preserve Cult's existing disconnect conversion: plain text or an argument-free key. */
    public ClientboundDisconnect disconnectPacket(Component reason) {
        if (reason instanceof TranslatableComponent translated) {
            return ClientboundDisconnect.translatable(translated.key());
        }
        return ClientboundDisconnect.literal(
                stripColor(LegacyComponentSerializer.legacySection().serialize(reason)));
    }

    @Contract("_, null -> null; _, !null -> !null")
    public @Nullable String replacePlaceholders(@Nullable CultPlayer player, @Nullable String string) {
        return replacePlaceholders(player, player == null ? null : player.platformPlayer, string, false);
    }

    /** As {@link #replacePlaceholders(CultPlayer, String)} for a {@link #miniMessage} template: client text stays text. */
    @Contract("_, null -> null; _, !null -> !null")
    public @Nullable String replacePlaceholdersForMiniMessage(@Nullable CultPlayer player, @Nullable String string) {
        return replacePlaceholders(player, player == null ? null : player.platformPlayer, string, true);
    }

    @Contract("_, null -> null; _, !null -> !null")
    public @Nullable String replacePlaceholders(@Nullable Sender sender, @Nullable String string) {
        return replacePlaceholders(sender != null ? sender.getPlatformPlayer() : null, string);
    }

    @Contract("_, null -> null; _, !null -> !null")
    public @Nullable String replacePlaceholders(@Nullable PlatformPlayer player, @Nullable String string) {
        return replacePlaceholders(
                player == null ? null : CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player.getUniqueId()),
                player,
                string,
                false);
    }

    private static final Pattern UNIFIED_PLACEHOLDER_PATTERN = Pattern.compile("%([a-zA-Z0-9_]+)%");

    /** The variables whose values the client chooses. */
    private static final Set<String> CLIENT_TEXT_VARIABLES = Set.of("%player%", "%brand%");

    @Contract("_, _, null -> null; _, _, !null -> !null")
    private @Nullable String replacePlaceholders(
            @Nullable CultPlayer cultPlayer,
            @Nullable PlatformPlayer platformPlayer,
            @Nullable String string,
            boolean clientTextAsText) {
        if (string == null) return null;

        // Without a '%' there is nothing to replace, so skip all allocation.
        if (string.indexOf('%') == -1) {
            return string;
        }

        if (cultPlayer != null && cultPlayer.isBedrockMovement()) {
            string = string.replace("%h_sensitivity%%", "%h_sensitivity%")
                    .replace("%v_sensitivity%%", "%v_sensitivity%");
        }
        final Matcher matcher = UNIFIED_PLACEHOLDER_PATTERN.matcher(string);

        // If '%' exists but doesn't form a valid simple placeholder, skip to the PAPI/Legacy handler.
        // This avoids allocating the StringBuilder below.
        if (!matcher.find()) {
            return CultAPI.INSTANCE.getMessagePlaceHolderManager().replacePlaceholders(platformPlayer, string);
        }

        final Map<String, String> staticReplacements =
                CultAPI.INSTANCE.getExternalAPI().getStaticReplacements();
        final Map<String, Function<GrimUser, String>> variableReplacements =
                CultAPI.INSTANCE.getExternalAPI().getVariableReplacements();
        // 32 is a heuristic buffer. It roughly covers the expansion cost of one UUID (36 chars) vs one placeholder (6
        // chars).
        final StringBuilder sb = new StringBuilder(string.length() + 32);

        // The first matcher.find() already ran above.
        do {
            // The full placeholder, e.g., "%tps%" or "%prefix%"
            final String keyWithPercent = matcher.group(0);
            String value = null;

            String staticValue = staticReplacements.get(keyWithPercent);

            if (staticValue != null) {
                value = staticValue;
            } else if (cultPlayer != null) {
                final Function<GrimUser, String> func = variableReplacements.get(keyWithPercent);
                if (func != null) {
                    value = func.apply(cultPlayer);
                    if (clientTextAsText && value != null && CLIENT_TEXT_VARIABLES.contains(keyWithPercent))
                        value = plainText(value);
                }
            }

            // An unknown placeholder stays as literal text.
            if (value == null) {
                value = keyWithPercent;
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));

        } while (matcher.find());

        matcher.appendTail(sb);

        String cultReplaced = sb.toString();

        return CultAPI.INSTANCE
                .getMessagePlaceHolderManager()
                .replacePlaceholders(platformPlayer, cultReplaced)
                .replace(PLACEHOLDER_ESCAPE_CHAR, '%');
    }

    public @NotNull Component replacePlaceholders(@NotNull CultPlayer player, @NotNull Component component) {
        // Replacement config that forces any placeholder replacement to be pure text
        final TextReplacementConfig safeReplacement = TextReplacementConfig.builder()
                .match("%[a-zA-Z0-9_]+%") // Match placeholders
                .replacement(placeholder -> Component.text(replacePlaceholders(player, placeholder.content())))
                .build();
        return component.replaceText(safeReplacement);
    }

    public @NotNull Component miniMessage(@NotNull String string) {
        return miniMessage(string, TagResolver.empty());
    }

    public @NotNull Component miniMessage(@NotNull String string, TagResolver... resolvers) {
        string = string.replace(
                "%prefix%", CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse("prefix", "&bCult &8»"));

        // hex codes
        Matcher matcher = HEX_PATTERN.matcher(string);
        StringBuilder sb = new StringBuilder(string.length());

        while (matcher.find()) {
            matcher.appendReplacement(sb, "<#" + matcher.group(0).replaceAll("[&§#x]", "") + ">");
        }

        string = matcher.appendTail(sb).toString();

        // MiniMessage doesn't like legacy formatting codes
        string = translateAlternateColorCodes('&', string)
                .replace("§0", "<!b><!i><!u><!st><!obf><black>")
                .replace("§1", "<!b><!i><!u><!st><!obf><dark_blue>")
                .replace("§2", "<!b><!i><!u><!st><!obf><dark_green>")
                .replace("§3", "<!b><!i><!u><!st><!obf><dark_aqua>")
                .replace("§4", "<!b><!i><!u><!st><!obf><dark_red>")
                .replace("§5", "<!b><!i><!u><!st><!obf><dark_purple>")
                .replace("§6", "<!b><!i><!u><!st><!obf><gold>")
                .replace("§7", "<!b><!i><!u><!st><!obf><gray>")
                .replace("§8", "<!b><!i><!u><!st><!obf><dark_gray>")
                .replace("§9", "<!b><!i><!u><!st><!obf><blue>")
                .replace("§a", "<!b><!i><!u><!st><!obf><green>")
                .replace("§b", "<!b><!i><!u><!st><!obf><aqua>")
                .replace("§c", "<!b><!i><!u><!st><!obf><red>")
                .replace("§d", "<!b><!i><!u><!st><!obf><light_purple>")
                .replace("§e", "<!b><!i><!u><!st><!obf><yellow>")
                .replace("§f", "<!b><!i><!u><!st><!obf><white>")
                .replace("§r", "<reset>")
                .replace("§k", "<obfuscated>")
                .replace("§l", "<bold>")
                .replace("§m", "<strikethrough>")
                .replace("§n", "<underlined>")
                .replace("§o", "<italic>");

        if (!hasStandIn(string))
            return MiniMessage.miniMessage().deserialize(string, resolvers).compact();
        return asWritten(MiniMessage.miniMessage()
                .deserialize(string, TagResolver.resolver(TagResolver.resolver(resolvers), WRITTEN_CLICK))
                .compact());
    }

    /**
     * A click with {@code arguments}, its value as written, for the actions every supported Adventure builds from text;
     * null leaves any other action to the standard click tag.
     */
    private @Nullable Tag writtenClick(ArgumentQueue arguments, Context context) {
        if (!arguments.hasNext()) return null;
        String action = arguments.pop().lowerValue();
        if (!arguments.hasNext()) {
            arguments.reset();
            return null;
        }
        String value = asWritten(arguments.pop().value());
        ClickEvent<?> click = switch (action) {
            case "open_url" -> ClickEvent.openUrl(value);
            case "run_command" -> ClickEvent.runCommand(value);
            case "suggest_command" -> ClickEvent.suggestCommand(value);
            case "copy_to_clipboard" -> ClickEvent.copyToClipboard(value);
            default -> null;
        };
        if (click == null) {
            arguments.reset();
            return null;
        }
        return Tag.styling(click);
    }

    private boolean hasStandIn(String string) {
        for (int i = 0; i < string.length(); i++) {
            if (TEXT_STAND_INS.indexOf(string.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private String asWritten(String string) {
        if (!hasStandIn(string)) return string;
        char[] chars = string.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int standIn = TEXT_STAND_INS.indexOf(chars[i]);
            if (standIn >= 0) chars[i] = TEXT_CHARS.charAt(standIn);
        }
        return new String(chars);
    }

    /**
     * {@code component} with the characters {@link #plainText} stood in for put back in its text, show_text hovers and
     * insertions; {@link #writtenClick} puts them back in clicks.
     */
    private Component asWritten(Component component) {
        Component written =
                component instanceof TextComponent text ? text.content(asWritten(text.content())) : component;
        HoverEvent<?> hover = written.hoverEvent();
        if (hover != null && hover.value() instanceof Component shown)
            written = written.hoverEvent(HoverEvent.showText(asWritten(shown)));
        String insertion = written.insertion();
        if (insertion != null) written = written.insertion(asWritten(insertion));
        List<Component> children = written.children();
        if (children.isEmpty()) return written;
        List<Component> writtenChildren = new ArrayList<>(children.size());
        for (Component child : children) writtenChildren.add(asWritten(child));
        return written.children(writtenChildren);
    }

    @Contract("null -> !null; !null -> !null")
    public @NotNull String miniMessageSafe(@Nullable String string) {
        return string == null ? "" : MiniMessage.miniMessage().escapeTags(string);
    }

    /**
     * {@code string} for a {@link #miniMessage} template, shown as written wherever the template puts it: no tag, quote,
     * colour code or placeholder in it takes effect, alone or with the text around it. Section signs are dropped, since
     * the client formats them even in plain text, and so are the stand-in noncharacters.
     */
    @Contract("null -> !null; !null -> !null")
    public @NotNull String plainText(@Nullable String string) {
        if (string == null) return "";
        StringBuilder text = new StringBuilder(string.length());
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            if (c == '§' || TEXT_STAND_INS.indexOf(c) >= 0) continue;
            int special = TEXT_CHARS.indexOf(c);
            text.append(special >= 0 ? TEXT_STAND_INS.charAt(special) : c);
        }
        return text.toString();
    }

    /**
     * {@code component} as MiniMessage for another server's {@link #miniMessage}, which shows it as here: '&' and '%'
     * have no meaning in MiniMessage, so they travel as their stand-ins.
     */
    public @NotNull String relayed(@NotNull Component component) {
        return MiniMessage.miniMessage()
                .serialize(component)
                .replace('&', TEXT_STAND_INS.charAt(TEXT_CHARS.indexOf('&')))
                .replace('%', TEXT_STAND_INS.charAt(TEXT_CHARS.indexOf('%')));
    }

    public Component getParsedComponent(Sender sender, String key, String fallbackText) {
        String message = CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallbackText);
        message = MessageUtil.replacePlaceholders(sender, message);
        return MessageUtil.miniMessage(message);
    }

    /** As {@link #getParsedComponent(Sender, String, String)}, each {@code %name%} of {@code values} shown as text. */
    public Component getParsedComponent(
            @Nullable Sender sender, String key, String fallbackText, Map<String, String> values) {
        String message = CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallbackText);
        // Before the shared placeholders, which would take a name such as %player% for the sender.
        for (Map.Entry<String, String> value : values.entrySet())
            message = message.replace("%" + value.getKey() + "%", plainText(value.getValue()));
        return MessageUtil.miniMessage(MessageUtil.replacePlaceholders(sender, message));
    }

    @Contract("_, _ -> new")
    public static @NotNull String translateAlternateColorCodes(char altColorChar, @NotNull String textToTranslate) {
        char[] b = textToTranslate.toCharArray();

        for (int i = 0; i < b.length - 1; ++i) {
            if (b[i] == altColorChar && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(b[i + 1]) > -1) {
                b[i] = 167;
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }

        return new String(b);
    }

    @Contract("!null -> !null; null -> null")
    public static @Nullable String stripColor(@Nullable String input) {
        return input == null ? null : STRIP_COLOR_PATTERN.matcher(input).replaceAll("");
    }
}
