package com.noxcrew.packet;

/** A function that unregisters a packet handler. */
@FunctionalInterface
public interface PacketHandlerUnregisterer {
    void invoke();

    default void unregister() {
        invoke();
    }
}
