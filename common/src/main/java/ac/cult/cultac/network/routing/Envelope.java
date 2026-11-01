package ac.cult.cultac.network.routing;

import com.google.gson.JsonObject;
import java.util.UUID;

/**
 * One network message: a {@code type}d {@code body} from instance {@code from} to instance {@code to}, or to every
 * instance when {@code to} is {@value NetworkSettings#ALL}. {@code ts} is the sender's clock in epoch milliseconds.
 */
public record Envelope(int v, String type, UUID id, String from, String to, long ts, JsonObject body) {
    public static final int VERSION = 1;

    static Envelope create(String type, String from, String to, long now, JsonObject body) {
        return new Envelope(VERSION, type, UUID.randomUUID(), from, to, now, body);
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("v", v);
        json.addProperty("type", type);
        json.addProperty("id", id.toString());
        json.addProperty("from", from);
        json.addProperty("to", to);
        json.addProperty("ts", ts);
        json.add("body", body);
        return json;
    }

    /** Whether this message is for every instance. */
    public boolean broadcast() {
        return NetworkSettings.ALL.equals(to);
    }
}
