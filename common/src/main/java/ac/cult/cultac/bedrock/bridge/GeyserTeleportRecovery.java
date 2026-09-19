package ac.cult.cultac.bedrock.bridge;

import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.TeleportCache;

/** Final wire coordinates, owned by the movement event loop. Never authorizes movement. */
final class GeyserTeleportRecovery {
    private static final int RETRY_INPUTS = 20;
    private BedrockPacket teleport;
    private SetEntityMotionPacket motion;
    private Consumer<BedrockPacket> writer;
    private Runnable flushed;
    private long lastInputTick;
    private int unconfirmedInputs;

    boolean active() { return teleport != null; }

    /** Drive Geyser's existing cache without allowing its positional tolerance to acknowledge CultAC. */
    static void retryGeyserTeleport(GeyserSession session) {
        var pending = session.getUnconfirmedTeleport();
        if (pending == null) return;
        pending.incrementUnconfirmedFor();
        if (!pending.shouldResend()) return;
        pending.resetUnconfirmedFor();
        var entity = session.getPlayerEntity();
        entity.moveAbsolute(pending.getPosition(), pending.getYaw(), pending.getPitch(), entity.isOnGround(), true);
        if (pending.getTeleportType() == TeleportCache.TeleportType.KEEP_VELOCITY) {
            var motion = new SetEntityMotionPacket();
            motion.setRuntimeEntityId(entity.geyserId());
            motion.setMotion(pending.getVelocity());
            session.sendUpstreamPacket(motion);
        }
    }

    void begin(BedrockPacket packet, long inputTick, Consumer<BedrockPacket> writer, Runnable flushed) {
        this.teleport = packet.clone();
        this.motion = null;
        this.writer = writer;
        this.flushed = flushed;
        this.lastInputTick = inputTick;
        this.unconfirmedInputs = 0;
        writeReset(inputTick);
    }

    void motion(SetEntityMotionPacket packet) {
        if (teleport != null) motion = packet.clone();
    }

    void input(long tick, boolean pending) {
        if (!pending) {
            clear();
            return;
        }
        if (teleport == null || tick <= lastInputTick) return;
        lastInputTick = tick;
        if (++unconfirmedInputs < RETRY_INPUTS) return;
        unconfirmedInputs = 0;
        writeReset(tick);
        writer.accept(teleport.clone());
        if (motion != null) writer.accept(motion.clone());
        flushed.run();
    }

    void clear() {
        teleport = null;
        motion = null;
        writer = null;
        flushed = null;
        unconfirmedInputs = 0;
    }

    private void writeReset(long tick) {
        // No observed movement means no player history to cancel yet. Never invent a future tick.
        if (tick > 0) writer.accept(reset(teleport, tick));
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
