package com.keyloop.scheduler.infrastructure.messaging.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

/**
 * Notification Worker — Kafka Consumer Group B.
 *
 * <p>FR-05: Multi-Channel Alerts — Consumes appointment events and dispatches
 * notifications via Email, SMS, FCM, and APNs channels.
 *
 * <p>Fully decoupled from the transactional booking path. Downstream provider
 * latency or outages do NOT block or fail booking operations (Section 4.1).
 *
 * <p>Consumer Group ID: {@code notification-worker} (Consumer Group B).
 * SLO: 99.99% delivery success, async latency &lt; 2 seconds.
 *
 * <p>Note: Actual external provider integrations (Twilio, SendGrid, Firebase)
 * are stubbed here. In production, wire in dedicated notification microservice clients.
 */
@ApplicationScoped
public class NotificationWorker {

    private static final Logger LOG = Logger.getLogger(NotificationWorker.class);

    @Inject ObjectMapper objectMapper;

    @Incoming("notification-events-in")
    public CompletionStage<Void> process(Message<String> message) {
        String payload = message.getPayload();

        try {
            JsonNode event = objectMapper.readTree(payload);
            // Handle Debezium envelope
            JsonNode after = event.path("payload").path("after");
            JsonNode data  = after.isMissingNode() ? event : after;

            String eventType    = data.path("event_type").asText();
            String aggregateId  = data.path("aggregate_id").asText();

            LOG.debugf("NotificationWorker processing: type=%s, appointmentId=%s", eventType, aggregateId);

            switch (eventType) {
                case "AppointmentBooked" -> dispatchBookingConfirmation(data);
                case "AppointmentCancelled" -> dispatchCancellationNotification(data);
                default -> LOG.debugf("NotificationWorker: ignoring event type: %s", eventType);
            }
        } catch (Exception e) {
            LOG.errorf("Notification processing failed for payload: %s | Error: %s", payload, e.getMessage());
            // Nack to trigger retry (failure-strategy=retry in application.properties)
            return message.nack(e);
        }

        return message.ack();
    }

    /**
     * Dispatches booking confirmation via Email + SMS + push notification.
     *
     * <p>In production: call SendGrid API, Twilio API, and FCM/APNs gateway.
     * Circuit breakers protect against external provider outages.
     */
    private void dispatchBookingConfirmation(JsonNode eventData) {
        String appointmentId = eventData.path("aggregate_id").asText();
        LOG.infof("Dispatching booking confirmation for appointment: %s via [Email, SMS, FCM, APNs]", appointmentId);
        // TODO: Wire in SendGrid, Twilio, Firebase notification clients
    }

    /**
     * Dispatches cancellation notification to the customer.
     */
    private void dispatchCancellationNotification(JsonNode eventData) {
        String appointmentId = eventData.path("aggregate_id").asText();
        LOG.infof("Dispatching cancellation notification for appointment: %s", appointmentId);
        // TODO: Wire in notification service clients
    }
}
