package com.cobre.challenge;

import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

class LocalWebhookStubSecurityConfigTest {

    @Import(TestcontainersConfiguration.class)
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("local")
    static class UnderLocalProfile {

        @Autowired
        MockMvc mockMvc;

        @Test
        void webhookStubReceiveIsReachableWithoutAuthentication() throws Exception {
            mockMvc.perform(post("/local/webhook-stub/receive"))
                    .andExpect(status().isOk());
        }

        @Test
        void webhookStubRequestsIsReachableWithoutAuthentication() throws Exception {
            mockMvc.perform(get("/local/webhook-stub/requests"))
                    .andExpect(status().isOk());
        }

        @Test
        void webhookStubClearRequestsIsReachableWithoutAuthentication() throws Exception {
            mockMvc.perform(delete("/local/webhook-stub/requests"))
                    .andExpect(status().isNoContent());
        }

        @Test
        void unrelatedPathIsNotAccidentallyPermittedByTheStubFilterChain() throws Exception {
            mockMvc.perform(get("/some-other-path"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(unauthenticated());
        }
    }

    @Import(TestcontainersConfiguration.class)
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("default")
    static class UnderDefaultProfile {

        @Autowired
        MockMvc mockMvc;

        @Test
        void anyPathStillRequiresAuthenticationWhenLocalProfileIsNotActive() throws Exception {
            mockMvc.perform(get("/local/webhook-stub/requests"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
