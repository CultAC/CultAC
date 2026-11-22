package ac.cult.reload.host;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Existing explicit player targets; self commands otherwise use the sender's session. */
public final class CommandRouting {
    private static final Set<String> TARGET =
            Set.of("profile", "log", "consoledebug", "javapacketlog", "geyserpacketlog", "spectate");
    private static final Set<String> GLOBAL = Set.of("list", "brands", "performance", "dump");

    private CommandRouting() {}

    public static String target(String[] args) {
        return args.length > 1 && TARGET.contains(args[0].toLowerCase(Locale.ROOT)) ? args[1] : null;
    }

    /** {@code /cult runtimes <id> <command>}: one command per player of that runtime, naming them by %player%. */
    public static List<String> perPlayer(String[] args, List<String> players) {
        String template = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (template.startsWith("/")) template = template.substring(1);
        String command = template;
        return players.stream().map(name -> command.replace("%player%", name)).toList();
    }

    public static boolean aggregate(String[] args) {
        return args.length > 0 && GLOBAL.contains(args[0].toLowerCase(Locale.ROOT));
    }
}
