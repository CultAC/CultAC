package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class CompensatedCameraEntity extends Check {
    private static final int NO_SERVER_CAMERA = Integer.MIN_VALUE;

    private final ArrayDeque<PacketEntity> entities = new ArrayDeque<>(1);
    // The camera the server last sent; NO_SERVER_CAMERA means the player itself.
    private int serverCamera = NO_SERVER_CAMERA;
    // The one possibility while entities holds exactly one, else null; every write to entities ends in refreshOnly.
    private PacketEntity only;

    public CompensatedCameraEntity(CultPlayer player) {
        super(player);
        reset();
    }

    public void onSetCamera(int camera) {
        serverCamera = camera;
        player.sendTransaction();

        player.latencyUtils.addRealTimeTaskNow(() -> {
            PacketEntity entity = player.compensatedEntities.getEntity(camera);
            if (entity != null) {
                entities.add(entity);
                refreshOnly();
            }
        });

        player.latencyUtils.addRealTimeTaskNext(() -> {
            while (entities.size() > 1) {
                entities.poll();
            }

            if (entities.isEmpty()) {
                entities.add(player.compensatedEntities.getSelf());
            }
            refreshOnly();
        });
    }

    public boolean isSelf() {
        PacketEntity self = player.compensatedEntities.getSelf();
        if (only != null) return only == self;
        for (PacketEntity entity : entities) {
            if (entity != self) {
                return false;
            }
        }

        return true;
    }

    public boolean isServerCamera(int entityId) {
        return entityId == serverCamera;
    }

    public List<PacketEntity> getPossibilities() {
        return new ArrayList<>(entities);
    }

    /** The server sent a login or respawn: ClientPacketListener#handleLogin and #handleRespawn reset the camera. */
    public void localPlayerSent() {
        serverCamera = NO_SERVER_CAMERA;
    }

    /** The client handled a login or respawn; a camera sent after it is still the server's camera. */
    public void reset() {
        entities.clear();
        entities.add(player.compensatedEntities.getSelf());
        refreshOnly();
    }

    private void refreshOnly() {
        only = entities.size() == 1 ? entities.peekFirst() : null;
    }
}
