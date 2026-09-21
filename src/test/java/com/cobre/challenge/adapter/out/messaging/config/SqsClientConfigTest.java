package com.cobre.challenge.adapter.out.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.sqs.SqsClient;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
@SpringBootTest
class SqsClientConfigTest {

	@Autowired
	private SqsClient sqsClient;

	@Autowired
	private SqsProperties sqsProperties;

	@Test
	void sqsClientBeanExists() {
		assertThat(sqsClient).isNotNull();
	}

	@Test
	void propertiesBindUnderLocalProfile() {
		assertThat(sqsProperties.region()).isNotBlank();
		assertThat(sqsProperties.queues().deliveries()).isEqualTo("deliveries");
		assertThat(sqsProperties.queues().deliveriesDlq()).isEqualTo("deliveries-dlq");
	}

}
