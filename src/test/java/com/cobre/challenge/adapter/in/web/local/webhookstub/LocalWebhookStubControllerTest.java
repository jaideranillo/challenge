package com.cobre.challenge.adapter.in.web.local.webhookstub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

class LocalWebhookStubControllerTest {

    // addFilters = false: no SecurityConfig exists yet (out of scope for this task,
    // see ADR-007 in a future feature), so the security auto-config filter chain
    // is disabled here rather than testing against an authorization posture this
    // task does not own.
    @WebMvcTest(controllers = LocalWebhookStubController.class)
    @AutoConfigureMockMvc(addFilters = false)
    @Import(LocalWebhookStubRecorder.class)
    @ActiveProfiles("local")
    static class WithLocalProfile {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        LocalWebhookStubRecorder recorder;

        @BeforeEach
        void resetState() {
            recorder.clearRecords();
            recorder.reset();
        }

        @Test
        void recordsReceivedRequestAndReturns200ByDefault() throws Exception {
            mockMvc.perform(post("/local/webhook-stub/receive")
                            .header("X-Webhook-Signature", "abc123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"event\":\"test\"}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/local/webhook-stub/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].method").value("POST"))
                    .andExpect(jsonPath("$[0].body").value("{\"event\":\"test\"}"))
                    .andExpect(jsonPath("$[0].headers['X-Webhook-Signature'][0]").value("abc123"));
        }

        @Test
        void forcedStatusIsReturnedForSubsequentRequests() throws Exception {
            for (int code : new int[] {200, 400, 429, 500}) {
                mockMvc.perform(post("/local/webhook-stub/control/status/" + code))
                        .andExpect(status().isNoContent());

                mockMvc.perform(post("/local/webhook-stub/receive"))
                        .andExpect(status().is(code));
            }
        }

        @Test
        void forcedHangBlocksResponseForConfiguredDuration() throws Exception {
            mockMvc.perform(post("/local/webhook-stub/control/hang").param("millis", "300"))
                    .andExpect(status().isNoContent());

            long start = System.nanoTime();
            mockMvc.perform(post("/local/webhook-stub/receive")).andExpect(status().isOk());
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMillis).isGreaterThanOrEqualTo(300);
        }

        @Test
        void resetReturnsToRecordAnd200() throws Exception {
            mockMvc.perform(post("/local/webhook-stub/control/status/500")).andExpect(status().isNoContent());
            mockMvc.perform(post("/local/webhook-stub/control/reset")).andExpect(status().isNoContent());

            mockMvc.perform(post("/local/webhook-stub/receive")).andExpect(status().isOk());
        }

        @Test
        void clearingRequestsEmptiesTheInspectEndpoint() throws Exception {
            mockMvc.perform(post("/local/webhook-stub/receive")).andExpect(status().isOk());

            mockMvc.perform(delete("/local/webhook-stub/requests")).andExpect(status().isNoContent());

            mockMvc.perform(get("/local/webhook-stub/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        void requestsAreReturnedMostRecentFirst() throws Exception {
            mockMvc.perform(post("/local/webhook-stub/receive").content("first")).andExpect(status().isOk());
            mockMvc.perform(post("/local/webhook-stub/receive").content("second")).andExpect(status().isOk());

            mockMvc.perform(get("/local/webhook-stub/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].body").value("second"))
                    .andExpect(jsonPath("$[1].body").value("first"));
        }
    }

    @WebMvcTest(controllers = LocalWebhookStubController.class)
    @AutoConfigureMockMvc(addFilters = false)
    static class WithoutLocalProfile {

        @Autowired
        MockMvc mockMvc;

        @Autowired(required = false)
        LocalWebhookStubController controller;

        @Test
        void stubControllerBeanIsAbsentWithoutLocalProfile() {
            assertThat(controller).isNull();
        }

        @Test
        void receiveEndpointIsNotRegisteredWithoutLocalProfile() throws Exception {
            mockMvc.perform(post("/local/webhook-stub/receive"))
                    .andExpect(status().isNotFound());
        }
    }
}
