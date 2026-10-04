package ac.cult.cultac.utils.minecraft;

import ac.cult.cultac.network.codec.ModelItemComponents;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.data.ModelRegistryNames;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.placement.PlacementRuntime;
import ac.cult.placement.api.GeometryTags;
import ac.cult.runtime.RuntimeModel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.block.Block;

/** Owns the acquired model services and each service's directed native-value boundary. */
// TODO: What the fuck is codex doing with the if (bedrock) return; hacks
public final class IsolatedMinecraft {
    private static volatile Binding primary;
    private static volatile Map<RuntimeModel, Binding> acquiredBindings = Map.of();
    private static volatile Map<Integer, Binding> actionBindings = Map.of();

    private IsolatedMinecraft() {}

    /** All values in one action must use the same model, ID maps and tag snapshot. */
    public static final class Binding {
        private final PlacementRuntime runtime;
        private final ModelBlockStates states;
        private final ModelRegistryNames names;
        private final ProtocolVersion client;
        private final boolean clientProjection;
        private final GeometryTags tags;

        private Binding(
                PlacementRuntime runtime, ModelBlockStates states, ModelRegistryNames names, GeometryTags tags) {
            this(runtime, states, names, tags, states.target(), false);
        }

        private Binding(
                PlacementRuntime runtime,
                ModelBlockStates states,
                ModelRegistryNames names,
                GeometryTags tags,
                ProtocolVersion client) {
            this(runtime, states, names, tags, client, true);
        }

        private Binding(
                PlacementRuntime runtime,
                ModelBlockStates states,
                ModelRegistryNames names,
                GeometryTags tags,
                ProtocolVersion client,
                boolean clientProjection) {
            this.runtime = runtime;
            this.states = states;
            this.names = names;
            this.tags = tags;
            this.client = client;
            this.clientProjection = clientProjection;
        }

        public PlacementRuntime runtime() {
            return runtime;
        }

        public ProtocolVersion clientProtocol() {
            return client;
        }

        public int toModelState(int hostId) {
            return states.toModel(hostId);
        }

        public int toHostState(int modelId) {
            return states.toHost(modelId);
        }

        public String toModelItem(String hostName) {
            return names.modelItem(hostName);
        }

        public String toHostItem(String modelName) {
            return names.hostItem(modelName);
        }

        public String toModelComponents(String json) {
            return ModelItemComponents.project(json, states.source(), client, states.target());
        }

        public String toHostComponents(String json) {
            return ModelItemComponents.project(json, states.target(), client, states.source());
        }

        public String dimensionType(String typeKey, String json) {
            return ModelDimensions.project(
                    typeKey, ModelDimensions.project(typeKey, json, states.source(), client), client, states.target());
        }

        public GeometryTags tagsFor(CultPlayer player) {
            if (player.registryState == null) return tags;
            return clientProjection
                    ? player.registryState.actionTags(
                            player.user.registries(), states.source(), client, states.target())
                    : player.registryState.geometryTags(
                            player.user.registries(), names, states.source(), states.target());
        }
    }

    public static synchronized void start() {
        if (primary != null) throw new IllegalStateException("Isolated Minecraft already started");
        var host = ProtocolVersion.of(net.minecraft.SharedConstants.getProtocolVersion());
        var model = RuntimeModel.forBackendProtocol(host.protocol());
        var opened = new LinkedHashMap<RuntimeModel, Binding>();
        try {
            opened.put(model, open(host, model, false));
            // A newer backing registry cannot give an older client new action semantics.
            // Acquire both supported families before accepting players, avoiding a download
            // or vanilla bootstrap on the first old-client action.
            if (model != RuntimeModel.JAVA_1_21_11)
                opened.put(RuntimeModel.JAVA_1_21_11, open(host, RuntimeModel.JAVA_1_21_11, true));
            var actions = new LinkedHashMap<Integer, Binding>();
            for (int protocol = 768; protocol <= 777; protocol++) {
                var client = ProtocolVersion.of(protocol);
                var acquired = opened.get(
                        protocol <= RuntimeModel.JAVA_1_21_11.protocol() ? RuntimeModel.JAVA_1_21_11 : model);
                var target = ProtocolVersion.of(acquired.runtime.model().protocol());
                var states = ModelBlockStates.project(host, client, target);
                var names = ModelRegistryNames.project(host, client, target);
                actions.put(
                        protocol,
                        new Binding(
                                acquired.runtime,
                                states,
                                names,
                                NativeGeometryTags.project(NativeGeometryTags.capture(), host, client, target),
                                client));
            }
            acquiredBindings = Map.copyOf(opened);
            actionBindings = Map.copyOf(actions);
            primary = opened.get(model);
        } catch (Exception | Error failure) {
            opened.values().stream().toList().reversed().forEach(binding -> close(binding, failure));
            throw new IllegalStateException("Unable to acquire or start vanilla runtime", failure);
        }
    }

