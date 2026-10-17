package ac.cult.cultac.platform.velocity.bridge.command;

import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandOutput;
import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.platform.velocity.bridge.BridgeFrames;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Fits command traffic into single bridge frames, which backends cap at {@link BridgeProtocol#MAX_STRING_BYTES}. */
final class CommandFrames {
    /** Frame bytes left for the payload after the type byte, the request id and a length prefix. */
    static final int PAYLOAD_BYTES = BridgeProtocol.MAX_STRING_BYTES - 16;

    static final int MAX_SUGGESTIONS = 256;
    /** Six JSON bytes per character at worst keeps each plain-text chunk under {@link #PAYLOAD_BYTES}. */
    static final int PLAIN_CHUNK_CHARS = 4000;

    private CommandFrames() {}

    /** One COMMAND_OUTPUT when the JSON fits; otherwise the plain text, split across several. */
    static List<CommandOutput> outputs(int requestId, Component message) {
        String json = GsonComponentSerializer.gson().serialize(message);
        CommandOutput whole = new CommandOutput(requestId, json);
        if (BridgeFrames.fits(whole)) return List.of(whole);
        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        var outputs = new ArrayList<CommandOutput>();
        int start = 0;
        while (start < plain.length()) {
            int end = Math.min(plain.length(), start + PLAIN_CHUNK_CHARS);
            if (end < plain.length() && Character.isHighSurrogate(plain.charAt(end - 1))) end--;
            outputs.add(new CommandOutput(
                    requestId, GsonComponentSerializer.gson().serialize(Component.text(plain.substring(start, end)))));
            start = end;
        }
        return outputs;
    }

    /** At most {@link #MAX_SUGGESTIONS} entries, cut short where the frame would overflow. */
    static List<String> suggestions(List<String> suggestions) {
        var kept = new ArrayList<String>(Math.min(suggestions.size(), MAX_SUGGESTIONS));
        int budget = PAYLOAD_BYTES;
        for (String suggestion : suggestions) {
            if (kept.size() == MAX_SUGGESTIONS) break;
            int cost = BridgeCodec.maxEncodedLength(suggestion);
            if (cost > budget) break;
            budget -= cost;
            kept.add(suggestion);
        }
        return kept;
    }
}
