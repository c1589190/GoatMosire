package com.goatmosire.map;

/**
 * Exception thrown by {@link MapStore} on persistence failures.
 */
public class MapStoreException extends RuntimeException {
    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message
     */
    public MapStoreException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause (which is saved for later retrieval by the {@link #getCause()} method)
     */
    public MapStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
