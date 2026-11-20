package ac.cult.cultac.bedrock.player;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;

/**
 * Ordered loading transitions; only server-anticipated screens may stop actor movement.
 * Mojang's ServerboundLoadingScreenPacket documentation requires an anticipated start and matching ID:
 * https://github.com/Mojang/bedrock-protocol-docs/blob/main/json/ServerboundLoadingScreenPacket.json
 */
public final class BedrockLoadingScreenState {
    private final Set<Integer> anticipated = new HashSet<>();
    private boolean active;
    private Integer activeId;

    public void startGame() {
        anticipated.clear();
        anticipated.add(null);
        active = false;
        activeId = null;
    }

    public void changeDimension(Integer id) {
        if (id != null) anticipated.add(id);
    }

    public boolean accept(ServerboundLoadingScreenPacketType type, Integer id) {
        if (type == ServerboundLoadingScreenPacketType.START_LOADING_SCREEN) {
            if (!anticipated.remove(id)) return false;
            activeId = id;
            active = true;
            return true;
        }
        if (type == ServerboundLoadingScreenPacketType.END_LOADING_SCREEN && active && Objects.equals(activeId, id)) {
            active = false;
            activeId = null;
            return true;
        }
        return false;
    }

    public boolean active() {
        return active;
    }

    /** Connection state survives Geyser's backend switch; only JDK values cross engine loaders. */
    public java.util.Map<String, Object> continuation() {
        var state = new java.util.HashMap<String, Object>();
        state.put("anticipated", new java.util.ArrayList<>(anticipated));
        state.put("active", active);
        if (activeId != null) state.put("active-id", activeId);
        return java.util.Map.copyOf(state);
    }

    public void restore(java.util.Map<?, ?> state) {
        anticipated.clear();
        for (var id : (java.util.List<?>) state.get("anticipated")) anticipated.add((Integer) id);
        active = (Boolean) state.get("active");
        activeId = (Integer) state.get("active-id");
    }
}
