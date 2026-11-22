package com.velocitypowered.proxy.protocol.util;

import com.velocitypowered.proxy.util.except.QuietDecoderException;

/** Stands in for Velocity's class of the same name with its debug flag off: every failed check throws one instance. */
public final class NettyPreconditions {
    public static final QuietDecoderException BAD = new QuietDecoderException("Invalid packet");

    private NettyPreconditions() {}

    public static void checkFrame(boolean expression, String message, Object first, Object second) {
        if (!expression) throw BAD;
    }
}
