package com.keyloop.scheduler.infrastructure.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.application.port.in.query.AvailabilityQuery;
import com.keyloop.scheduler.application.port.out.AvailabilitySearchPort;
import com.keyloop.scheduler.infrastructure.web.dto.response.AvailableSlotDto;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Elasticsearch availability search adapter — CQRS Read Path.
 *
 * <p>Queries the dealer-partitioned availability index for open slots matching:
 * <ul>
 *   <li>Dealership partition (index: {@code dealer_{dealershipId}_slots})</li>
 *   <li>Date range filter</li>
 *   <li>Skill level filter: technician.skill_level >= required_skill_level</li>
 *   <li>Status filter: available = true</li>
 * </ul>
 *
 * <p>Target latency: 10–50ms (Section 4.3). Multi-tenant isolation via
 * per-dealership index eliminates Noisy Neighbor effect (Section 7.1).
 */
@ApplicationScoped
public class ElasticsearchAvailabilityAdapter implements AvailabilitySearchPort {

    private static final Logger LOG = Logger.getLogger(ElasticsearchAvailabilityAdapter.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int MAX_RESULTS = 100;

    @Inject RestClient restClient;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "scheduler.elasticsearch.index-prefix", defaultValue = "dealer")
    String indexPrefix;

    @Override
    public Uni<List<AvailableSlotDto>> findAvailableSlots(
        AvailabilityQuery query, int durationMinutes, int requiredSkillLevel) {

        return Uni.createFrom().emitter(emitter -> {
            try {
                String indexName = "%s_%s_slots".formatted(indexPrefix, query.dealershipId());
                String queryBody = buildBoolQuery(query, requiredSkillLevel);

                Request request = new Request("POST", "/%s/_search".formatted(indexName));
                request.setJsonEntity(queryBody);

                var response = restClient.performRequest(request);
                var responseBody = response.getEntity().getContent().readAllBytes();
                List<AvailableSlotDto> slots = parseHits(new String(responseBody), durationMinutes);

                LOG.debugf("ES query returned %d slots for dealership %s", slots.size(), query.dealershipId());
                emitter.complete(slots);
            } catch (IOException e) {
                LOG.errorf("Elasticsearch query failed: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }

    /**
     * Builds a multi-dimensional bool query for slot availability.
     * Filters by date range, skill level, and available status.
     */
    private String buildBoolQuery(AvailabilityQuery query, int requiredSkillLevel) throws IOException {
        String startDatetime = LocalDateTime.of(query.startDate(), java.time.LocalTime.MIDNIGHT)
            .format(ISO_FMT);
        String endDatetime = LocalDateTime.of(query.endDate(), java.time.LocalTime.MAX)
            .format(ISO_FMT);

        var esQuery = Map.of(
            "size", MAX_RESULTS,
            "sort", List.of(Map.of("start_time", Map.of("order", "asc"))),
            "query", Map.of(
                "bool", Map.of(
                    "filter", List.of(
                        // Filter 1: Available slots only
                        Map.of("term", Map.of("is_available", true)),
                        // Filter 2: Date range
                        Map.of("range", Map.of("start_time", Map.of(
                            "gte", startDatetime,
                            "lte", endDatetime
                        ))),
                        // Filter 3: Skill level — technician skill >= required
                        Map.of("range", Map.of("technician_skill_level", Map.of(
                            "gte", requiredSkillLevel
                        )))
                    )
                )
            )
        );

        return objectMapper.writeValueAsString(esQuery);
    }

    @SuppressWarnings("unchecked")
    private List<AvailableSlotDto> parseHits(String responseBody, int durationMinutes) throws IOException {
        Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> hits  = (Map<String, Object>) parsed.get("hits");
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hits.get("hits");

        List<AvailableSlotDto> slots = new ArrayList<>();
        for (Map<String, Object> hit : hitList) {
            Map<String, Object> source = (Map<String, Object>) hit.get("_source");
            var startTime = java.time.OffsetDateTime.parse(
                (String) source.get("start_time"), DateTimeFormatter.ISO_DATE_TIME);
            var endTime = startTime.plusMinutes(durationMinutes);

            slots.add(new AvailableSlotDto(
                startTime, endTime,
                UUID.fromString((String) source.get("technician_id")),
                UUID.fromString((String) source.get("service_bay_id"))
            ));
        }
        return slots;
    }
}
