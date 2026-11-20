package social.publish;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
    private static final String ENDPOINT = "https://twitter-web-inky.vercel.app/api/post";

    /** Supabase-Projekt fuer Bilder. Der Publishable-Key ist oeffentlich — Policies erlauben nur Upload in post-images. */
    private static final String SUPABASE_URL = "https://ocuhrvjuonijfnhwmgjb.supabase.co";
    private static final String SUPABASE_BUCKET = "post-images";
    private static final String SUPABASE_ANON_KEY = "sb_publishable_vmuHtUQakDWQ34gsr6gZfw_RwOcKvzw";
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

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
     * Feld im JSON einfach weg.
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
        return publish(postId, text, avatarId, null, likeCount, comments);
    }

    /**
     * Wie publish(postId, text, avatarId, ...), zusaetzlich mit optionalem Bild: Gibt es einen
     * imagePath, laedt die Library die Datei selbst in den Supabase Storage und sendet nur die
     * oeffentliche URL mit. null oder leer = kein Bild (Post wird ganz normal gesendet).
     * ACHTUNG: blockiert den aufrufenden Thread — in JavaFX besser publishAsync(...) verwenden.
     *
     * @param imagePath optionaler Pfad zu einer PNG/JPG-Datei (max. 5 MB), z. B. post.getImagePath()
     * @throws IllegalArgumentException wenn die Bilddatei fehlt, zu gross ist oder weder PNG noch JPG ist
     * @throws PublishException         bei Netzwerkfehlern, Upload-Fehlern oder Fehler-Status des Servers
     */
    public static PublishResult publish(String postId, String text, String avatarId, String imagePath,
                                        int likeCount, List<CommentData> comments)
            throws PublishException {
        validate(text, likeCount, comments);
        Path image = validateImage(imagePath);
        String resolvedPostId = (postId == null || postId.isBlank())
                ? UUID.randomUUID().toString()
                : postId;
        String imageUrl = image == null ? null : uploadImage(resolvedPostId, image);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        toJson(resolvedPostId, text, avatarId, imageUrl, likeCount, comments)))
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
     * Der einfache Weg mit Bild: nur imagePath mitgeben, den Rest (Upload) macht die Library.
     * Blockiert den aufrufenden Thread — in JavaFX besser publishAsyncWithImage(...) verwenden.
     */
    public static PublishResult publishWithImage(String postId, String text, String imagePath,
                                                 int likeCount, List<CommentData> comments)
            throws PublishException {
        return publish(postId, text, null, imagePath, likeCount, comments);
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
        return publishAsync(postId, text, avatarId, null, likeCount, comments);
    }

    /**
     * Wie publish(postId, text, avatarId, imagePath, ...), aber asynchron — Upload und Senden
     * laufen im Hintergrund. Fehler kommen als CompletionException (Cause: PublishException oder
     * IllegalArgumentException bei ungueltigem Bild).
     */
    public static CompletableFuture<PublishResult> publishAsync(
            String postId, String text, String avatarId, String imagePath,
            int likeCount, List<CommentData> comments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return publish(postId, text, avatarId, imagePath, likeCount, comments);
            } catch (PublishException e) {
                throw new CompletionException(e);
            }
        });
    }

    /** Async-Variante von publishWithImage(...) — der einfache Weg fuer JavaFX. */
    public static CompletableFuture<PublishResult> publishAsyncWithImage(
            String postId, String text, String imagePath, int likeCount, List<CommentData> comments) {
        return publishAsync(postId, text, null, imagePath, likeCount, comments);
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

    /** @return Pfad der Bilddatei oder null, wenn kein Bild angegeben ist */
    private static Path validateImage(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) return null;
        Path path = Path.of(imagePath);
        if (!Files.isRegularFile(path))
            throw new IllegalArgumentException("image file not found: " + imagePath);
        if (contentType(path) == null)
            throw new IllegalArgumentException("image must be a PNG or JPG file: " + imagePath);
        try {
            if (Files.size(path) == 0)
                throw new IllegalArgumentException("image file is empty: " + imagePath);
            if (Files.size(path) > MAX_IMAGE_BYTES)
                throw new IllegalArgumentException("image must not exceed 5 MB: " + imagePath);
        } catch (IOException e) {
            throw new IllegalArgumentException("image file not readable: " + imagePath, e);
        }
        return path;
    }

    private static String contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return null;
    }

    /** Laedt das Bild nach Supabase Storage (Objektname = postId, Republish ersetzt das Bild). */
    private static String uploadImage(String postId, Path image) throws PublishException {
        String type = contentType(image);
        String objectName = postId + (type.equals("image/png") ? ".png" : ".jpg");
        String base = SUPABASE_URL + "/storage/v1/object/";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + SUPABASE_BUCKET + "/" + objectName))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + SUPABASE_ANON_KEY)
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Content-Type", type)
                    .header("x-upsert", "true")
                    .POST(HttpRequest.BodyPublishers.ofFile(image))
                    .build();
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new PublishException("Image upload failed with status " + status
                        + ": " + response.body());
            }
            return base + "public/" + SUPABASE_BUCKET + "/" + objectName;
        } catch (IOException e) {
            throw new PublishException("Network error — image upload failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Image upload was interrupted", e);
        }
    }

    /** Baut das feste API-JSON. Die Feldnamen hier sind der Server-Vertrag. */
    private static String toJson(String postId, String text, String avatarId, String imageUrl,
                                 int likeCount, List<CommentData> comments) {
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
        // imageUrl ist optional — nur vorhanden, wenn die Library ein Bild hochgeladen hat
        if (imageUrl != null && !imageUrl.isBlank()) {
            sb.append(",\"imageUrl\":\"").append(escape(imageUrl)).append("\"");
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
