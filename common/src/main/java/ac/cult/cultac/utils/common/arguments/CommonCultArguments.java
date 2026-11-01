package ac.cult.cultac.utils.common.arguments;

import static ac.cult.cultac.utils.common.arguments.ArgumentUtils.*;

import ac.cult.cultac.platform.api.Platform;

public class CommonCultArguments {

    private static final SystemArgumentFactory FACTORY = SystemArgumentFactory.Builder.of("Cult")
            .optionModifier(builder -> builder.key("Cult" + builder.options().getKey()))
            .supportEnv()
            .build();

    public static final SystemArgument<String> API_URL =
            FACTORY.create(string("APIUrl", "https://api.grim.ac/v1/server/"));
    public static final SystemArgument<String> PASTE_API_URL =
            FACTORY.create(string("PasteApiUrl", "https://api.pastes.dev/"));
    public static final SystemArgument<String> PASTE_URL = FACTORY.create(string("PasteUrl", "https://pastes.dev/"));
    public static final SystemArgument<Platform> PLATFORM_OVERRIDE = FACTORY.create(platform("PlatformOverride"));
    public static final SystemArgument<Integer> URL_TIMEOUT = FACTORY.create(range("UrlTimeout", 10000, 1000, 60000));

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
    public static final SystemArgument<Boolean> USE_CHAT_FAST_BYPASS = FACTORY.create(string("ChatFastBypass", true));

    /**
     * If true, players will be kicked when they try to connect from a proxy server with ViaVersion installed.
     * <p>
     * This setting is opt-out (default: true) and requires a server restart to change.
     */
    public static final SystemArgument<Boolean> KICK_ON_VIA_PROXY = FACTORY.create(string("KickOnViaProxy", true));
}
