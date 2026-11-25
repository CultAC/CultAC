package com.velocitypowered.proxy.network.limiter;

/** Stands in for Velocity's limiter interface of the same name, with its one method. */
public interface PacketLimiter {
    boolean account(int bytes);
}
