package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.prediction.input.BedrockPoseInputData;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockClientAction;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

/** Ordered packet actions frozen at an auth-frame boundary, before candidate evaluation. */
final class BedrockFrameActions {
    private final Set<BedrockClientAction> pending = new LinkedHashSet<>();
    private final WeakHashMap<BedrockAuthInputFrame, Set<String>> frames = new WeakHashMap<>();
    private boolean serverUsingItem;

    void applyAcknowledgedItemUse(Boolean usingItem) {
        if (usingItem != null) serverUsingItem = usingItem;
    }

    void record(BedrockClientAction action) {
        pending.add(action);
    }

    Set<String> forFrame(BedrockAuthInputFrame frame) {
        if (frame == null) return Set.of();
        return frames.computeIfAbsent(frame, this::capture);
    }

    private Set<String> capture(BedrockAuthInputFrame frame) {
        TreeSet<String> input = new TreeSet<>();
        if (serverUsingItem) input.add("SERVER_USING_ITEM");
        // Keep both start and stop actions; each phase consumes its own flag.
        for (BedrockClientAction action : pending) {
            input.add(switch (action) {
                case ITEM_RELEASE -> "RELEASE_USING_ITEM";
                case START_GLIDING -> BedrockPoseInputData.START_GLIDING_ACTION;
                case STOP_GLIDING -> BedrockPoseInputData.STOP_GLIDING_ACTION;
                default -> action.name();
            });
        }
        pending.clear();
        if (frame.hasRawInputFlag(PlayerAuthInputData.START_USING_ITEM)) {
            input.add("RIPTIDE_CHARGE_START");
        }
        return Set.copyOf(input);
    }

    void clear() {
        pending.clear();
        frames.clear();
    }
}
