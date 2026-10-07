package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.utils.math.Vec3;
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

    public static EntityPositionPath fromProtocol(PositionPath path) {
        var position = path.endPosition();
        Vec3 end = new Vec3(position.x(), position.y(), position.z());
        if (path instanceof PositionPath.Linear) return linear(end);
        var steps = ((PositionPath.Stepped) path)
                .steps().stream()
                        .map(step -> new Step(
                                new Vec3(
                                        step.position().x(),
                                        step.position().y(),
                                        step.position().z()),
                                step.tickOffset()))
                        .toList();
        return new EntityPositionPath(end, steps);
    }

    /** Decode against a copy of the codec base; only the packet handler commits the endpoint. */
    public static EntityPositionPath decodeRelative(EntityDelta delta, Vec3 base) {
        if (delta instanceof EntityDelta.Linear value) {
            return linear(PacketCodecUtil.decodeRelativeEntityPosition(base, value.x(), value.y(), value.z()));
        }
        var deltas = ((EntityDelta.Stepped) delta).steps();
        List<Step> steps = new ArrayList<>(deltas.size());
        Vec3 position = base;
        for (var step : deltas) {
            position = PacketCodecUtil.decodeRelativeEntityPosition(position, step.x(), step.y(), step.z());
            steps.add(new Step(position, step.ticks()));
        }
        return new EntityPositionPath(position, steps);
    }

    public record Step(Vec3 position, int tickOffset) {
        public Step {
            Objects.requireNonNull(position);
        }
    }
}
