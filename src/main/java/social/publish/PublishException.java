package social.publish;

/**
 * Fachliche Exception fuer alle Fehler beim Veroeffentlichen
 * (Netzwerkfehler, Server nicht erreichbar, Fehler-Status der API).
 */
public class PublishException extends Exception {

    public PublishException(String message) {
        super(message);
    }

    public PublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
