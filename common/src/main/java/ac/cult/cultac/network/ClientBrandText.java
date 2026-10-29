package ac.cult.cultac.network;

import org.jetbrains.annotations.Nullable;

/** A client brand as the client sent it, made safe to store, send and show as plain text. */
public final class ClientBrandText {
    public static final int MAX_LENGTH = 64;

    private ClientBrandText() {}

    /**
     * {@code raw} without control characters or {@code §} colour codes, stripped and cut to {@value #MAX_LENGTH}
     * characters; null when nothing is left.
     */
    public static @Nullable String sanitize(@Nullable String raw) {
        if (raw == null) return null;
        StringBuilder kept = new StringBuilder(Math.min(raw.length(), MAX_LENGTH * 2));
        boolean colour = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.FORMAT) continue;
            if (colour) {
                colour = false;
                continue;
            }
            if (c == '§') {
                colour = true;
                continue;
            }
            kept.append(c);
        }
        String text = kept.toString().strip();
        if (text.length() > MAX_LENGTH) {
            int end = Character.isHighSurrogate(text.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
            text = text.substring(0, end).strip();
        }
        return text.isEmpty() ? null : text;
    }
}
