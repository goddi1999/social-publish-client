package social.publish;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class PostPublisherTest {

    @Test(expected = IllegalArgumentException.class)
    public void rejectBlankText() throws PublishException {
        PostPublisher.publish("  ", 0, List.of());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectTooLongText() throws PublishException {
        PostPublisher.publish("x".repeat(281), 0, List.of());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectNegativeLikes() throws PublishException {
        PostPublisher.publish("hi", -1, List.of());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectNullComments() throws PublishException {
        PostPublisher.publish("hi", 0, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectBlankCommentText() {
        new PostPublisher.CommentData("  ", "2024-01-01T00:00:00Z");
    }

    @Test
    public void commentConvenienceConstructorLeavesIdNull() {
        var c = new PostPublisher.CommentData("nice", "2024-01-01T00:00:00Z");
        assertNull(c.id());
        assertEquals("nice", c.text());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectMissingImageFile() throws PublishException {
        PostPublisher.publishWithImage(null, "hi", "/does/not/exist.png", 0, List.of());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectNonImageFile() throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("note", ".txt");
        try {
            PostPublisher.publishWithImage(null, "hi", f.toString(), 0, List.of());
        } finally {
            java.nio.file.Files.deleteIfExists(f);
        }
    }
}
