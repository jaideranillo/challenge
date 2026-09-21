package com.cobre.challenge.application.port.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.application.port.out.webhook.dto.WebhookRequest;
import com.cobre.challenge.application.port.out.webhook.dto.WebhookResponse;
import com.cobre.challenge.domain.policy.AttemptOutcome;
import com.cobre.challenge.domain.policy.ResponseClassifier;
import com.cobre.challenge.domain.policy.TransportFailure;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebhookClientPortTest {

    @Test
    void webhookRequestDefensivelyCopiesHeaders() {
        WebhookRequest request = new WebhookRequest(
                "https://example.com/hook", "{}", Map.of("X-Cobre-Delivery-Id", "d1"));

        assertThat(request.headers()).containsEntry("X-Cobre-Delivery-Id", "d1");
        assertThatThrownBy(() -> request.headers().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void webhookResponseFeedsResponseClassifierDirectly() {
        WebhookResponse response = new WebhookResponse(
                200, TransportFailure.NONE, 120, Optional.empty(), Optional.empty());

        assertThat(ResponseClassifier.classify(response.statusCode(), response.failure()))
                .isEqualTo(AttemptOutcome.SUCCESS);
    }

    @Test
    void webhookResponseRejectsNullFailure() {
        assertThatThrownBy(() -> new WebhookResponse(0, null, 0, Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }
}
