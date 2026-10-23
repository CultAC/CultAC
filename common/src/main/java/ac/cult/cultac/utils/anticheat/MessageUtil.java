package ac.cult.cultac.utils.anticheat;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundDisconnect;
import ac.grim.grimac.api.GrimUser;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
        return replacePlaceholders(player, player == null ? null : player.platformPlayer, string);
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
                string);
    }

    private static final Pattern UNIFIED_PLACEHOLDER_PATTERN = Pattern.compile("%([a-zA-Z0-9_]+)%");

    @Contract("_, _, null -> null; _, _, !null -> !null")
    private @Nullable String replacePlaceholders(
            @Nullable CultPlayer cultPlayer, @Nullable PlatformPlayer platformPlayer, @Nullable String string) {
        if (string == null) return null;

        // Without a '%' there is nothing to replace, so skip all allocation.
        if (string.indexOf('%') == -1) {
            return string;
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

        return MiniMessage.miniMessage().deserialize(string, resolvers).compact();
    }

    @Contract("null -> !null; !null -> !null")
    public @NotNull String miniMessageSafe(@Nullable String string) {
        return string == null ? "" : MiniMessage.miniMessage().escapeTags(string);
    }

    public Component getParsedComponent(Sender sender, String key, String fallbackText) {
        String message = CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallbackText);
        message = MessageUtil.replacePlaceholders(sender, message);
        return MessageUtil.miniMessage(message);
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
