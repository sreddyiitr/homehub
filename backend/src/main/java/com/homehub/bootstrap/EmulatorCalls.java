package com.homehub.bootstrap;

import java.util.function.Supplier;
import software.amazon.awssdk.core.exception.SdkServiceException;

/**
 * Wraps bootstrap AWS calls so an emulator "not supported" response fails
 * loudly with the service + operation named (R-INF-03, R-OPS-04), instead of
 * surfacing as a generic SDK error or being swallowed.
 *
 * <p>Verified 2026-11-11: an unimplemented operation returns HTTP 501
 * {@code NotImplemented} ("operation X is scaffolded but not implemented").
 * CLAUDE.md originally said 503, so both are treated as "not supported".
 */
final class EmulatorCalls {

    private EmulatorCalls() {
    }

    static boolean isNotSupported(int status) {
        return status == 501 || status == 503;
    }

    static <T> T call(String service, String operation, Supplier<T> body) {
        try {
            return body.get();
        } catch (SdkServiceException e) {
            if (isNotSupported(e.statusCode())) {
                throw new EmulatorUnsupportedOperationException(service, operation, e);
            }
            throw e;
        }
    }

    static final class EmulatorUnsupportedOperationException extends RuntimeException {
        EmulatorUnsupportedOperationException(String service, String operation, SdkServiceException cause) {
            super("Emulator returned HTTP " + cause.statusCode() + " for " + service + ":" + operation
                    + " — operation not implemented/unverified in the emulator. Stop and propose a workaround.", cause);
        }
    }
}
