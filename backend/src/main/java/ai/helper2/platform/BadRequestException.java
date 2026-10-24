package ai.helper2.platform;

/**
 * Invalid input from the client: mapped to 400 problem+json. Throw it only for what the caller sent; any
 * other exception (IllegalArgumentException from a bug included) is a 500.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
