package io.jafra.analyzer.storage;

/** A path component cannot be used as a directory or file name. */
public class InvalidIdentityException extends IllegalArgumentException {
    public InvalidIdentityException(String message) {
        super(message);
    }
}
