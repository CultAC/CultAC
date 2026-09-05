package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class CompensatedCameraEntity extends Check {
    // 26.2 keeps cameraId private with no accessor (only getEntity(Level)); resolve it once.
    private static final Field CAMERA_ID = resolveCameraIdField();

    private final ArrayDeque<PacketEntity> entities = new ArrayDeque<>(1);

    public CompensatedCameraEntity(CultPlayer player) {
        super(player);
        reset();
    }


    public void onSetCamera(ClientboundSetCameraPacket packet) {
        final int camera = readCameraId(packet);
        player.sendTransaction();

        player.latencyUtils.addRealTimeTaskNow(() -> {
            PacketEntity entity = player.compensatedEntities.getEntity(camera);
            if (entity != null) {
                entities.add(entity);
            }
        });

        player.latencyUtils.addRealTimeTaskNext(() -> {
            while (entities.size() > 1) {
                entities.poll();
            }

            if (entities.isEmpty()) {
                entities.add(player.compensatedEntities.getSelf());
            }
        });
    }

    public boolean isSelf() {
        PacketEntity self = player.compensatedEntities.getSelf();
        for (PacketEntity entity : entities) {
            if (entity != self) {
                return false;
            }
        }

        return true;
    }

    public List<PacketEntity> getPossibilities() {
        return new ArrayList<>(entities);
    }

    public void reset() {
        entities.clear();
        entities.add(player.compensatedEntities.getSelf());
    }

    private static Field resolveCameraIdField() {
        try {
            Field field = ClientboundSetCameraPacket.class.getDeclaredField("cameraId");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Unable to find ClientboundSetCameraPacket#cameraId", exception);
        }
    }

    private static int readCameraId(ClientboundSetCameraPacket packet) {
        try {
            return CAMERA_ID.getInt(packet);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read ClientboundSetCameraPacket#cameraId", exception);
        }
    }
}
