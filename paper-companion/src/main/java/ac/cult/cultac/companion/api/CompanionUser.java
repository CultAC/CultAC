package ac.cult.cultac.companion.api;

import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertToggle;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckCatalog;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckInfo;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Metrics;
import ac.cult.cultac.bridge.protocol.BridgeMessage.UserInfo;
import ac.cult.cultac.companion.bridge.CompanionSession;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.PacketWorld;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.feature.FeatureManager;
import ac.grim.grimac.api.handler.ResyncHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * A player whose checks run on the CultAC proxy; exists from {@code PlayerJoinEvent} until {@code PlayerQuitEvent}.
 *
 * <p>Name, world, keep-alive ping, messages and permissions are read from the backend player. Brand, version and
 * vanilla math mirror USER_INFO. Transaction ping and sensitivities mirror METRICS. The last transaction ids ride along
 * with METRICS, so they are coarse. Mirrored values keep their defaults until a bridge session is READY.
 */
public final class CompanionUser implements GrimUser {
    private static final int NEW = 0;
    private static final int JOINING = 1;
    private static final int JOINED = 2;
    private static final int QUIT_PENDING = 3;
    private static final int QUIT = 4;
    private static final ResyncHandler NO_RESYNC = new ResyncHandler() {
        @Override
        public void resync(int minBlockX, int minBlockY, int minBlockZ, int maxBlockX, int maxBlockY, int maxBlockZ) {}

        @Override
        public void resyncPosition(int x, int y, int z, int sequence) {}
    };

    private volatile @Nullable CompanionSession session;
    private final Player player;
    private final Executor mainThread;
    private final FeatureManager featureManager;
    private volatile String brand = "Unknown";
    private volatile String versionName = "Unknown";
    private volatile boolean vanillaMath;
    private volatile int transactionPing;
    private volatile double horizontalSensitivity;
    private volatile double verticalSensitivity;
    private volatile int lastTransactionSent;
    private volatile int lastTransactionReceived;
    private volatile List<CompanionCheck> checks = List.of();
    private volatile int alertBits;
    private volatile ResyncHandler resyncHandler = NO_RESYNC;
    private Map<String, CompanionCheck> carryOver = Map.of();
    private final AtomicInteger joinState = new AtomicInteger(NEW);

    CompanionUser(Player player, Executor mainThread, FeatureManager featureManager) {
        this.player = player;
        this.mainThread = mainThread;
        this.featureManager = featureManager;
    }

    Player player() {
        return player;
    }

    @Nullable
    CompanionSession session() {
        return session;
    }

    void attach(CompanionSession session) {
        this.session = session;
    }

    /** True the first time, while the player has not quit: GrimJoinEvent fires once per Bukkit join. */
    boolean beginJoin() {
        return joinState.compareAndSet(NEW, JOINING);
    }

    /** Ends the GrimJoinEvent dispatch; false when the player quit during it, so the caller fires GrimQuitEvent. */
    boolean finishJoin() {
        if (joinState.compareAndSet(JOINING, JOINED)) return true;
        joinState.set(QUIT);
        return false;
    }

    /**
     * True when the join had finished, so GrimQuitEvent is due; a quit during the join is left to
     * {@link #finishJoin()}.
     */
    boolean markQuit() {
        return joinState.getAndUpdate(state -> state == JOINING ? QUIT_PENDING : QUIT) == JOINED;
    }

    /** Detaches {@code session} if it is the attached one; returns whether it was. */
    boolean detach(CompanionSession session) {
        if (this.session != session) return false;
        this.session = null;
        return true;
    }

    void apply(UserInfo info) {
        brand = info.brand();
        versionName = info.versionName();
        vanillaMath = info.vanillaMath();
    }

    void apply(Metrics metrics) {
        transactionPing = metrics.transactionPing();
        horizontalSensitivity = metrics.sensX();
        verticalSensitivity = metrics.sensY();
        lastTransactionSent = metrics.lastTransSent();
        lastTransactionReceived = metrics.lastTransRecv();
    }

