package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Received PlayerInfo identities and immutable modes, including references cached by remote players. */
public final class ClientPlayerModes {
    public record Entry(Object identity, GameMode mode, String name) {}

    private static final long DISPLAY_HEAP = 65_536;
    private static final Pattern LEGACY_CODE = Pattern.compile("§.?");
    private static final Pattern WORDS = Pattern.compile("\\s+");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}|\\.[A-Za-z0-9_]{1,15}");

    private final Map<UUID, Entry> profiles = new HashMap<>();

    public Entry get(UUID id) {
        return profiles.get(id);
    }

    /** The profiles listed under {@code name}, ignoring case. */
    public List<UUID> named(String name) {
        List<UUID> named = new ArrayList<>(1);
        for (Map.Entry<UUID, Entry> profile : profiles.entrySet()) {
            if (name.equalsIgnoreCase(profile.getValue().name())) named.add(profile.getKey());
        }
        return named;
    }

    public void remove(List<UUID> ids) {
        ids.forEach(profiles::remove);
    }

    public void clear() {
        profiles.clear();
    }

    public void update(ClientboundPlayerInfoUpdate packet, Set<UUID> visibleModes, Iterable<PacketEntity> entities) {
        var actions = packet.actions();
        if (actions.contains(ClientboundPlayerInfoUpdate.Action.ADD_PLAYER))
            for (var entry : packet.entries())
                profiles.putIfAbsent(entry.profileId(), new Entry(new Object(), GameMode.SURVIVAL, name(entry)));
        if (!actions.contains(ClientboundPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) return;
        for (var entry : packet.entries()) {
            var previous = profiles.get(entry.profileId());
            if (previous == null || !visibleModes.contains(entry.profileId())) continue;
            var next = new Entry(previous.identity(), entry.gameMode(), previous.name());
            profiles.put(entry.profileId(), next);
            for (var entity : entities)
                if (entity instanceof PacketEntityPlayer remote
                        && remote.cachedInfo != null
                        && remote.cachedInfo.identity() == previous.identity()) remote.cachedInfo = next;
        }
    }

    /** The profile name an {@code ADD_PLAYER} entry lists; null for an entry without that action. */
    public static @Nullable String name(ClientboundPlayerInfoUpdate.Entry entry) {
        var encoded = entry.encodedActions().get(ClientboundPlayerInfoUpdate.Action.ADD_PLAYER);
        if (encoded == null) return null;
        var buffer = Unpooled.wrappedBuffer(encoded.bytes());
        try {
            return Wire.readString(buffer, 16);
        } finally {
            buffer.release();
        }
    }

    /** The display name {@code entry} sets, as plain text without formatting and legacy codes; null for none. */
    public static @Nullable String displayName(ClientboundPlayerInfoUpdate.Entry entry) {
        var encoded = entry.encodedActions().get(ClientboundPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME);
        if (encoded == null) return null;
        byte[] bytes = encoded.bytes();
        if (bytes.length < 2 || bytes[0] == 0) return null;
        try {
            NbtValue text = BinaryNbt.read(Arrays.copyOfRange(bytes, 1, bytes.length), DISPLAY_HEAP);
            StringBuilder plain = new StringBuilder();
            appendText(text, plain);
            return LEGACY_CODE.matcher(plain).replaceAll("");
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static void appendText(NbtValue text, StringBuilder plain) {
        switch (text) {
            case NbtValue.Text literal -> plain.append(literal.value());
            case NbtValue.Sequence parts -> parts.values().forEach(part -> appendText(part, plain));
            case NbtValue.Compound component -> {
                if (component.values().get("") instanceof NbtValue wrapped) appendText(wrapped, plain);
                if (component.values().get("text") instanceof NbtValue.Text literal) plain.append(literal.value());
                if (component.values().get("extra") instanceof NbtValue extra) appendText(extra, plain);
            }
            default -> {}
        }
    }

    /**
     * The name {@code display} lists a player under in place of {@code account}: its last word in player-name syntax,
     * when that is not the account and no word of it is; null otherwise.
     */
    public static @Nullable String listedName(@Nullable String display, @Nullable String account) {
        if (display == null || account == null) return null;
        String listed = null;
        for (String word : WORDS.split(display.strip())) {
            if (word.equalsIgnoreCase(account)) return null;
            if (PLAYER_NAME.matcher(word).matches()) listed = word;
        }
        return listed;
    }
}
