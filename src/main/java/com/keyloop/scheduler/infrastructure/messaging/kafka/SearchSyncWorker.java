package com.keyloop.scheduler.infrastructure.messaging.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Search Sync Worker — Kafka Consumer Group A.
 *
 * <p>FR-04 Eventual Consistency: Consumes {@code AppointmentBooked} and
 * {@code AppointmentCancelled} events from Kafka and updates the Elasticsearch
 * availability index accordingly. Target sync latency: &lt; 200ms after commit.
 *
 * <p>Event flow (Section 9.1, Step 4):
 * <ol>
 *   <li>Debezium CDC tails PostgreSQL WAL and publishes outbox events to Kafka.</li>
 *   <li>This consumer processes events from the {@code appointment-events} topic.</li>
 *   <li>On {@code AppointmentBooked}: marks the slot as unavailable in ES.</li>
 *   <li>On {@code AppointmentCancelled}: marks the slot as available in ES.</li>
 * </ol>
 *
 * <p>Consumer Group ID: {@code search-sync-worker} (Consumer Group A).
 * Failure strategy: retry up to 5 times with exponential backoff.
 */
@ApplicationScoped
public class SearchSyncWorker {

    private static final Logger LOG = Logger.getLogger(SearchSyncWorker.class);

    @Inject RestClient restClient;
    @Inject ObjectMapper objectMapper;

    @Incoming("appointment-events-in")
    public CompletionStage<Void> process(Message<String> message) {
        return Uni.createFrom().item(message.getPayload())
            .flatMap(payload -> {
                try {
                    JsonNode event = objectMapper.readTree(payload);
                    // Handle Debezium envelope: payload.after for inserts
                    JsonNode after = event.path("payload").path("after");
                    if (after.isMissingNode()) {
                        // Direct payload format
                        return handleEvent(event);
                    }
                    return handleEvent(after);
                } catch (Exception e) {
                    LOG.errorf("Failed to parse event payload: %s", e.getMessage());
                    return Uni.createFrom().failure(e);
                }
            })
            .subscribeAsCompletionStage()
            .thenCompose(__ -> message.ack());
    }

    private Uni<Void> handleEvent(JsonNode event) {
        String eventType    = event.path("event_type").asText();
        String dealershipId = event.path("dealership_id").asText();
        String aggregateId  = event.path("aggregate_id").asText();

        LOG.debugf("SearchSyncWorker processing event: type=%s, appointmentId=%s", eventType, aggregateId);

        return switch (eventType) {
            case "AppointmentBooked"    -> updateSlotAvailability(dealershipId, event, false);
            case "AppointmentCancelled" -> updateSlotAvailability(dealershipId, event, true);
            default -> {
                LOG.debugf("SearchSyncWorker: ignoring unknown event type: %s", eventType);
                yield Uni.createFrom().voidItem();
            }
        };
    }

    /**
     * Updates the Elasticsearch slot document to reflect new availability.
     *
     * @param dealershipId the tenant partition key (index name suffix)
     * @param event        the outbox event JSON
     * @param available    true to restore availability (cancellation), false to mark booked
     */
    private Uni<Void> updateSlotAvailability(String dealershipId, JsonNode event, boolean available) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String indexName = "dealer_%s_slots".formatted(dealershipId);
                JsonNode payload = objectMapper.readTree(event.path("payload").asText(event.toString()));

                String technicianId = payload.path("technicianId").asText();
                String serviceBayId = payload.path("serviceBayId").asText();
                String startTime    = payload.path("startTime").asText();

                // Update by query: find the slot matching technician + bay + startTime
                String updateQuery = objectMapper.writeValueAsString(Map.of(
                    "script", Map.of(
                        "source", "ctx._source.is_available = params.available",
                        "lang", "painless",
                        "params", Map.of("available", available)
                    ),
                    "query", Map.of(
                        "bool", Map.of(
                            "filter", java.util.List.of(
                                Map.of("term", Map.of("technician_id", technicianId)),
                                Map.of("term", Map.of("service_bay_id", serviceBayId)),
                                Map.of("term", Map.of("start_time", startTime))
                            )
                        )
                    )
                ));

                Request request = new Request("POST", "/%s/_update_by_query".formatted(indexName));
                request.setJsonEntity(updateQuery);
                restClient.performRequest(request);

                LOG.debugf("ES slot updated: dealership=%s, tech=%s, bay=%s, available=%s",
                    dealershipId, technicianId, serviceBayId, available);
                emitter.complete(null);
            } catch (IOException e) {
                LOG.errorf("Failed to update ES slot: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }
}
