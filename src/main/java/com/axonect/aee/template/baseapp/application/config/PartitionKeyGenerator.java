/*
package com.axonect.aee.template.baseapp.application.config;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

*/
/**
 * Generates a stable, well-distributed partition key for Kafka messages.
 *
 * <p>Strategy: CRC32( eventType + ":" + entityKey ) → integer string.
 * This guarantees:
 * <ul>
 *   <li>Same event type + entity always lands on the same partition (ordering guarantee).</li>
 *   <li>Different event types for the same entity can be spread across partitions.</li>
 *   <li>No hot-spotting — CRC32 distributes far better than plain username hashing.</li>
 * </ul>
 *
 * The key is written as a Kafka header ({@code X-Partition-Key}) so it is
 * visible to monitoring/tracing tools without polluting the message key.
 *//*

@Component
public class PartitionKeyGenerator {

    */
/** Header name written on every outbound ProducerRecord. *//*

    public static final String PARTITION_KEY_HEADER = "X-Partition-Key";

    */
/**
     * Builds a stable partition key string.
     *
     * @param eventType  logical event name, e.g. "USER_CREATED"
     * @param entityKey  primary business key, e.g. username or ID
     * @return           10-digit zero-padded CRC32 value as a string
     *//*

    public String generate(String eventType, String entityKey) {
        String raw = normalize(eventType) + ":" + normalize(entityKey);

        CRC32 crc = new CRC32();
        crc.update(raw.getBytes(StandardCharsets.UTF_8));

        // Format as fixed-width string so it sorts/reads consistently in logs
        return String.format("%010d", crc.getValue());
    }

    */
/**
     * Returns the key as a UTF-8 byte array ready to be used in a Kafka header.
     *//*

    public byte[] generateBytes(String eventType, String entityKey) {
        return generate(eventType, entityKey).getBytes(StandardCharsets.UTF_8);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String normalize(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase();
    }
}*/
