package com.task10;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID;
import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID;

@LambdaHandler(
    lambdaName = "api_handler",
	roleName = "api_handler-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@DependsOn(resourceType = ResourceType.COGNITO_USER_POOL, name = "${booking_userpool}")
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "REGION", value = "${region}"),
		@EnvironmentVariable(key = "COGNITO_ID", value = "${booking_userpool}", valueTransformer = USER_POOL_NAME_TO_USER_POOL_ID),
		@EnvironmentVariable(key = "CLIENT_ID", value = "${booking_userpool}", valueTransformer = USER_POOL_NAME_TO_CLIENT_ID),
		@EnvironmentVariable(key = "Tables", value = "${tables_table}"),
		@EnvironmentVariable(key = "Reservations", value = "${reservations_table}")
})
public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private final CognitoIdentityProviderClient cognitoClient;
	private final DynamoDB dynamoDB;
	private final Map<String, String> headersForCORS;

	public ApiHandler() {
		this.cognitoClient = CognitoIdentityProviderClient.builder()
				.region(Region.of(System.getenv("REGION")))
				.credentialsProvider(DefaultCredentialsProvider.create())
				.build();
		dynamoDB = new DynamoDB(AmazonDynamoDBClientBuilder
				.standard().withRegion(Regions.EU_CENTRAL_1)
				.build());
		this.headersForCORS = initHeadersForCORS();
	}

	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
		context.getLogger().log("Started event: " + event);

		String path = event.getPath();
		String method = event.getHttpMethod();

		if ("/signin".equals(path) && "POST".equals(method)) {
			return postSignInHandler(event, context);
		} else if ("/signup".equals(path) && "POST".equals(method)) {
			return postSignUpHandler(event, context);
		} else if ("/tables".equals(path) && "GET".equals(method)) {
			return getTablesHandler(event, context);
		} else if ("/tables".equals(path) && "POST".equals(method)) {
			return postTablesHandler(event, context);
		} else if (path.startsWith("/tables/") && "GET".equals(method)) {
			return getTableByIdHandler(event, context);
		} else if ("/reservations".equals(path) && "POST".equals(method)) {
			return postReservationHandler(event, context);
		} else if ("/reservations".equals(path) && "GET".equals(method)) {
			return getReservationsHandler(event, context);
		}

		return createErrorResponse(404, "Endpoint not found");
	}

	private APIGatewayProxyResponseEvent postSignUpHandler(APIGatewayProxyRequestEvent event, Context context) {
		Map<String, String> body = parseRequestBody(event.getBody());
		String email = body.get("email");
		String password = body.get("password");
		String firstName = body.get("firstName");
		String lastName = body.get("lastName");

		try {
			AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
					.userPoolId(System.getenv("COGNITO_ID"))
					.username(email)
					.temporaryPassword(password)
					.messageAction("SUPPRESS")
					.userAttributes(
							AttributeType.builder()
									.name("given_name")
									.value(firstName)
									.build(),
							AttributeType.builder()
									.name("family_name")
									.value(lastName)
									.build(),
							AttributeType.builder()
									.name("email")
									.value(email)
									.build()
					)
					.build();

			cognitoClient.adminCreateUser(createUserRequest);

			return createSuccessResponse(Map.of("message", "User created successfully"));
		} catch (Exception e) {
			return createErrorResponse(400, "Failed to create user: " + e);
		}
	}

	private APIGatewayProxyResponseEvent postSignInHandler(APIGatewayProxyRequestEvent event, Context context) {
		Map<String, String> body = parseRequestBody(event.getBody());
		String email = body.get("email");
		String password = body.get("password");
		context.getLogger().log("SignIn event: " + email + " - " + password + " - " + System.getenv("COGNITO_ID") + " - " + System.getenv("CLIENT_ID"));
		try {
			AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(AdminInitiateAuthRequest.builder()
					.authFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
					.authParameters(Map.of("USERNAME", email, "PASSWORD", password))
					.userPoolId(System.getenv("COGNITO_ID"))
					.clientId(System.getenv("CLIENT_ID"))
					.build());

			if (ChallengeNameType.NEW_PASSWORD_REQUIRED.name().equals(authResponse.challengeNameAsString())) {
				return createSuccessResponse(Map.of("accessToken",
				cognitoClient.adminRespondToAuthChallenge(AdminRespondToAuthChallengeRequest.builder()
						.challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED)
						.challengeResponses(Map.of(
								"USERNAME", email,
								"PASSWORD",password,
								"NEW_PASSWORD", password
						))
						.userPoolId(System.getenv("COGNITO_ID"))
						.clientId(System.getenv("CLIENT_ID"))
						.session(authResponse.session())
						.build()).authenticationResult().idToken()));
			}

			context.getLogger().log("authResponse: " + authResponse);
			context.getLogger().log("authResponse token: " + authResponse.authenticationResult().idToken());

			return createSuccessResponse(Map.of("accessToken", authResponse.authenticationResult().idToken()));
		} catch (Exception e) {
			context.getLogger().log("Error: " + e);
			return createErrorResponse(400, "Invalid credentials: " + e);
		}
	}

	private APIGatewayProxyResponseEvent getTablesHandler(APIGatewayProxyRequestEvent event, Context context) {
		try {
			Table table = dynamoDB.getTable(System.getenv("Tables"));

			Iterator<Item> iterator = table.scan().iterator();
			List<Item> items = new ArrayList<>();
			while (iterator.hasNext()) {
				items.add(iterator.next());
			}

			context.getLogger().log("Tables items: " + items);

			List<Map<String, Object>> tables = items.stream().map(item -> {
					Map<String, Object> tmp = new HashMap<String, Object>();
					tmp.put("id", item.getInt("id"));
					tmp.put("number", item.getInt("number"));
					tmp.put("places", item.getInt("places"));
					tmp.put("isVip", item.getBoolean("isVip"));
					tmp.put("minOrder", item.isPresent("minOrder") ? item.getInt("minOrder") : null);
					return tmp;
			}).collect(Collectors.toList());

			context.getLogger().log("Tables: " + tables);

			return createSuccessResponse(Map.of("tables", tables));
		} catch (Exception e) {
			return createErrorResponse(400, "Failed to fetch tables: " + e);
		}
	}

	private APIGatewayProxyResponseEvent postTablesHandler(APIGatewayProxyRequestEvent event, Context context) {
		Map<String, String> body = parseRequestBody(event.getBody());
		try {
			Table table = dynamoDB.getTable(System.getenv("Tables"));
			Item newItem = new Item()
					.withPrimaryKey("id", Integer.parseInt(body.get("id")))
					.withInt("number", Integer.parseInt(body.get("number")))
					.withInt("places", Integer.parseInt(body.get("places")))
					.withBoolean("isVip", Boolean.parseBoolean(body.get("isVip")));

			if (body.containsKey("minOrder")) {
				newItem.withInt("minOrder", Integer.parseInt(body.get("minOrder")));
			}

			table.putItem(newItem);

			return createSuccessResponse(Map.of("id", newItem.getInt("id")));
		} catch (Exception e) {
			return createErrorResponse(400, "Failed to create table " + e);
		}
	}

	private APIGatewayProxyResponseEvent getTableByIdHandler(APIGatewayProxyRequestEvent event, Context context) {
		try {
			String tableId = event.getPathParameters().get("tableId");
			Table table = dynamoDB.getTable(System.getenv("Tables"));
			context.getLogger().log("Table Id: " + tableId);

			Item item = table.getItem("id", Integer.parseInt(tableId));
			context.getLogger().log("Table fetched item: " + item);

			if (item == null) {
				return createErrorResponse(400, "Table not found");
			}

			Map<String, Object> tableDetails = Map.of(
					"id", item.getInt("id"),
					"number", item.getInt("number"),
					"places", item.getInt("places"),
					"isVip", item.getBoolean("isVip"),
					"minOrder", item.isPresent("minOrder") ? item.getInt("minOrder") : null
			);

			return createSuccessResponse(tableDetails);
		} catch (Exception e) {
			return createErrorResponse(400, "Failed to fetch table by ID " + e);
		}
	}

	private APIGatewayProxyResponseEvent postReservationHandler(APIGatewayProxyRequestEvent event, Context context) {
		Map<String, String> body = parseRequestBody(event.getBody());

		try {
			Table reservationsTable = dynamoDB.getTable(System.getenv("Reservations"));
			context.getLogger().log("Request body: " + body);

			String reservationId = UUID.randomUUID().toString();

			int tableNumber = Integer.parseInt(body.get("tableNumber"));
			String clientName = body.get("clientName");
			String phoneNumber = body.get("phoneNumber");
			String date = body.get("date");
			String slotTimeStart = body.get("slotTimeStart");
			String slotTimeEnd = body.get("slotTimeEnd");

			Iterator<Item> iterator = reservationsTable.scan().iterator();
			List<Item> existingReservations = new ArrayList<>();
			while (iterator.hasNext()) {
				existingReservations.add(iterator.next());
			}

			context.getLogger().log("Reservations: " + existingReservations);

			boolean conflict = existingReservations.stream().anyMatch(reservation ->
					reservation.getString("tableNumber").equals(String.valueOf(tableNumber)) &&
							reservation.getString("date").equals(date) &&
							(
									(slotTimeStart.compareTo(reservation.getString("slotTimeStart")) >= 0 &&
											slotTimeStart.compareTo(reservation.getString("slotTimeEnd")) < 0) ||
											(slotTimeEnd.compareTo(reservation.getString("slotTimeStart")) > 0 &&
													slotTimeEnd.compareTo(reservation.getString("slotTimeEnd")) <= 0)
							)
			);

			if (conflict) {
				return createErrorResponse(400, "Conflicting reservation exists for the selected time slot.");
			}

			Table table = dynamoDB.getTable(System.getenv("Tables"));
			Iterator<Item> tableiterator = table.scan().iterator();
			List<Item> items = new ArrayList<>();
			while (iterator.hasNext()) {
				items.add(iterator.next());
			}
			if(items.stream().noneMatch(x -> x.getInt("number") == tableNumber)){
				return createErrorResponse(400, "Table not found");
			}

			Item newReservation = new Item()
					.withPrimaryKey("id", reservationId)
					.withInt("tableNumber", tableNumber)
					.withString("clientName", clientName)
					.withString("phoneNumber", phoneNumber)
					.withString("date", date)
					.withString("slotTimeStart", slotTimeStart)
					.withString("slotTimeEnd", slotTimeEnd);


			reservationsTable.putItem(newReservation);

			return createSuccessResponse(Map.of("reservationId", reservationId));
		} catch (Exception e) {
			return createErrorResponse(400, "Failed to create reservation " + e);
		}
	}

	private APIGatewayProxyResponseEvent getReservationsHandler(APIGatewayProxyRequestEvent event, Context context) {
		try {
			Table reservationsTable = dynamoDB.getTable(System.getenv("Reservations"));

			Iterator<Item> iterator = reservationsTable.scan().iterator();
			List<Item> reservations = new ArrayList<>();
			while (iterator.hasNext()) {
				reservations.add(iterator.next());
			}

			context.getLogger().log("Reservations: " + reservations);

			List<Map<String, Object>> reservationList = reservations.stream().map(reservation -> {
					Map<String, Object> tmp = new HashMap<String, Object>();
					tmp.put("tableNumber", reservation.getInt("tableNumber"));
					tmp.put("clientName", reservation.getString("clientName"));
					tmp.put("phoneNumber", reservation.getString("phoneNumber"));
					tmp.put("date", reservation.getString("date"));
					tmp.put("slotTimeStart", reservation.getString("slotTimeStart"));
					tmp.put("slotTimeEnd", reservation.getString("slotTimeEnd"));
					return tmp;
				}
			).collect(Collectors.toList());

			return createSuccessResponse(Map.of("reservations", reservationList));
		} catch (Exception e) {
			return createErrorResponse(400, "Failed to fetch reservations " + e);
		}
	}

	private APIGatewayProxyResponseEvent createSuccessResponse(Object body) {
		ObjectMapper objectMapper = new ObjectMapper();
		try {
			String jsonBody = objectMapper.writeValueAsString(body);
			return new APIGatewayProxyResponseEvent()
					.withStatusCode(200)
					.withHeaders(headersForCORS)
					.withBody(jsonBody);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Error serializing response body to JSON", e);
		}
	}

	private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
		return new APIGatewayProxyResponseEvent()
				.withStatusCode(statusCode)
				.withBody(Map.of("error", message).toString())
				.withHeaders(headersForCORS);
	}

	private Map<String, String> initHeadersForCORS() {
		return Map.of(
				"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token",
				"Access-Control-Allow-Origin", "*",
				"Access-Control-Allow-Methods", "*",
				"Accept-Version", "*"
		);
	}

	private Map<String, String> parseRequestBody(String requestBody) {
		ObjectMapper objectMapper = new ObjectMapper();
		try {
			return objectMapper.readValue(requestBody, new TypeReference<Map<String, String>>() {});
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid request body format", e);
		}
	}
}
