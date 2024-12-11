package com.task09;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.AWSXRayRecorderBuilder;
import com.amazonaws.xray.plugins.ECSPlugin;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.TracingMode;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
    lambdaName = "processor",
	roleName = "processor-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	tracingMode = TracingMode.Active,
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
@EnvironmentVariable(key = "table", value = "${target_table}")
public class Processor implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
	private static final String TABLE_NAME = System.getenv("table");
	private static final String API_URL = "https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current=temperature_2m,wind_speed_10m&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m";
	private final DynamoDB dynamoDB = new DynamoDB(AmazonDynamoDBClientBuilder
			.standard().withRegion(Regions.EU_CENTRAL_1).build());

	static {
		AWSXRayRecorderBuilder.standard().withPlugin(new ECSPlugin()).build();

	}


	public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
		try {
			AWSXRay.beginSegment("WeatherForecastLambda");
			String weatherData = getLatestWeatherForecast();

			pushToDynamoDB(weatherData);
			AWSXRay.endSegment();

			return APIGatewayV2HTTPResponse.builder()
					.withStatusCode(200)
					.withBody("{\"statusCode\": 200, \"message\": \"Weather forecast added successfully!\"}")
					.withHeaders(Map.of("Content-Type", "application/json"))
					.build();
		} catch (Exception e) {
			return APIGatewayV2HTTPResponse.builder()
					.withStatusCode(400)
					.withBody(String.format("{\"statusCode\": 400, \"message\": \"Error: \"" + e.getMessage()))
					.withHeaders(Map.of("Content-Type", "application/json"))
					.build();
		}
	}

	private void pushToDynamoDB(String weatherData) throws Exception {
		String id = UUID.randomUUID().toString();
		String createdAt = Instant.now().toString();

		ObjectMapper mapper = new ObjectMapper();
		Object weather = mapper.readValue(weatherData, Object.class);

		Table table = dynamoDB.getTable(TABLE_NAME);
		Item item = new Item()
				.withPrimaryKey("id", UUID.randomUUID().toString())
				.withJSON("forecast", mapper.writeValueAsString(weather));

		table.putItem(item);
	}

	public String getLatestWeatherForecast() throws Exception {
		URL url = new URL(API_URL);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("GET");

		int responseCode = connection.getResponseCode();
		if (responseCode == HttpURLConnection.HTTP_OK) {
			BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			StringBuilder response = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				response.append(line);
			}
			reader.close();
			return response.toString();
		} else {
			throw new RuntimeException("Failed to fetch weather data: HTTP code " + responseCode);
		}
	}
}
