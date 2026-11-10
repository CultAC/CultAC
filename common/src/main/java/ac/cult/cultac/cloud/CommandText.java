package ac.cult.cultac.cloud;

import java.util.regex.Pattern;

/** Cloud text that ends up in a console command or a chat message, kept to plain words. */
public final class CommandText {
    /** {@code <}, a section sign, and {@code &} before a legacy format code at the start or after whitespace. */
    private static final Pattern FORMAT = Pattern.compile("<|§|(?<!\\S)&(?=[0-9A-Fa-fK-Ok-oRrXx#])");
    private static final Pattern FLAG_WORD = Pattern.compile("(^|\\s)-(?=[A-Za-z-])");

    private CommandText() {}

    /** {@code text} without {@code <}, section signs and {@code &} format codes, also those its removals join. */
    public static String plain(String text) {
        String out = text;
        String before;
        do {
            before = out;
            out = FORMAT.matcher(out).replaceAll("");
        } while (!out.equals(before));
        return out;
    }

    /** {@code text} with a {@code '} before each word that starts with {@code -} and a letter or {@code -}. */
    public static String unflagged(String text) {
        return FLAG_WORD.matcher(text).replaceAll("$1'-");
    }

    /** {@code text} {@linkplain #plain plain} and {@linkplain #unflagged unflagged}. */
    public static String shown(String text) {
        return unflagged(plain(text));
    }
}
