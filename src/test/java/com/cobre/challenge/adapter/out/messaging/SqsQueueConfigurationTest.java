package com.cobre.challenge.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Regression test for ADR-006 section 1.1: proves the queue attributes produced by
 * {@code docker/localstack/init-sqs.sh} against a real LocalStack SQS API, not the
 * init script's exit code. Catches redrive-policy / visibility-timeout drift here,
 * at the infrastructure step, instead of later in worker-level tests.
 */
@Testcontainers
class SqsQueueConfigurationTest {

    private static final String INIT_SCRIPT_CONTAINER_PATH = "/etc/localstack/init/ready.d/init-sqs.sh";
    private static final String MAIN_QUEUE_NAME = "deliveries";
    private static final String DLQ_NAME = "deliveries-dlq";

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(
                    DockerImageName.parse("localstack/localstack:4.4.0"))
            .withServices("sqs")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(
                            Path.of(System.getProperty("user.dir"), "docker", "localstack", "init-sqs.sh"), 0777),
                    INIT_SCRIPT_CONTAINER_PATH);

    private static final Pattern MAX_RECEIVE_COUNT_PATTERN = Pattern.compile("\"maxReceiveCount\"\\s*:\\s*\"?(\\d+)\"?");
    private static final Pattern DEAD_LETTER_TARGET_ARN_PATTERN =
            Pattern.compile("\"deadLetterTargetArn\"\\s*:\\s*\"([^\"]+)\"");

    private static SqsClient sqsClient;

    @BeforeAll
    static void createSqsClient() {
        sqsClient = SqsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();
    }

    @Test
    void deliveriesQueueMatchesAdr006Section1_1() throws Exception {
        String dlqArn = queueArn(DLQ_NAME);
        Map<QueueAttributeName, String> attributes = queueAttributes(MAIN_QUEUE_NAME);

        assertThat(attributes.get(QueueAttributeName.VISIBILITY_TIMEOUT)).isEqualTo("30");
        assertThat(attributes.get(QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS)).isEqualTo("20");

        String redrivePolicy = attributes.get(QueueAttributeName.REDRIVE_POLICY);
        assertThat(maxReceiveCount(redrivePolicy)).isEqualTo(3);
        assertThat(deadLetterTargetArn(redrivePolicy)).isEqualTo(dlqArn);
    }

    @Test
    void reRunningInitScriptIsIdempotent() throws Exception {
        Map<QueueAttributeName, String> before = queueAttributes(MAIN_QUEUE_NAME);

        ExecResult result = localstack.execInContainer("bash", INIT_SCRIPT_CONTAINER_PATH);

        assertThat(result.getExitCode()).isZero();
        Map<QueueAttributeName, String> after = queueAttributes(MAIN_QUEUE_NAME);
        assertThat(after).isEqualTo(before);
    }

    private static String queueArn(String queueName) {
        return queueAttributes(queueName).get(QueueAttributeName.QUEUE_ARN);
    }

    private static Map<QueueAttributeName, String> queueAttributes(String queueName) {
        String queueUrl = sqsClient
                .getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                .queueUrl();
        return sqsClient
                .getQueueAttributes(builder -> builder.queueUrl(queueUrl).attributeNames(QueueAttributeName.ALL))
                .attributes();
    }

    private static int maxReceiveCount(String redrivePolicy) {
        Matcher matcher = MAX_RECEIVE_COUNT_PATTERN.matcher(redrivePolicy);
        assertThat(matcher.find()).as("maxReceiveCount present in RedrivePolicy: %s", redrivePolicy).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static String deadLetterTargetArn(String redrivePolicy) {
        Matcher matcher = DEAD_LETTER_TARGET_ARN_PATTERN.matcher(redrivePolicy);
        assertThat(matcher.find()).as("deadLetterTargetArn present in RedrivePolicy: %s", redrivePolicy).isTrue();
        return matcher.group(1);
    }
}
