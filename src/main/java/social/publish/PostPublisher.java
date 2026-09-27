package social.publish;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Client zum Veroeffentlichen eines Posts auf der Kurs-Website.
 * Sendet den Post (Text, LikeCount, Kommentare) als JSON an die Server-API.
 */
public final class PostPublisher {

    /** Ein Kommentar, wie er an die API gesendet wird. id ist optional (null = Server vergibt). */
    public record CommentData(String id, String text, String timestamp) {
        public CommentData {
            if (text == null || text.isBlank())
                throw new IllegalArgumentException("comment text must not be null or blank");
        }
        /** Convenience-Konstruktor ohne id (Server vergibt). */
        public CommentData(String text, String timestamp) {
            this(null, text, timestamp);
        }
    }

    /** Ergebnis eines erfolgreichen Publish-Aufrufs. postId fuer Republish mit gleichem Author. */
    public record PublishResult(boolean success, int statusCode, String message, String postId) {}

    /** URL der Kurs-API (Vercel Function) — vor Release anpassen! */
    private static final String ENDPOINT = "https://DEIN-PROJEKT.vercel.app/api/post";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private PostPublisher() {}

    /**
     * Sendet den Post an die Website (einfacher Aufruf — Library generiert postId).
     * ACHTUNG: blockiert den aufrufenden Thread — in JavaFX besser publishAsync(...) verwenden.
     *
     * @param text      Post-Text (nicht leer, max. 280 Zeichen)
     * @param likeCount Like-Zaehler (>= 0)
     * @param comments  Kommentare in chronologischer Reihenfolge (darf leer sein)
     * @return Ergebnis mit HTTP-Statuscode, Server-Antwort und postId
     * @throws PublishException bei Netzwerkfehlern oder Fehler-Status des Servers
     */
    public static PublishResult publish(String text, int likeCount, List<CommentData> comments)
            throws PublishException {
        return publish(null, text, likeCount, comments);
    }

    /**
     * Sendet den Post mit bestehender postId (fuer Republish — gleicher Author auf der Website).
     * ACHTUNG: blockiert den aufrufenden Thread — in JavaFX besser publishAsync(...) verwenden.
     *
     * @param postId    postId aus vorherigem PublishResult (null = Library generiert neu)
     * @param text      Post-Text (nicht leer, max. 280 Zeichen)
     * @param likeCount Like-Zaehler (>= 0)
     * @param comments  Kommentare in chronologischer Reihenfolge (darf leer sein)
     * @return Ergebnis mit HTTP-Statuscode, Server-Antwort und postId
     * @throws PublishException bei Netzwerkfehlern oder Fehler-Status des Servers
     */
    public static PublishResult publish(String postId, String text, int likeCount, List<CommentData> comments)
            throws PublishException {
        return publish(postId, text, null, likeCount, comments);
    }

    /**
     * Sendet den Post mit optionaler avatarId (z. B. "avatar_female_german_01") — bestimmt das
     * Avatar-Bild auf der Website. avatarId ist komplett optional: null oder leer lässt das
     * Feld im JSON einfach weg (siehe toJson(...) unten — es wird als letztes Feld angehängt).
     * ACHTUNG: blockiert den aufrufenden Thread — in JavaFX besser publishAsync(...) verwenden.
     *
     * @param postId    postId aus vorherigem PublishResult (null = Library generiert neu)
     * @param text      Post-Text (nicht leer, max. 280 Zeichen)
     * @param avatarId  optionale Avatar-Kennung (null oder leer = kein Avatar)
     * @param likeCount Like-Zaehler (>= 0)
     * @param comments  Kommentare in chronologischer Reihenfolge (darf leer sein)
     * @return Ergebnis mit HTTP-Statuscode, Server-Antwort und postId
     * @throws PublishException bei Netzwerkfehlern oder Fehler-Status des Servers
     */
    public static PublishResult publish(String postId, String text, String avatarId, int likeCount, List<CommentData> comments)
            throws PublishException {
        validate(text, likeCount, comments);
        String resolvedPostId = (postId == null || postId.isBlank())
                ? UUID.randomUUID().toString()
                : postId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(resolvedPostId, text, avatarId, likeCount, comments)))
                .build();
        try {
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new PublishResult(true, status, response.body(), resolvedPostId);
            }
            throw new PublishException("Server responded with status " + status
                    + ": " + response.body());
        } catch (IOException e) {
            throw new PublishException("Network error — server not reachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Publish was interrupted", e);
        }
    }

    /**
     * Wie publish(...), aber asynchron — blockiert die JavaFX-UI nicht.
     * Fehler kommen als CompletionException mit PublishException als Cause.
     */
    public static CompletableFuture<PublishResult> publishAsync(
            String text, int likeCount, List<CommentData> comments) {
        return publishAsync(null, text, likeCount, comments);
    }

    /**
     * Wie publish(postId, ...), aber asynchron — blockiert die JavaFX-UI nicht.
     * Fehler kommen als CompletionException mit PublishException als Cause.
     */
    public static CompletableFuture<PublishResult> publishAsync(
            String postId, String text, int likeCount, List<CommentData> comments) {
        return publishAsync(postId, text, null, likeCount, comments);
    }

    /**
     * Wie publish(postId, text, avatarId, ...), aber asynchron — blockiert die JavaFX-UI nicht.
     * Fehler kommen als CompletionException mit PublishException als Cause.
     */
    public static CompletableFuture<PublishResult> publishAsync(
            String postId, String text, String avatarId, int likeCount, List<CommentData> comments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return publish(postId, text, avatarId, likeCount, comments);
            } catch (PublishException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static void validate(String text, int likeCount, List<CommentData> comments) {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("post text must not be null or blank");
        if (text.length() > 280)
            throw new IllegalArgumentException("post text must not exceed 280 characters");
        if (likeCount < 0)
            throw new IllegalArgumentException("likeCount must not be negative");
        if (comments == null)
            throw new IllegalArgumentException("comments must not be null (use an empty list)");
    }

    /** Baut das feste API-JSON. Die Feldnamen hier sind der Server-Vertrag. */
    private static String toJson(String postId, String text, String avatarId, int likeCount, List<CommentData> comments) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"post\":{\"id\":\"").append(escape(postId))
          .append("\",\"text\":\"").append(escape(text))
          .append("\",\"likeCount\":").append(likeCount)
          .append(",\"comments\":[");
        for (int i = 0; i < comments.size(); i++) {
            CommentData c = comments.get(i);
            if (i > 0) sb.append(',');
            String commentId = (c.id() == null || c.id().isBlank())
                    ? UUID.randomUUID().toString()
                    : c.id();
            sb.append("{\"id\":\"").append(escape(commentId))
              .append("\",\"text\":\"").append(escape(c.text()))
              .append("\"");
            if (c.timestamp() != null && !c.timestamp().isBlank()) {
                sb.append(",\"timestamp\":\"").append(escape(c.timestamp())).append("\"");
            }
            sb.append("}");
        }
        sb.append("]");
        // avatarId ist optional — kommt nur ans Ende des post-Objekts, wenn vorhanden
        if (avatarId != null && !avatarId.isBlank()) {
            sb.append(",\"avatarId\":\"").append(escape(avatarId)).append("\"");
        }
        sb.append("}}");
        return sb.toString();
    }

    /** Escaped Sonderzeichen fuer JSON-Strings. */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
}
