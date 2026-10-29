package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.reload.api.HostContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * The engine generations running in this process, each a member receiving every message any of them sends, the
 * sender included. The member list is the host's shared object, which holds JDK types only; without a host this
 * generation is the bus's only member.
 */
final class InProcessBus {
    static final String KEY = "cultac.network.bus";

    private final List<Consumer<String>> members;
    private final Consumer<String> member;

    private InProcessBus(List<Consumer<String>> members, Consumer<String> member) {
        this.members = members;
        this.member = member;
    }

    @SuppressWarnings("unchecked")
    static InProcessBus join(@Nullable HostContext host, Consumer<String> member) {
        List<Consumer<String>> members = host == null
                ? new CopyOnWriteArrayList<>()
                : (List<Consumer<String>>) host.shared(KEY, CopyOnWriteArrayList::new);
        members.add(member);
        return new InProcessBus(members, member);
    }

    /** Hands {@code text} to every member on the caller's thread. */
    void send(String text) {
        for (Consumer<String> each : members) {
            try {
                each.accept(text);
            } catch (RuntimeException failure) {
                LogUtil.warn("A runtime on this server failed to take a network message", failure);
            }
        }
    }

    void leave() {
        members.remove(member);
    }
}