    private static Binding open(ProtocolVersion host, RuntimeModel model, boolean clientProjection) throws Exception {
        var target = ProtocolVersion.of(model.protocol());
        var mapped = clientProjection ? ModelBlockStates.project(host, target) : ModelBlockStates.load(host, target);
        var mappedNames =
                clientProjection ? ModelRegistryNames.project(host, target) : ModelRegistryNames.load(host, target);
        if (mapped.sourceCount() != Block.BLOCK_STATE_REGISTRY.size())
            throw new IllegalStateException("Host block registry does not match its pinned protocol data");
        if (!PlacementRuntime.available(model))
            throw new IllegalStateException("Missing acquisition metadata for " + model.minecraftId());
        PlacementRuntime opened = null;
        try {
            opened = PlacementRuntime.openVanilla(
                    Path.of(PlacementRuntime.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()),
                    ac.cult.cultac.CultAPI.INSTANCE
                            .getGrimPlugin()
                            .getDataFolder()
                            .toPath()
                            .resolve("runtime"),
                    model);
            if (opened.stateCount() != mapped.targetCount())
                throw new IllegalStateException(
                        "Acquired model block registry does not match its pinned protocol data");
            var captured = NativeGeometryTags.capture(mappedNames, mapped.source(), mapped.target());
            opened.prepareInteractions();
            LogUtil.info("Isolated vanilla " + model.minecraftId() + " "
                    + (clientProjection ? "client action" : "placement and geometry")
                    + " runtime started: " + opened.stateCount() + " states");
            return new Binding(opened, mapped, mappedNames, captured);
        } catch (Exception | Error failure) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    public static synchronized void stop() {
        var bindings = acquiredBindings;
        primary = null;
        acquiredBindings = Map.of();
        actionBindings = Map.of();
        bindings.values().forEach(binding -> close(binding, null));
    }

    private static void close(Binding binding, Throwable failure) {
        if (binding == null) return;
        try {
            binding.runtime.close();
        } catch (java.io.IOException cleanup) {
            if (failure == null) LogUtil.error("Unable to close vanilla runtime", cleanup);
            else failure.addSuppressed(cleanup);
        }
    }

    private static Binding compatible(CultPlayer player) {
        var current = primary;
        return player != null
                        && !player.isBedrockMovement()
                        && current != null
                        && player.getClientVersion().getProtocolVersion() >= ProtocolVersion.V1_21_3.protocol()
                        && player.getClientVersion().getProtocolVersion()
                                <= current.states.target().protocol()
                ? current
                : null;
    }

    /** Geometry retains its native host/model boundary. Actions select their own whole binding. */
    public static PlacementRuntime forPlayer(CultPlayer player) {
        var current = compatible(player);
        return current == null ? null : current.runtime;
    }

    public static Binding actionsFor(CultPlayer player) {
        // Geyser's native action observer used the host block-action implementation
        // before it moved into the isolated runtime. Keep that action binding separate
        // from Java movement geometry and Java client-version projections.
        if (player != null && player.isBedrockMovement()) return primary;
        if (player == null) return null;
        int protocol = player.getClientVersion().getProtocolVersion();
        if (protocol < 768 || protocol > 777) return null;
        return actionBindings.get(protocol);
    }

    private static Binding requirePrimary() {
        var current = primary;
        if (current == null) throw new IllegalStateException("Isolated Minecraft is not running");
        return current;
    }

    public static int toModelState(int hostId) {
        return requirePrimary().toModelState(hostId);
    }

    public static int toHostState(int modelId) {
        return requirePrimary().toHostState(modelId);
    }

    public static String toModelItem(String hostName) {
        return requirePrimary().toModelItem(hostName);
    }

    public static String toHostItem(String modelName) {
        return requirePrimary().toHostItem(modelName);
    }

    public static GeometryTags tags() {
        return requirePrimary().tags;
    }

    public static GeometryTags tagsFor(CultPlayer player) {
        return requirePrimary().tagsFor(player);
    }
}
