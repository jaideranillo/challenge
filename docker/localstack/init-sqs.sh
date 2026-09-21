#!/usr/bin/env bash
set -euo pipefail

# ADR-006 §1.1 queue configuration.
# DLQ is created first so its ARN is available for the main queue's
# RedrivePolicy before the main queue exists (no window with no DLQ target).
# `create-queue` is idempotent when called again with identical attributes,
# which is what re-running this script (restart, `compose up`) does.

REGION="us-east-1"
MAIN_QUEUE_NAME="deliveries"
DLQ_NAME="deliveries-dlq"

dlq_url=$(awslocal sqs create-queue \
  --queue-name "$DLQ_NAME" \
  --region "$REGION" \
  --attributes '{"ReceiveMessageWaitTimeSeconds":"20"}' \
  --query 'QueueUrl' --output text)

dlq_arn=$(awslocal sqs get-queue-attributes \
  --queue-url "$dlq_url" \
  --attribute-names QueueArn \
  --region "$REGION" \
  --query 'Attributes.QueueArn' --output text)

redrive_policy=$(printf '{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"3\"}' "$dlq_arn")
attributes=$(printf '{"VisibilityTimeout":"30","ReceiveMessageWaitTimeSeconds":"20","RedrivePolicy":"%s"}' \
  "$(printf '%s' "$redrive_policy" | sed 's/"/\\"/g')")

awslocal sqs create-queue \
  --queue-name "$MAIN_QUEUE_NAME" \
  --region "$REGION" \
  --attributes "$attributes" \
  --query 'QueueUrl' --output text
