package ac.cult.cultac.protocol.packet.clientbound;

/**
 * Turns the local player toward a point, measured from its feet or its eyes. With {@code atEntity}, the client aims at
 * its own copy of {@code entityId} (its feet or eyes by {@code toEyes}) and uses the point only when it has none.
 */
public record ClientboundPlayerLookAt(
        boolean fromEyes, double x, double y, double z, boolean atEntity, int entityId, boolean toEyes)
        implements ClientboundPacket {}
