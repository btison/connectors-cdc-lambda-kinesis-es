#!/bin/bash
set -eo pipefail
FUNCTION=$(aws cloudformation describe-stack-resource --stack-name java-events --logical-resource-id function --query 'StackResourceDetail.PhysicalResourceId' --output text)
PAYLOAD='file://events/kinesis-record.json'

while true; do
  aws lambda invoke --function-name $FUNCTION --payload $PAYLOAD --cli-binary-format raw-in-base64-out out.json
  cat out.json
  echo ""
  sleep 2
done