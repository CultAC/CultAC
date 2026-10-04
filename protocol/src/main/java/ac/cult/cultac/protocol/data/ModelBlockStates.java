package ac.cult.cultac.protocol.data;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/** Block IDs at the host/model boundary. Lossy upstream mappings cannot be inverted by guessing. */
public final class ModelBlockStates {
    private final ProtocolVersion source;
    private final ProtocolVersion target;
    private final int[] forward;
    private final int[] reverse;

    private ModelBlockStates(ModelRegistryData source, ModelRegistryData target) {
        this.source = source.version();
        this.target = target.version();
        if (this.source.protocol() > this.target.protocol()) {
            var olderToNewer = new ModelBlockStates(target, source);
            forward = olderToNewer.reverse;
            reverse = olderToNewer.forward;
            return;
        }
        forward = new int[source.blockStates().size()];
        reverse = new int[target.blockStates().size()];
        Arrays.fill(reverse, -1);
        var mappings = this.source == this.target ? null : ModelIdMappings.load(this.source, this.target);
        for (int id = 0; id < forward.length; id++) {
            int mapped = mappings == null ? id : mappings.blockState(id);
            if (mapped < 0 || mapped >= reverse.length)
                throw new ProtocolResolutionException("Invalid backing-model block ID " + mapped);
            forward[id] = mapped;
            // -2 records ambiguity permanently, even when a third source maps here.
            reverse[mapped] = reverse[mapped] == -1 ? id : -2;
        }
        // An exact name/property match is authoritative even when another old state
        // has a lossy upstream fallback to that same state.
        for (int id = 0; id < reverse.length; id++) {
            int exact = source.blockStateId(target.blockStateName(id));
            if (exact >= 0) reverse[id] = exact;
        }
    }

    public static ModelBlockStates load(ProtocolVersion source, ProtocolVersion target) {
        return new ModelBlockStates(ModelRegistryData.load(source), ModelRegistryData.load(target));
    }

    /** Client-visible projection uses two independently proven directions; no lossy map is inverted. */
    public static ModelBlockStates project(ProtocolVersion source, ProtocolVersion target) {
        if (source == target) return load(source, target);
        return new ModelBlockStates(
                ModelRegistryData.load(source),
                ModelRegistryData.load(target),
                ModelIdMappings.project(source, target),
                ModelIdMappings.project(target, source));
    }

    /** A family model must see the states the original client received, including its fallbacks. */
    public static ModelBlockStates project(ProtocolVersion source, ProtocolVersion client, ProtocolVersion target) {
        var toClient = ModelIdMappings.project(source, client);
        var toModel = ModelIdMappings.project(client, target);
        var fromModel = ModelIdMappings.project(target, client);
        var toHost = ModelIdMappings.project(client, source);
        var sourceData = ModelRegistryData.load(source);
        var targetData = ModelRegistryData.load(target);
        var clientData = ModelRegistryData.load(client);
        if (toClient.blockStateCount() != sourceData.blockStates().size()
                || toModel.blockStateCount() != clientData.blockStates().size()
                || fromModel.blockStateCount() != targetData.blockStates().size()
                || toHost.blockStateCount() != clientData.blockStates().size())
            throw new ProtocolResolutionException("Client-directed block mapping does not match its registry");
        return new ModelBlockStates(
                sourceData,
                targetData,
                id -> toModel.blockState(toClient.blockState(id)),
                id -> toHost.blockState(fromModel.blockState(id)));
    }

    private ModelBlockStates(
            ModelRegistryData source, ModelRegistryData target, ModelIdMappings toModel, ModelIdMappings toHost) {
        this(source, target, toModel::blockState, toHost::blockState);
        if (forward.length != toModel.blockStateCount() || reverse.length != toHost.blockStateCount())
            throw new ProtocolResolutionException("Directed block mapping does not match its registry");
    }

    private ModelBlockStates(
            ModelRegistryData source, ModelRegistryData target, IntUnaryOperator toModel, IntUnaryOperator toHost) {
        this.source = source.version();
        this.target = target.version();
        forward = new int[source.blockStates().size()];
        reverse = new int[target.blockStates().size()];
        for (int id = 0; id < forward.length; id++) {
            forward[id] = toModel.applyAsInt(id);
            if (forward[id] < 0 || forward[id] >= reverse.length)
                throw new ProtocolResolutionException("Invalid directed model block ID");
        }
        for (int id = 0; id < reverse.length; id++) {
            reverse[id] = toHost.applyAsInt(id);
            if (reverse[id] < 0 || reverse[id] >= forward.length)
                throw new ProtocolResolutionException("Invalid directed host block ID");
        }
    }

    public ProtocolVersion source() {
        return source;
    }

    public ProtocolVersion target() {
        return target;
    }

    public int sourceCount() {
        return forward.length;
    }

    public int targetCount() {
        return reverse.length;
    }

    public int toModel(int id) {
        if (id < 0 || id >= forward.length) throw new MalformedPacketException("Unknown host block ID " + id);
        if (forward[id] < 0)
            throw new ProtocolResolutionException("Host block ID " + id + " has "
                    + (forward[id] == -2 ? "an ambiguous" : "no") + " representation on " + target);
        return forward[id];
    }

    public int toHost(int id) {
        if (id < 0 || id >= reverse.length) throw new MalformedPacketException("Unknown model block ID " + id);
        if (reverse[id] < 0)
            throw new ProtocolResolutionException("Model block ID " + id + " has "
                    + (reverse[id] == -2 ? "an ambiguous" : "no") + " representation on " + source);
        return reverse[id];
    }
}
