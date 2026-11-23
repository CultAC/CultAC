package ac.cult.cultac.utils.common.arguments;

import ac.cult.cultac.platform.api.Platform;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Startup flags, read once when this class initializes from the JVM's {@code -DCult<Key>=<value>} input arguments.
 * Key case is ignored, an empty value leaves the flag unset and a value that does not parse reads as the default.
 */
public final class CommonCultArguments {
    private static final Map<String, String> ARGUMENTS = inputArguments();

    public static final String PASTE_API_URL = string("PasteApiUrl", "https://api.pastes.dev/");
    public static final String PASTE_URL = string("PasteUrl", "https://pastes.dev/");
    public static final @Nullable Platform PLATFORM_OVERRIDE = platform("PlatformOverride");
    /** Clamped to 1000..60000. */
    public static final int URL_TIMEOUT = range("UrlTimeout", 10000, 1000, 60000);

    /**
     * Enables "Fast Bypass" mode for chat messages sent by CultAC.
     * <p>
     * <b>BENEFIT:</b> Messages are sent directly as packets, significantly improving
     * performance and reducing server overhead especially when lots of alerts are being sent.
     * <p>
     * <b>TRADE-OFF:</b> This completely bypasses the platform's event system (e.g., Bukkit's chat events).
     * Other plugins will NOT be able to see, format, or cancel these messages.
     * <p>
     * This setting is opt-out (default: true) and requires a server restart to change.
     */
    public static final boolean USE_CHAT_FAST_BYPASS = bool("ChatFastBypass", true);

    /**
     * If true, players will be kicked when they try to connect from a proxy server with ViaVersion installed.
     * <p>
     * This setting is opt-out (default: true) and requires a server restart to change.
     */
    public static final boolean KICK_ON_VIA_PROXY = bool("KickOnViaProxy", true);

    private CommonCultArguments() {}

    private static @Nullable String value(String key) {
        return ARGUMENTS.get(("Cult" + key).toLowerCase());
    }

    private static String string(String key, String otherwise) {
        String value = value(key);
        return value == null ? otherwise : value;
    }

    private static @Nullable Platform platform(String key) {
        String value = value(key);
        return value == null ? null : Platform.getByName(value);
    }

    private static int range(String key, int otherwise, int min, int max) {
        String value = value(key);
        if (value == null) return otherwise;
        try {
            return Math.min(Math.max(Integer.parseInt(value), min), max);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static boolean bool(String key, boolean otherwise) {
        String value = value(key);
        return value == null ? otherwise : Boolean.parseBoolean(value);
    }

    private static Map<String, String> inputArguments() {
        Map<String, String> arguments = new HashMap<>();
        for (String line : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!line.toLowerCase().startsWith("-dcult")) continue;
            int index = line.indexOf('=');
            if (index > 0 && index < line.length() - 1) {
                arguments.put(line.substring(2, index).toLowerCase(), line.substring(index + 1));
            }
        }
        return arguments;
    }
}
