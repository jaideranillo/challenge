package com.cobre.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChallengeApplicationTests {

	@Value("${challenge.sqs.endpoint}")
	private String sqsEndpoint;

	@Value("${challenge.sqs.region}")
	private String sqsRegion;

	@Value("${challenge.sqs.credentials.access-key}")
	private String sqsAccessKey;

	@Value("${challenge.sqs.credentials.secret-key}")
	private String sqsSecretKey;

	@Test
	void contextLoads() {
	}

	@Test
	void testcontainersConfigurationReachesDeliveriesQueue() {
		try (SqsClient sqsClient = SqsClient.builder()
				.endpointOverride(URI.create(sqsEndpoint))
				.region(Region.of(sqsRegion))
				.credentialsProvider(
						StaticCredentialsProvider.create(AwsBasicCredentials.create(sqsAccessKey, sqsSecretKey)))
				.build()) {
			String queueUrl = sqsClient
					.getQueueUrl(GetQueueUrlRequest.builder().queueName("deliveries").build())
					.queueUrl();

			assertThat(queueUrl).contains("deliveries");
		}
	}

}
