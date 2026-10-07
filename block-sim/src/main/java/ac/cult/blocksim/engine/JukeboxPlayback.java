package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.nbt.NbtValue;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;

/** JukeboxSongPlayer.tick's client-visible timer; sounds/events have no block writes. */
public final class JukeboxPlayback {
    private JukeboxPlayback() { }
    public static BlockEntityData tick(BlockEntityData entity) {
        var fields = entity.data(); var playing = fields.get("song_playing");
        if (!entity.type().equals("minecraft:jukebox") || playing == null || !playing.getAsBoolean()) return entity;
        long elapsed = fields.get("ticks_since_song_started").getAsLong();
        boolean finished = elapsed >= fields.get("song_end_tick").getAsLong();
        var saved = new HashMap<>(entity.savedData().values());
        if (finished) saved.remove("ticks_since_song_started");
        else saved.put("ticks_since_song_started", new NbtValue.Numeric(NbtValue.Kind.LONG, elapsed + 1));
        fields = fields.with("song_playing", new JsonPrimitive(!finished))
            .with("ticks_since_song_started", finished ? null : new JsonPrimitive(elapsed + 1));
        return new BlockEntityData(entity.type(), fields, new NbtValue.Compound(saved));
    }
}
