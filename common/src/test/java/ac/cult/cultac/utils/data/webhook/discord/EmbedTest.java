package ac.cult.cultac.utils.data.webhook.discord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

class EmbedTest {
    @Test
    void addedFieldsAppendInOrder() {
        EmbedField verbose = new EmbedField("Verbose", "action=attack");
        EmbedField state = new EmbedField("State", "ping=50");
        EmbedField extra = new EmbedField("Extra", "a");
        EmbedField last = new EmbedField("Last", "b");

        Embed embed =
                new Embed("description").addFields(verbose).addFields(state).addFields(extra, last);

        assertArrayEquals(new EmbedField[] {verbose, state, extra, last}, embed.fields());
        JsonArray fields = embed.toJson().getAsJsonArray("fields");
        assertEquals(4, fields.size());
        assertEquals("Verbose", fields.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("State", fields.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("Last", fields.get(3).getAsJsonObject().get("name").getAsString());
    }
}
