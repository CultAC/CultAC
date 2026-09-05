package com.viaversion.viaversion.api.protocol.packet;

/**
 * Marker replacement used only by the parity bootstrap when the pinned
 * Paper fixture does not ship ViaVersion.  CultPlayer keeps this optional
 * field for compile-time integration; no runtime code can reach the type
 * while ViaVersion is unavailable.
 */
public final class PacketTracker {
    private PacketTracker() {
    }
}
