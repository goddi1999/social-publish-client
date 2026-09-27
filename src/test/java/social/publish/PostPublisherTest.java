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
}
