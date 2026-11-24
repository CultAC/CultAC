package ac.cult.cultac.utils.latency;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;

/**
 * The riding links as the server has sent them, ahead of the passenger graph the client holds once it answers them.
 * Each set_passengers is applied the way ClientPacketListener#handleSetEntityPassengersPacket applies it: the vehicle
 * ejects its passengers, and each listed passenger leaves any other vehicle to ride it (Entity#startRiding).
 */
final class SentRidingGraph {
    private final Int2IntOpenHashMap vehicleOf = new Int2IntOpenHashMap();
    private final Int2ObjectOpenHashMap<int[]> passengersOf = new Int2ObjectOpenHashMap<>();
    // The transaction whose answer proves the client holds the last change sent to what each entity rides;
    // Integer.MAX_VALUE until provenAt names it.
    private final Int2IntOpenHashMap changeProvenAt = new Int2IntOpenHashMap();
    private final IntArrayList unproven = new IntArrayList();
    // As changeProvenAt, for every entity: the last clear.
    private int clearProvenAt = Integer.MIN_VALUE;

    SentRidingGraph() {
        changeProvenAt.defaultReturnValue(Integer.MIN_VALUE);
    }

    void setPassengers(int vehicle, int[] passengers) {
        eject(vehicle);
        int[] kept = new int[passengers.length];
        int count = 0;
        for (int passenger : passengers) {
            // Entity#startRiding refuses the vehicle itself and a vehicle that rides the passenger.
            if (passenger == vehicle || ridesIndirectly(vehicle, passenger)) continue;
            if (vehicleOf.containsKey(passenger)) leave(passenger, vehicleOf.get(passenger));
            vehicleOf.put(passenger, vehicle);
            changed(passenger);
            kept[count++] = passenger;
        }
        if (count > 0) passengersOf.put(vehicle, count == kept.length ? kept : Arrays.copyOf(kept, count));
    }

    /** ClientLevel#removeEntity: Entity#setRemoved stops the entity riding and ejects its passengers. */
    void remove(int entity) {
        if (vehicleOf.containsKey(entity)) leave(entity, vehicleOf.get(entity));
        eject(entity);
        changed(entity);
    }

    void clear() {
        vehicleOf.clear();
        passengersOf.clear();
        changeProvenAt.clear();
        unproven.clear();
        clearProvenAt = Integer.MAX_VALUE;
    }

    /**
     * The client holds every change recorded since the last call once it answers {@code transaction}. Changes the
     * client already held when it answered {@code answered} are forgotten.
     */
    void provenAt(int transaction, int answered) {
        for (int i = 0; i < unproven.size(); i++) changeProvenAt.put(unproven.getInt(i), transaction);
        unproven.clear();
        if (clearProvenAt == Integer.MAX_VALUE) clearProvenAt = transaction;
        if (changeProvenAt.size() > 256)
            changeProvenAt.int2IntEntrySet().removeIf(change -> change.getIntValue() <= answered);
    }

    /**
     * Whether the client, having answered {@code answered}, may not yet hold the last change sent to what
     * {@code entity} rides: between that change's packet and its proof it rides either what it rode or what it rides.
     */
    boolean changeUnanswered(int entity, int answered) {
        return clearProvenAt > answered || changeProvenAt.get(entity) > answered;
    }

    private void changed(int entity) {
        if (changeProvenAt.put(entity, Integer.MAX_VALUE) != Integer.MAX_VALUE) unproven.add(entity);
    }

    /** Whether {@code passenger} rides {@code vehicle} directly. */
    boolean ridesDirectly(int passenger, int vehicle) {
        return vehicleOf.containsKey(passenger) && vehicleOf.get(passenger) == vehicle;
    }

    /** Whether {@code entity} rides another entity. */
    boolean rides(int entity) {
        return vehicleOf.containsKey(entity);
    }

    /** Entity#getRootVehicle: the bottom of {@code entity}'s riding chain, {@code entity} itself when it rides nothing. */
    int rootOf(int entity) {
        int current = entity;
        // A cycle cannot form, as every link is added through ridesIndirectly; the bound only guards corrupt input.
        for (int hops = 0; vehicleOf.containsKey(current) && hops <= vehicleOf.size(); hops++) {
            current = vehicleOf.get(current);
        }
        return current;
    }

    private boolean ridesIndirectly(int entity, int vehicle) {
        int current = entity;
        // A cycle cannot form, as every link is added through this check; the bound only guards corrupt input.
        for (int hops = 0; vehicleOf.containsKey(current) && hops <= vehicleOf.size(); hops++) {
            current = vehicleOf.get(current);
            if (current == vehicle) return true;
        }
        return false;
    }

    private void eject(int vehicle) {
        int[] old = passengersOf.remove(vehicle);
        if (old == null) return;
        for (int passenger : old) {
            if (vehicleOf.containsKey(passenger) && vehicleOf.get(passenger) == vehicle) vehicleOf.remove(passenger);
            changed(passenger);
        }
    }

    private void leave(int passenger, int vehicle) {
        vehicleOf.remove(passenger);
        int[] old = passengersOf.get(vehicle);
        if (old == null) return;
        int[] next = new int[old.length];
        int count = 0;
        for (int id : old) if (id != passenger) next[count++] = id;
        if (count == 0) passengersOf.remove(vehicle);
        else passengersOf.put(vehicle, Arrays.copyOf(next, count));
    }
}
