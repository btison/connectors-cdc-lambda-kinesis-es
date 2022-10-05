package org.acme.connectors;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HandlerKinesis implements RequestHandler<KinesisEvent, String> {

    private static final Logger log = LoggerFactory.getLogger(HandlerKinesis.class);

    private final static String PROTOCOL = "https://";

    private final static String USER_AGENT_NAME = "User-Agent";

    private final static String USER_AGENT_VALUE = "LambdaKinesisToES/1.0";

    private final String esEndpoint = System.getenv("ES_ENDPOINT");

    private final String esUserName = System.getenv("ES_USERNAME");

    private final String esPassword = System.getenv("ES_PASSWORD");

    private final String esIndex = System.getenv("ES_INDEX");

    Gson gson = new GsonBuilder().setPrettyPrinting().create();

    RestClient restClient;

    @Override
    public String handleRequest(KinesisEvent kinesisEvent, Context context) {
        if (restClient == null) {
            restClient = buildESRestClient();
        }
        String response = "200 OK";
        for (KinesisEvent.KinesisEventRecord record : kinesisEvent.getRecords()) {
            processRecord(record);
        }
        Util.logEnvironment(kinesisEvent, context, gson);
        return response;
    }

    private void processRecord(KinesisEvent.KinesisEventRecord record) {
        ByteBuffer bb = record.getKinesis().getData();
        log.info("Raw data: " + gson.toJson(bb));
        if (bb.array().length == 0) {
            log.info("Tombstone record, ignoring");
            return;
        }
        JsonElement jsonElement = JsonParser.parseString(new String(bb.array()));
        log.info("Record: " + jsonElement.toString());

        JsonElement json_op = jsonElement.getAsJsonObject().get("op");
        if (json_op == null) {
            log.warn("Operation is null!");
            return;
        }
        String op = jsonElement.getAsJsonObject().get("op").getAsString();
        log.info("Operation: " + op);
        if ("c".equalsIgnoreCase(op) || "u".equalsIgnoreCase(op) || "r".equalsIgnoreCase(op)) {
            String after = jsonElement.getAsJsonObject().get("after").getAsString();
            log.info("After: " + after);
            JsonElement afterJson = JsonParser.parseString(after);
            JsonObject id = afterJson.getAsJsonObject().get("_id").getAsJsonObject();
            String documentId = id.get("$oid").getAsString();
            log.info("Indexing document with id " + documentId);
            afterJson.getAsJsonObject().remove("_id");
            performRequest(HttpPut.METHOD_NAME, "/" + esIndex + "/_doc/" + documentId, afterJson.toString());
        } else if ("d".equalsIgnoreCase(op)) {
            log.info("Delete is not supported");
        } else {
            log.warn("Unsupported operation");
        }
    }

    private Response performRequest(String method, String endpoint, String payload) {
        Request request = new Request(method, endpoint);
        request.setJsonEntity(payload);
        try {
            return restClient.performRequest(request);
        } catch (ResponseException responseException) {
            log.error("[ERROR] processing index request with response code " + responseException.getResponse(), responseException);
            return responseException.getResponse();
        } catch (IOException e) {
            throw new RuntimeException("[ERROR] Internal error happened when processing your request", e);
        }
    }

    private RestClient buildESRestClient() {
        final Header[] headers = new Header[]{new BasicHeader(USER_AGENT_NAME, USER_AGENT_VALUE)};
        final String endpointWithProtocol = String.format("%s%s", PROTOCOL, esEndpoint);
        return RestClient.builder(HttpHost.create(endpointWithProtocol))
                .setHttpClientConfigCallback(httpAsyncClientBuilder -> {
                    httpAsyncClientBuilder.setDefaultCredentialsProvider(buildCredentialsProvider());
                    return httpAsyncClientBuilder;
                })
                .setDefaultHeaders(headers)
                .build();
    }

    private CredentialsProvider buildCredentialsProvider() {
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(esUserName, esPassword));
        return credentialsProvider;
    }
}
