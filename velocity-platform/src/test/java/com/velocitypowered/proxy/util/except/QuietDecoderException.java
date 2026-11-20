package com.velocitypowered.proxy.util.except;

import io.netty.handler.codec.DecoderException;

/** Stands in for Velocity's exception of the same name: a decoder failure without a stack trace. */
public class QuietDecoderException extends DecoderException {
    public QuietDecoderException(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