    /**
     * Applies one CHECK_CATALOG chunk. A chunk whose first entry is idx 0 starts a new catalog; later chunks append or
     * replace by idx. Checks keep their violations when their stable key survives into the new catalog. Netty thread
     * only.
     */
    void apply(CheckCatalog catalog) {
        List<CheckInfo> infos = catalog.checks();
        List<CompanionCheck> current = checks;
        boolean restart = infos.isEmpty() || infos.getFirst().idx() == 0;
        if (restart) {
            Map<String, CompanionCheck> byKey = new HashMap<>(current.size());
            for (CompanionCheck check : current) byKey.put(check.getStableKey(), check);
            carryOver = byKey;
        }
        List<CompanionCheck> next = new ArrayList<>(restart ? List.of() : current);
        for (CheckInfo info : infos) {
            int idx = info.idx();
            if (idx < 0 || idx > next.size()) continue;
            CompanionCheck check = new CompanionCheck(info);
            CompanionCheck old = info.stableKey().isEmpty() ? null : carryOver.get(info.stableKey());
            if (old != null) check.commitFlag(old.getViolations(), old.getLastViolationTime());
            if (idx == next.size()) next.add(check);
            else next.set(idx, check);
        }
        checks = List.copyOf(next);
        if (catalog.last()) carryOver = Map.of();
    }

    CompanionCheck check(int index) {
        List<CompanionCheck> current = checks;
        if (index < 0 || index >= current.size()) return null;
        CompanionCheck check = current.get(index);
        return check.index() == index ? check : null;
    }

    boolean hasAlert(int bit) {
        return session != null && (alertBits & bit) != 0;
    }

    void setAlertBits(int bits) {
        alertBits = bits;
    }

    /**
     * Sends ALERT_TOGGLE and updates the local state before the proxy confirms it with ALERT_STATE; no effect without
     * a session.
     */
    void toggleAlert(int kind, int bit, boolean enabled, boolean silent) {
        CompanionSession current = session;
        if (current == null) return;
        alertBits = enabled ? alertBits | bit : alertBits & ~bit;
        current.send(new AlertToggle(kind, enabled, silent));
    }

    @Override
    public String getName() {
        return player.getName();
    }

    @Override
    public UUID getUniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String getBrand() {
        return brand;
    }

    @Override
    public String getWorldName() {
        return player.getWorld().getName();
    }

    @Override
    public UUID getWorldUID() {
        return player.getWorld().getUID();
    }

    /** Unsupported: the packet world lives on the proxy. */
    @Override
    public PacketWorld getPacketWorld() {
        throw Unsupported.method("getPacketWorld");
    }

    /** Mirrored from METRICS. */
    @Override
    public int getTransactionPing() {
        return transactionPing;
    }

    @Override
    public int getKeepAlivePing() {
        return player.getPing();
    }

    @Override
    public String getVersionName() {
        return versionName;
    }

    /** Mirrored from METRICS. */
    @Override
    public double getHorizontalSensitivity() {
        return horizontalSensitivity;
    }

    /** Mirrored from METRICS. */
    @Override
    public double getVerticalSensitivity() {
        return verticalSensitivity;
    }

    @Override
    public boolean isVanillaMath() {
        return vanillaMath;
    }

    /** No effect: the proxy reads permissions itself. */
    @Override
    public void updatePermissions() {}

    @Override
    public Collection<CompanionCheck> getChecks() {
        return checks;
    }

    /** Runs the task on the server main thread. */
    @Override
    public void runSafely(Runnable runnable) {
        mainThread.execute(runnable);
    }

    /** Coarse: the value at the last METRICS, which a transaction alone never triggers. */
    @Override
    public int getLastTransactionReceived() {
        return lastTransactionReceived;
    }

    /** Coarse: the value at the last METRICS, which a transaction alone never triggers. */
    @Override
    public int getLastTransactionSent() {
        return lastTransactionSent;
    }

    /** Unsupported: transactions are sent by the proxy. */
    @Override
    public void addRealTimeTask(int transaction, Runnable runnable) {
        throw Unsupported.method("addRealTimeTask");
    }

    /** Unsupported: transactions are sent by the proxy. */
    @Override
    public void addRealTimeTaskNow(Runnable runnable) {
        throw Unsupported.method("addRealTimeTaskNow");
    }

    /** Unsupported: transactions are sent by the proxy. */
    @Override
    public void addRealTimeTaskNext(Runnable runnable) {
        throw Unsupported.method("addRealTimeTaskNext");
    }

    @Override
    public FeatureManager getFeatureManager() {
        return featureManager;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void sendMessage(String message) {
        player.sendMessage(message);
    }

    @Override
    public boolean hasPermission(String permission) {
        if (CheckPermissions.isCheckNode(permission) && !player.isPermissionSet(permission)) return false;
        return player.hasPermission(permission);
    }

    @Override
    public ResyncHandler getResyncHandler() {
        return resyncHandler;
    }

    @Override
    public void setResyncHandler(ResyncHandler handler) {
        this.resyncHandler = handler;
    }

    /** No effect: per-player configuration lives on the proxy. */
    @Override
    public void reload(ConfigManager config) {}

    /** No effect: per-player configuration lives on the proxy. */
    @Override
    public void reload() {}
}
