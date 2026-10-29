package ac.cult.cultac.dialog;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundShowDialog;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomClickAction;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

/**
 * Shows {@link Dialog}s and runs their buttons, on any platform: CultAC writes the dialog to the client itself and takes
 * the clicks back out of the stream before the server sees them. Each viewer has at most one dialog that answers
 * clicks, the last one shown to them; a click on an older one, or on one that was closed, does nothing.
 */
public final class Dialogs {
    static final String CLICK_PREFIX = "cultac:dialog/";
    private static final long MAX_PAYLOAD_HEAP = 65_536L;
    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private record Shown(CultPlayer viewer, int id, Dialog dialog) {}

    private Dialogs() {}

    /** Whether {@code viewer}'s client draws dialogs: a Java client on 1.21.6 or later, as CultAC talks to it. */
    public static boolean canShow(CultPlayer viewer) {
        if (CultAPI.INSTANCE.getPlayerDataManager().isKnownBedrockPlayer(viewer.getUniqueId())) return false;
        return viewer.user.getCultConnection().runtime().supports(ClientboundPackets.SHOW_DIALOG);
    }

    /** {@code player} as a {@link DialogViewer}. */
    public static DialogViewer viewer(CultPlayer player) {
        return new PlayerViewer(player);
    }

    private record PlayerViewer(CultPlayer player) implements DialogViewer {
        @Override
        public UUID id() {
            return player.getUniqueId();
        }

        @Override
        public void show(Dialog dialog) {
            Dialogs.show(player, dialog);
        }

        @Override
        public void close() {
            Dialogs.close(player);
        }

        @Override
        public void execute(Runnable task) {
            player.runSafely(task);
        }

        @Override
        public boolean online() {
            return CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player.getUniqueId()) == player;
        }
    }

    /** Shows {@code dialog} to {@code viewer} in place of any dialog of theirs, on their thread. */
    public static void show(CultPlayer viewer, Dialog dialog) {
        viewer.runSafely(() -> {
            if (!viewer.canSendPlayPackets()) {
                LogUtil.debug("Dialog for " + viewer.getName() + " dropped: not in play");
                return;
            }
            int id = NEXT_ID.incrementAndGet();
            byte[] nbt = BinaryNbt.write(DialogNbt.encode(dialog, CLICK_PREFIX + id + "/"));
            SHOWN.put(viewer.getUniqueId(), new Shown(viewer, id, dialog));
            viewer.user.write(new CultWrite(ClientboundShowDialog.inline(nbt), true));
        });
    }

    /** Closes {@code viewer}'s dialog, whatever it is, on their thread. */
    public static void close(CultPlayer viewer) {
        viewer.runSafely(() -> {
            SHOWN.computeIfPresent(viewer.getUniqueId(), (uuid, shown) -> shown.viewer() == viewer ? null : shown);
            if (viewer.canSendPlayPackets()) {
                viewer.user.write(new CultWrite(ClientboundPackets.CLEAR_DIALOG.opaqueValue(), true));
            }
        });
    }

    /** Forgets the dialog {@code viewer} was shown, once they left. */
    public static void forget(UUID uuid, @Nullable CultPlayer viewer) {
        SHOWN.computeIfPresent(uuid, (key, shown) -> shown.viewer() == viewer ? null : shown);
    }

    /**
     * Runs the button {@code click} names when it is on the dialog {@code viewer} was last shown. Called on their
     * thread; true for any click on a CultAC dialog, which the server must not see.
     */
    static boolean clicked(CultPlayer viewer, ServerboundCustomClickAction click) {
        if (!click.id().startsWith(CLICK_PREFIX)) return false;
        Shown shown = SHOWN.get(viewer.getUniqueId());
        if (shown == null || shown.viewer() != viewer) return true;
        Dialog.Button button = button(shown.dialog(), shown.id(), click.id());
        Response response = response(click.payload().bytes());
        if (button == null || response == null) return true;
        try {
            button.onClick().accept(response);
        } catch (RuntimeException failed) {
            LogUtil.error("Dialog button for " + viewer.getName() + " failed", failed);
        }
        return true;
    }

    /** The button of {@code dialog}, shown as {@code id}, that {@code clickId} names; null for another dialog's. */
    static Dialog.@Nullable Button button(Dialog dialog, int id, String clickId) {
        String prefix = CLICK_PREFIX + id + "/";
        if (!clickId.startsWith(prefix)) return null;
        int index;
        try {
            index = Integer.parseInt(clickId.substring(prefix.length()));
        } catch (NumberFormatException notAButton) {
            return null;
        }
        if (index >= 0 && index < dialog.buttons().size())
            return dialog.buttons().get(index);
        return index == dialog.buttons().size() ? dialog.exit() : null;
    }

    /** The inputs a click carried; null when its payload is not a vanilla one. */
    static @Nullable Response response(byte[] payload) {
        if (payload.length == 1 && payload[0] == 0) return Response.EMPTY;
        try {
            if (!(BinaryNbt.read(payload, MAX_PAYLOAD_HEAP) instanceof NbtValue.Compound inputs)) return null;
            Map<String, Object> values = new HashMap<>();
            inputs.values().forEach((key, value) -> {
                if (value instanceof NbtValue.Text text) values.put(key, text.value());
                if (value instanceof NbtValue.Numeric number) values.put(key, number.value());
            });
            return new Response(values);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
