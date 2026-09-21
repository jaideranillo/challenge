package com.cobre.challenge;

import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// No @ServiceConnection for LocalStack SQS in this Boot version (that mechanism
	// ships with Spring Cloud AWS, which this project does not depend on), so the
	// container's endpoint/region/credentials are exposed as challenge.sqs.* test
	// properties via DynamicPropertyRegistrar instead.
	private static final String INIT_SCRIPT_CONTAINER_PATH = "/etc/localstack/init/ready.d/init-sqs.sh";

	@Bean
	@ServiceConnection
	LgtmStackContainer grafanaLgtmContainer() {
		return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"));
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
	}

	@Bean
	LocalStackContainer localstackContainer() {
		return new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4.0"))
				.withServices("sqs")
				.withCopyFileToContainer(
						MountableFile.forHostPath(
								Path.of(System.getProperty("user.dir"), "docker", "localstack", "init-sqs.sh"), 0777),
						INIT_SCRIPT_CONTAINER_PATH);
	}

	@Bean
	DynamicPropertyRegistrar sqsPropertiesRegistrar(LocalStackContainer localstackContainer) {
		return registry -> {
			registry.add("challenge.sqs.endpoint", () -> localstackContainer.getEndpoint().toString());
			registry.add("challenge.sqs.region", localstackContainer::getRegion);
			registry.add("challenge.sqs.credentials.access-key", localstackContainer::getAccessKey);
			registry.add("challenge.sqs.credentials.secret-key", localstackContainer::getSecretKey);
			registry.add("challenge.sqs.queues.deliveries", () -> "deliveries");
			registry.add("challenge.sqs.queues.deliveries-dlq", () -> "deliveries-dlq");
		};
	}

}
