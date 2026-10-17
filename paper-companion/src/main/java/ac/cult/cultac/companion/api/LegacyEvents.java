package ac.cult.cultac.companion.api;

import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.events.CommandExecuteEvent;
import ac.grim.grimac.api.events.CompletePredictionEvent;
import ac.grim.grimac.api.events.FlagEvent;
import ac.grim.grimac.api.events.GrimJoinEvent;
import ac.grim.grimac.api.events.GrimQuitEvent;
import ac.grim.grimac.api.events.GrimReloadEvent;
import java.util.function.Consumer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** The pre-1.3 Bukkit Grim events; each is built and called only when a Bukkit listener is registered for it. */
@SuppressWarnings("removal")
final class LegacyEvents {
    private final Consumer<Event> caller;

    /**
     * {@code caller} calls an async event on the current thread, which is never the main thread for the events that
     * report a cancel; on the main thread it queues the event off it, in submission order, without waiting.
     */
    LegacyEvents(Consumer<Event> caller) {
        this.caller = caller;
    }

    private static boolean listened(HandlerList handlers) {
        return handlers.getRegisteredListeners().length > 0;
    }

    static boolean hasFlag() {
        return listened(FlagEvent.getHandlerList());
    }

    static boolean hasReload() {
        return listened(GrimReloadEvent.getHandlerList());
    }

    static boolean hasCommandExecute() {
        return listened(CommandExecuteEvent.getHandlerList());
    }

    static boolean hasCompletePrediction() {
        return listened(CompletePredictionEvent.getHandlerList());
    }

    /** True when a listener cancelled the event. */
    boolean flag(GrimUser user, AbstractCheck check, String verbose) {
        if (!hasFlag()) return false;
        FlagEvent event = new FlagEvent(user, check, verbose);
        caller.accept(event);
        return event.isCancelled();
    }

    /** True when a listener cancelled the event. */
    boolean commandExecute(GrimUser user, AbstractCheck check, String verbose, String command) {
        if (!hasCommandExecute()) return false;
        CommandExecuteEvent event = new CommandExecuteEvent(user, check, verbose, command);
        caller.accept(event);
        return event.isCancelled();
    }

    void join(GrimUser user) {
        if (!listened(GrimJoinEvent.getHandlerList())) return;
        caller.accept(new GrimJoinEvent(user));
    }

    void quit(GrimUser user) {
        if (!listened(GrimQuitEvent.getHandlerList())) return;
        caller.accept(new GrimQuitEvent(user));
    }

    void reload(boolean success) {
        if (hasReload()) caller.accept(new GrimReloadEvent(success));
    }
}
