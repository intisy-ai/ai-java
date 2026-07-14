package io.github.intisy.ai.examples;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that a file-backed store defaults to the real {@code JsonlNotifier} and that a routing
 * fallback writes one well-shaped JSONL line ({@code message}/{@code level}/{@code at}) to the
 * notifications file.
 */
class NotifierIntegrationTest {

    @Test
    void fileBackedStoreWritesAJsonlNotificationLine() throws IOException {
        NotifierDemo.Result result = NotifierDemo.execute();

        assertEquals("JsonlNotifier", result.notifierType,
                "a file-backed AiJava should default to the real JsonlNotifier");

        String line = result.jsonlContent.trim();
        assertTrue(line.contains("\"message\":"), "JSONL line should carry a message field: " + line);
        assertTrue(line.contains("\"level\":"), "JSONL line should carry a level field: " + line);
        assertTrue(line.contains("\"at\":"), "JSONL line should carry an at timestamp: " + line);
        assertTrue(line.contains("rate-limited"),
                "the fallback notice should describe the rate-limit -> fallback event: " + line);
    }
}
