package ac.cult.cultac.network.event;

import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import java.util.ArrayList;
import java.util.List;

public final class PacketSendEvent<R> extends PacketEvent<R> {
    private final boolean insideBundle;
    private List<CultWrite> before, after;
    private List<Runnable> tasks;
    private boolean bundle;
    private boolean authored;
    private final ProtocolVersion decodedProtocol;
    // A CultAC ping already in the open flush bundle this packet joins; 0 when there is none.
    private int sharedPing;
    private CultWrite leadingPing;
    private int leadingTransaction;
    private int trackedPing;
    private int receiptProof;

    /** A proof already written after this packet by an earlier listener. */
    public int receiptProof() {
        return receiptProof;
    }

    public void receiptProof(int transaction) {
        receiptProof = transaction;
    }

    public PacketSendEvent(User user, ConnectionPhase phase, PacketType<R> type, R packet, boolean insideBundle) {
        super(user, phase, type, packet);
        if (type.direction() != PacketDirection.CLIENTBOUND)
            throw new IllegalArgumentException("Send event requires clientbound packet");
        this.insideBundle = insideBundle;
        this.decodedProtocol = user == null ? null : user.getCultConnection().getObservedProtocol();
    }

    public ProtocolVersion getDecodedProtocol() {
        return decodedProtocol;
    }

    public List<CultWrite> getWritesBeforeSend() {
        if (before == null) before = new ArrayList<>(2);
        return before;
    }

    public List<CultWrite> getWritesAfterSend() {
        if (after == null) after = new ArrayList<>(2);
        return after;
    }

    public List<Runnable> getTasksAfterSend() {
        if (tasks == null) tasks = new ArrayList<>(2);
        return tasks;
    }

    public boolean isInsideBundle() {
        return insideBundle;
    }

    public void bundle() {
        bundle = true;
    }

    public boolean isBundleRequested() {
        return bundle;
    }

    /** CultAC wrote this packet itself; the server never sent it. */
    public void authored() {
        authored = true;
    }

    public boolean isAuthored() {
        return authored;
    }

    /** The encoder found a CultAC ping, transaction {@code transaction}, in the flush bundle this packet joins. */
    public void sharedPing(int transaction) {
        sharedPing = transaction;
    }

    /**
     * The transaction of a CultAC ping in the same physical bundle as this packet, or 0 when a listener must lead
     * the packet with its own. The client handles a bundle in one task and answers its pings inside it, so any
     * ping in the bundle proves every packet in it.
     */
    public int flushPing() {
        return leadingPing != null ? leadingTransaction : sharedPing;
    }

    /** Writes {@code ping} directly before this packet; the encoder opens the flush bundle before it. */
    public void leadWith(CultWrite ping, int transaction) {
        if (leadingPing != null) throw new IllegalStateException("Packet already has a leading ping");
        leadingPing = ping;
        leadingTransaction = transaction;
    }

    public CultWrite leadingPing() {
        return leadingPing;
    }

    /** This packet is the CultAC ping of transaction {@code transaction}. */
    public void trackedPing(int transaction) {
        trackedPing = transaction;
    }

    public int trackedPing() {
        return trackedPing;
    }

    public List<CultWrite> writesBefore() {
        return before == null ? List.of() : before;
    }

    public List<CultWrite> writesAfter() {
        return after == null ? List.of() : after;
    }

    public List<Runnable> tasksAfter() {
        return tasks == null ? List.of() : tasks;
    }

    @Override
    public void discardChanges() {
        super.discardChanges();
        if (before != null) before.clear();
        if (after != null) after.clear();
        if (tasks != null) tasks.clear();
        bundle = false;
        leadingPing = null;
        receiptProof = 0;
        // trackedPing stays: the packet is still forwarded, and its ping was marked sent.
    }
}
