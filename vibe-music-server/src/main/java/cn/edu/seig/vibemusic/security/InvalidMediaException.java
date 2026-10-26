package cn.edu.seig.vibemusic.security;

/**
 * A client-visible media validation failure. The message must not contain parser internals.
 */
public class InvalidMediaException extends RuntimeException {
    public InvalidMediaException(String message) {
        super(message);
    }

    public InvalidMediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
