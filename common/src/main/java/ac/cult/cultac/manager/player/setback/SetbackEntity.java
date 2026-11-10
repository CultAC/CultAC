package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.player.CultPlayer;

/** The entity a setback moves: the player itself, or the vehicle with this entity id. */
public record SetbackEntity(int vehicleId) {
    public static final SetbackEntity PLAYER = new SetbackEntity(Integer.MIN_VALUE);

    /** The vehicle with {@code vehicleId}, or the player for {@link Integer#MIN_VALUE}. */
    public static SetbackEntity of(int vehicleId) {
        return vehicleId == Integer.MIN_VALUE ? PLAYER : new SetbackEntity(vehicleId);
    }

    public boolean isPlayer() {
        return vehicleId == Integer.MIN_VALUE;
    }

    /**
     * The entity a setback moves now. Every passenger change already written reaches the client before a setback, so
     * the server's passenger graph decides: the vehicle it seats the player on, else the player. A Java client still
     * riding a vehicle the server took it off is set back on foot.
     */
    public static SetbackEntity current(CultPlayer player) {
        boolean leftVehicle = !player.isBedrockMovement()
                && !isServerPassenger(player)
                && player.getRidingVehicleId() != Integer.MIN_VALUE;
        return leftVehicle ? PLAYER : of(vehicleOf(player));
    }

    /** The vehicle whose state a correction reads: the server's seat, else the vehicle the client rides. */
    public static int vehicleOf(CultPlayer player) {
        if (isServerPassenger(player)) {
            return player.compensatedEntities.vehicles.serverPlayerVehicle;
        }

        // The compensated graph trails the packet stream. For Bedrock it remains the
        // client-visible authority during an ordered dismount transition.
        return player.getRidingVehicleId();
    }

    private static boolean isServerPassenger(CultPlayer player) {
        Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
        return serverVehicle != null && player.compensatedEntities.vehicles.isServerPlayerPassengerOf(serverVehicle);
    }
}
