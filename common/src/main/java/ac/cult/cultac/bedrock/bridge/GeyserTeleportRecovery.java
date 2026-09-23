package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.geysermc.geyser.session.GeyserSession;

final class GeyserTeleportRecovery {
    private static final int RETRY_INPUTS = 20;
    private BedrockTeleportOperation operation;
    private BedrockCoordinateFrame origin;
    private SetEntityMotionPacket motion;
    private Consumer<SetEntityMotionPacket> resend;
    private long lastInputTick;
    private int unconfirmedInputs;

    boolean active() { return operation != null; }
    BedrockTeleportOperation operation() { return operation; }

    static void confirmRejectedInput(GeyserSession session, PlayerAuthInputPacket input) {
        if (!session.isSpawned() || session.getUnconfirmedTeleport() == null) return;
        session.confirmTeleport(input.getPosition().down((float) BedrockPositionTranslator.PLAYER_PACKET_Y_OFFSET));
    }

    void begin(BedrockPacket packet, BedrockTeleportOperation operation, BedrockCoordinateFrame origin,
               long inputTick, Consumer<BedrockPacket> writer, Consumer<SetEntityMotionPacket> resend) {
        this.operation = operation;
        this.origin = origin;
        this.motion = null;
        this.resend = resend;
        this.lastInputTick = inputTick;
        this.unconfirmedInputs = 0;
        if (inputTick > 0) writer.accept(reset(packet, inputTick));
    }

    void motion(SetEntityMotionPacket packet) {
        if (active()) motion = packet.clone();
    }

    void input(long tick, boolean pending, BedrockCoordinateFrame writtenOrigin) {
        if (!pending) {
            clear();
            return;
        }
        if (!active() || tick <= lastInputTick) return;
        lastInputTick = tick;
        if (origin.equals(writtenOrigin) && ++unconfirmedInputs < RETRY_INPUTS) return;
        unconfirmedInputs = 0;
        resend.accept(motion == null ? null : motion.clone());
    }

    void clear() {
        operation = null;
        origin = null;
        motion = null;
        resend = null;
        unconfirmedInputs = 0;
    }

    static MovePlayerPacket reset(BedrockPacket teleport, long tick) {
        MovePlayerPacket reset;
        if (teleport instanceof MovePlayerPacket move) {
            reset = move.clone();
        } else if (teleport instanceof MoveEntityAbsolutePacket move) {
            reset = new MovePlayerPacket();
            reset.setRuntimeEntityId(move.getRuntimeEntityId());
            reset.setPosition(move.getPosition());
            reset.setRotation(move.getRotation());
            reset.setOnGround(move.isOnGround());
        } else {
            throw new IllegalArgumentException("Not a player teleport");
        }
        // Wire mode 1 (RESET in the protocol, RESPAWN in Cloudburst) clears queued corrections
        // and history before ordinary movement when timestamped.
        // TELEPORT with tick zero bypasses that branch; timestamping TELEPORT can instead replay it.
        reset.setMode(MovePlayerPacket.Mode.RESPAWN);
        reset.setTick(tick);
        return reset;
    }
}
