package com.task05;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
    lambdaName = "api_handler",
	roleName = "api_handler-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
public class ApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
	private static final String TABLE_NAME = "Events";
	private final DynamoDB dynamoDB = new DynamoDB(AmazonDynamoDBClientBuilder.defaultClient());

	@Override
	public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent request, Context context) {
		try {
			context.getLogger().log("Received event: " + request);
			Map<String, Object> input = new ObjectMapper().readValue(request.getBody(), Map.class);
			context.getLogger().log("input: " + input);

			Integer principalId = (Integer) input.get("principalId");
			Map<String, String> content = (Map<String, String>) input.get("content");
			context.getLogger().log("content: " + content);

			String id = UUID.randomUUID().toString();
			String createdAt = Instant.now().toString();

			Table table = dynamoDB.getTable(TABLE_NAME);
			Item item = new Item()
					.withPrimaryKey("id", id)
					.withInt("principalId", principalId)
					.withString("createdAt", createdAt)
					.withMap("body", content);
			table.putItem(item);

			Map<String, Object> event = Map.of(
					"id", id,
					"principalId", principalId,
					"createdAt", createdAt,
					"body", content
			);

			return  APIGatewayV2HTTPResponse.builder()
					.withStatusCode(201)
					.withBody(new ObjectMapper().writeValueAsString(Map.of("statusCode", 201, "event", event)))
					.withHeaders(Map.of("Content-Type", "application/json"))
					.build();

		} catch (Exception e) {
			context.getLogger().log("Error saving event = " + e.getMessage() + " | " + request.getBody());
			return  APIGatewayV2HTTPResponse.builder()
					.withStatusCode(500)
					.withBody("{\"message\": \"Internal Server Error+ " + e.getMessage() + " \"}"+ " | " + request.getBody())
					.withHeaders(Map.of("Content-Type", "application/json"))
					.build();
		}
	}
}
