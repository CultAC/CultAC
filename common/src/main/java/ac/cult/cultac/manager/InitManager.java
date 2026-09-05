package ac.cult.cultac.manager;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.init.Initable;
import ac.cult.cultac.manager.init.load.LoadableInitable;
import ac.cult.cultac.manager.init.load.NetworkManagerInit;
import ac.cult.cultac.manager.init.start.*;
import ac.cult.cultac.manager.init.stop.StoppableInitable;
import ac.cult.cultac.manager.init.stop.TerminateGeyserBedrockBridge;
import ac.cult.cultac.manager.init.stop.TerminateNetworkManager;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.google.common.collect.ImmutableList;
import lombok.Getter;

import java.util.ArrayList;

public class InitManager {

    private final ImmutableList<LoadableInitable> initializersOnLoad;
    private final ImmutableList<StartableInitable> initializersOnStart;
    private final ImmutableList<StoppableInitable> initializersOnStop;

    @Getter
    private boolean loaded = false;
    @Getter
    private boolean started = false;
    @Getter
    private boolean stopped = false;

    public InitManager(Initable... platformSpecificInitables) {
        ArrayList<LoadableInitable> extraLoadableInitables = new ArrayList<>();
        ArrayList<StartableInitable> extraStartableInitables = new ArrayList<>();
        ArrayList<StoppableInitable> extraStoppableInitables = new ArrayList<>();
        for (Initable initable : platformSpecificInitables) {
            if (initable instanceof LoadableInitable) extraLoadableInitables.add((LoadableInitable) initable);
            if (initable instanceof StartableInitable) extraStartableInitables.add((StartableInitable) initable);
            if (initable instanceof StoppableInitable) extraStoppableInitables.add((StoppableInitable) initable);
        }

        initializersOnLoad = ImmutableList.<LoadableInitable>builder()
                .add(new NetworkManagerInit())
                .add(() -> CultAPI.INSTANCE.getExternalAPI().load())
                .addAll(extraLoadableInitables)
                .build();

        initializersOnStart = ImmutableList.<StartableInitable>builder()
                .add(CultAPI.INSTANCE.getExternalAPI())
                .add(new PacketManager())
                .add(new ViaBackwardsManager())
                .add(new TickRunner())
                .add(new TickEndEvent())
                .add(new CommandRegister(CultAPI.INSTANCE.getCommandService()))
                .add(new ChannelManager())
                .add(new UpdateChecker())
                .add(new PacketLimiter())
                .add(CultAPI.INSTANCE.getAlertManager())
                .add(CultAPI.INSTANCE.getDiscordManager())
                .add(CultAPI.INSTANCE.getSpectateManager())
                .add(CultAPI.INSTANCE.getDataStoreLifecycle())
                .add(new JavaVersion())
                .add(new ViaVersion())
                .add(new TAB())
                .add(new NetworkManagerStart())
                // Geyser Bedrock bridge starts late: it taps Geyser sessions and must run
                // after networking is up. The initable itself loads no Geyser classes
                // (soft-dep: gated on the Geyser-Spigot plugin, LinkageError-guarded).
                .add(new GeyserBedrockBridgeInit())
                .addAll(extraStartableInitables)
                .build();

        initializersOnStop = ImmutableList.<StoppableInitable>builder()
                // Detach the Geyser session taps before Cult's own networking tears down.
                .add(new TerminateGeyserBedrockBridge())
                .add(new TerminateNetworkManager())
                .add(CultAPI.INSTANCE.getDataStoreLifecycle())
                .addAll(extraStoppableInitables)
                .build();
    }

    public void load() {
        for (LoadableInitable initable : initializersOnLoad) {
            try {
                initable.load();
            } catch (Exception e) {
                LogUtil.error("Failed to load " + initable.getClass().getSimpleName(), e);
            }
        }
        loaded = true;
    }

    public void start() {
        for (StartableInitable initable : initializersOnStart) {
            try {
                initable.start();
            } catch (Exception e) {
                LogUtil.error("Failed to start " + initable.getClass().getSimpleName(), e);
            }
        }
        started = true;
    }

    public void stop() {
        for (StoppableInitable initable : initializersOnStop) {
            try {
                initable.stop();
            } catch (Exception e) {
                LogUtil.error("Failed to stop " + initable.getClass().getSimpleName(), e);
            }
        }
        stopped = true;
    }
}
