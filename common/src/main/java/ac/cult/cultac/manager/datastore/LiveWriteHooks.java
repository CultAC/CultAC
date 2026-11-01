package ac.cult.cultac.manager.datastore;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.AbstractCheck;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plugin-side facade that fans out player-lifecycle and flag events into the
 * data store.
 *
 * <p>The "from" convenience methods build all the per-call args (UUID, name,
 * timestamp, {@link SessionTracker.ClientMeta}) inside the impl. Granular
 * methods stay platform-neutral for tests + tooling.
 */
public interface LiveWriteHooks {

    /** Player joined. Upsert identity (firstSeen=min / lastSeen=max) and open/extend a session. */
    void onJoin(@NotNull UUID uuid, @Nullable String name, long now, @NotNull SessionTracker.ClientMeta meta);

    /** Player disconnected. Flush the closing heartbeat on the session row + clear the in-memory tracker entry. */
    void onQuit(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta);

    /**
     * Brand packet arrived. Re-issues a session upsert with the now-known
     * {@code clientBrand} field — the brand-channel plugin message can arrive
     * after PlayerJoinEvent, so the initial onJoin upsert may have left
     * client_brand null.
     */
    void observeBrand(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta);

    /** Record a single flag with the full check object — pulls stable_key + description from the check. */
    void recordFlag(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            @Nullable String verbose,
            long now,
            @NotNull SessionTracker.ClientMeta meta);

    /** Record a single flag whose verbose payload is already encoded as opaque bytes. */
    void recordFlagData(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            byte @Nullable [] verboseData,
            long now,
            @NotNull SessionTracker.ClientMeta meta);

    /** Convenience for {@code PlayerLoginHooks.onLogin}. Builds meta from the User + CultPlayer internally. */
    void onJoinFromUserLogin(@NotNull PlatformPlayer player, @NotNull User user, long now);

    /** Convenience for {@code PlayerDataManager.onDisconnect}. Builds meta internally; tolerates null cultPlayer (early-disconnect race). */
    void onQuitFromUserDisconnect(@NotNull User user, @Nullable CultPlayer cultPlayer, long now);

    /** Convenience for {@code ClientBrand.onPacketReceive}. Pulls UUID + builds meta from the CultPlayer internally. */
    void observeBrandFromCheck(@NotNull CultPlayer cultPlayer);

    /**
     * Convenience for {@code PunishmentManager.handleAlert}. Wraps the call
     * in a try/catch so call sites don't need their own — datastore failures
     * are logged as a warning and swallowed.
     */
    void recordFlagFromCheck(
            @NotNull CultPlayer player, @NotNull AbstractCheck check, double vl, @Nullable String verbose);

    /** Binary-verbose counterpart to {@link #recordFlagFromCheck(CultPlayer, AbstractCheck, double, String)}. */
    void recordFlagDataFromCheck(
            @NotNull CultPlayer player, @NotNull AbstractCheck check, double vl, byte @Nullable [] verboseData);

    /**
     * Build the {@link SessionTracker.ClientMeta} stamped on session upserts
     * for {@code user}. Reads from the passed-in {@link CultPlayer} (when
     * non-null) for the captured client brand. Public so tests and other
     * callers can mint a meta with the same shape the impl uses.
     */
    static @NotNull SessionTracker.ClientMeta clientMetaFor(@NotNull User user, @Nullable CultPlayer gp) {
        String grimVersion = CultAPI.INSTANCE.getExternalAPI().getGrimVersion();
        int clientVersionPvn = gp != null
                ? gp.getClientVersion().getProtocolVersion()
                : ClientVersionResolver.resolveProtocolVersion(user);
        String serverVersion = CultAPI.INSTANCE.getPlatformServer().getPlatformImplementationString();
        String brand = null;
        if (gp != null) {
            ClientBrand check = gp.checkManager.getCheck(ClientBrand.class);
            if (check != null && check.isHasBrand()) brand = check.getBrand();
        }
        return new SessionTracker.ClientMeta(
                grimVersion, brand, clientVersionPvn, serverVersion, user.getConnectionId());
    }
}
