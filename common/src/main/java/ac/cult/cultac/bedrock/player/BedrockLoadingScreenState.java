package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;

/**
 * Ordered loading transitions; only server-anticipated screens may stop actor movement.
 * Mojang's ServerboundLoadingScreenPacket documentation requires an anticipated start and matching ID:
 * https://github.com/Mojang/bedrock-protocol-docs/blob/main/json/ServerboundLoadingScreenPacket.json
 */
public final class BedrockLoadingScreenState {
    /** A screen the server announced; a dimension change carries the wire feet position it places the actor at. */
    private record Announced(Integer id, Vec3 position) {}

    // Screens the server has announced and the client has not started yet, in order. A dimension change may carry
    // no id, and two of them sent back to back announce two screens with the same one.
    private final List<Announced> anticipated = new ArrayList<>();
    private boolean active;
    private Integer activeId;
    private Vec3 placement;

    public void startGame() {
        anticipated.clear();
        anticipated.add(new Announced(null, null));
        active = false;
        activeId = null;
        placement = null;
    }

    public void changeDimension(Integer id, Vec3 position) {
        anticipated.add(new Announced(id, position));
    }

    public boolean accept(ServerboundLoadingScreenPacketType type, Integer id) {
        if (type == ServerboundLoadingScreenPacketType.START_LOADING_SCREEN) {
            for (Iterator<Announced> it = anticipated.iterator(); it.hasNext(); ) {
                Announced announced = it.next();
                if (!Objects.equals(announced.id(), id)) continue;
                it.remove();
                activeId = id;
                active = true;
                placement = announced.position();
                return true;
            }
            return false;
        }
        if (type == ServerboundLoadingScreenPacketType.END_LOADING_SCREEN && active && Objects.equals(activeId, id)) {
            active = false;
            activeId = null;
            placement = null;
            return true;
        }
        return false;
    }

    public boolean active() {
        return active;
    }

    /**
     * The wire feet position of the dimension change whose screen is active. The client puts its actor there, at
     * rest, when it starts that screen. Null for any other screen.
     */
    public Vec3 placement() {
        return placement;
    }

    /** Connection state survives Geyser's backend switch; only JDK values cross engine loaders. */
    public java.util.Map<String, Object> continuation() {
        var state = new java.util.HashMap<String, Object>();
        // One entry per announced screen, so an id announced twice is listed twice.
        var ids = new ArrayList<Integer>();
        var positions = new ArrayList<List<Double>>();
        for (Announced announced : anticipated) {
            ids.add(announced.id());
            positions.add(coordinates(announced.position()));
        }
        state.put("anticipated", ids);
        state.put("anticipated-positions", positions);
        state.put("active", active);
        if (activeId != null) state.put("active-id", activeId);
        return java.util.Map.copyOf(state);
    }

    public void restore(java.util.Map<?, ?> state) {
        anticipated.clear();
        var ids = (List<?>) state.get("anticipated");
        var positions = (List<?>) state.get("anticipated-positions");
        for (int i = 0; i < ids.size(); i++) {
            anticipated.add(new Announced((Integer) ids.get(i), positions == null ? null : position(positions.get(i))));
        }
        active = (Boolean) state.get("active");
        activeId = (Integer) state.get("active-id");
        placement = null;
    }

    private static List<Double> coordinates(Vec3 position) {
        return position == null ? null : List.of(position.x, position.y, position.z);
    }

    private static Vec3 position(Object coordinates) {
        if (!(coordinates instanceof List<?> list)) return null;
        return new Vec3((Double) list.get(0), (Double) list.get(1), (Double) list.get(2));
    }
}
