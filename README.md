### AWS Lambda Kinesis to ElasticSearch

Lambda function used in demonstrating the use of Red Hat OpenShift Connectors.

The flow is as follows:
* source database: MongoDB Atlas instance, with `sample_mflix` database and `movies` collection
* OpenShift Connectors MongoDB source connector pointing to the MongoDB Atlas instance and capturing change events from the 
`sample_mflix.movies` collection
* OpenShift Connectors sink connector for AWS Kinesis, forwarding the change events from the Streams for Apache Kafka 
topic to AWS Kinesis
* AWS Lambda function consumes Kinesis records and inserts/updates the `movies` index in an AWS OpenSearch instance.

#### Deploy the Lambda function

* Run the `1-create-bucket` script to create the S3 bucket to hold the lambda package
  ```bash
  ./1-create-bucket.sh 
  ```
* Run the `2-deploy` script to deploy and update the lambda package. Rerun evey time you need to update the lambda function.
  ```bash
  ./2-deploy.sh 
  ```

#### Create a Kinesis stream

* Create a Kinesis stream
  ```bash
  aws kinesis create-stream --stream-name movies --shard-count 1
  ```
* Get the stream ARN
  ```bash
  aws kinesis describe-stream --stream-name movies
  ```
  Take note of the stream ARN

#### Add an event stream to the Lambda function

* Get the name of the Lambda function
  ```bash
  FUNCTION=$(aws cloudformation describe-stack-resource --stack-name lambda-kinesis-es --logical-resource-id function \
    --query 'StackResourceDetail.PhysicalResourceId' --output text)
  ```
* Add the event source to the lambda function
  ```bash
  aws lambda create-event-source-mapping --function-name ${FUNCTION} \
    --event-source  ${STREAM_ARN} \
    --batch-size 100 --starting-position TRIM_HORIZON
  ```

#### Add ElasticSearch environment variables to the Lambda function

* Set local environment variables
  ```bash
  ES_ENDPOINT=<ElasticSearch endpoint>
  ES_USERNAME=<ElasticSearch username>
  ES_PASSWORD=<ElasticSearch password>
  ES_INDEX=movies
  ```
* Set environment variables on lambda
  ```bash
  aws lambda update-function-configuration --function-name $FUNCTION \
    --environment "Variables={ES_ENDPOINT=${ES_ENDPOINT},ES_USERNAME=${ES_USERNAME},ES_PASSWORD=${ES_PASSWORD},ES_INDEX=${ES_INDEX}}"
  ```
  
#### Create the Elasticsearch index

* Mapping for the `movies` index
  ```json
  {
      "mappings": {
          "properties": {
              "plot": {
                "type": "text"
              },
              "fullplot": {
                "type": "text"
              },          
              "title": {
                  "type": "text"
              },
              "cast": {
                  "type": "text"
              },
              "directors": {
                "type": "text"
              },          
              "genres": {
                  "type": "text"
              }            
          }
      }
  }
  ```
