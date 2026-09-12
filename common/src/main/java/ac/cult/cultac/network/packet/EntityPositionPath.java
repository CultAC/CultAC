package ac.cult.cultac.network.packet;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Client positions, independent of the server's PositionPath/VecDelta ABI. */
public record EntityPositionPath(Vec3 endPosition, List<Step> steps) {
    public EntityPositionPath {
        Objects.requireNonNull(endPosition);
        steps = List.copyOf(steps);
    }

    public static EntityPositionPath linear(Vec3 position) {
        return new EntityPositionPath(position, List.of());
    }

    public static EntityPositionPath fromNative(Object path) {
        Vec3 end = (Vec3) NmsPacketUtil.invokeNoArg(path, "endPosition");
        List<?> nativeSteps = stepsOrNull(path);
        if (nativeSteps == null) return linear(end);
        List<Step> steps = new ArrayList<>(nativeSteps.size());
        for (Object step : nativeSteps) {
            steps.add(new Step((Vec3) NmsPacketUtil.invokeNoArg(step, "position"),
                    NmsPacketUtil.intValue(step, "tickOffset")));
        }
        return new EntityPositionPath(end, steps);
    }

    /** Decode against a copy of the codec base; only the packet handler commits the endpoint. */
    public static EntityPositionPath decodeRelative(ClientboundMoveEntityPacket packet, Vec3 base) {
        Object delta = NmsPacketUtil.invokeNoArg(packet, "getPositionDelta");
        List<?> nativeSteps = stepsOrNull(delta);
        if (nativeSteps == null) return linear(decodeDelta(base, delta));
        List<Step> steps = new ArrayList<>(nativeSteps.size());
        Vec3 position = base;
        for (Object step : nativeSteps) {
            position = decodeDelta(position, step);
            steps.add(new Step(position, NmsPacketUtil.intValue(step, "ticks")));
        }
        return new EntityPositionPath(position, steps);
    }

    private static Vec3 decodeDelta(Vec3 base, Object delta) {
        return PacketCodecUtil.decodeRelativeEntityPosition(base,
                NmsPacketUtil.intValue(delta, "xa"), NmsPacketUtil.intValue(delta, "ya"),
                NmsPacketUtil.intValue(delta, "za"));
    }

    private static List<?> stepsOrNull(Object path) {
        try {
            return (List<?>) path.getClass().getMethod("steps").invoke(path);
        } catch (NoSuchMethodException linear) {
            return null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to read entity path", failure);
        }
    }

    public record Step(Vec3 position, int tickOffset) {
        public Step {
            Objects.requireNonNull(position);
        }
    }
}
