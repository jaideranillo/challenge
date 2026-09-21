package com.cobre.challenge.adapter.out.messaging.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * Builds the single {@link SqsClient} bean for this application.
 *
 * <p>This is the synchronous client, never {@code SqsAsyncClient}: Spring MVC here runs
 * blocking on virtual threads (spring.threads.virtual.enabled=true), not WebFlux. The sync
 * client's blocking HTTP I/O unmounts the carrier thread correctly under that model, which is
 * why it is the right choice, and why no caller may wrap a call to it in {@code synchronized}
 * (that would pin the virtual thread instead).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SqsProperties.class)
public class SqsClientConfig {

	@Bean
	SqsClient sqsClient(SqsProperties properties) {
		SqsClientBuilder builder = SqsClient.builder().region(Region.of(properties.region()));

		properties.endpoint().ifPresent(endpoint -> builder.endpointOverride(URI.create(endpoint)));

		if (properties.credentials() != null) {
			builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
					properties.credentials().accessKey(), properties.credentials().secretKey())));
		}

		return builder.build();
	}

}
