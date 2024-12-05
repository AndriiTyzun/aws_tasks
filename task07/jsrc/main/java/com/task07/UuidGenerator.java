package com.task07;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.EventSource;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.EventSourceType;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;

import java.time.Instant;
import java.util.*;

//@LambdaHandler(
//    lambdaName = "uuid_generator",
//	roleName = "uuid_generator-role",
//	isPublishVersion = true,
//	aliasName = "${lambdas_alias_name}",
//	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
//)
//@EventSource(eventType = EventSourceType.CLOUDWATCH_RULE_TRIGGER)
//@DependsOn(name = "uuid_trigger", resourceType = ResourceType.CLOUDWATCH_RULE)
//@EnvironmentVariable(key = "bucket", value = "${target_bucket}")
public class UuidGenerator implements RequestHandler<Object, Void> {

	private final AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
			.withRegion("eu-central-1")
			.build();

	@Override
	public Void handleRequest(Object input, Context context) {
		try {
			context.getLogger().log("UUID generation, bucket name: " + System.getenv("bucket"));
			List<String> uuids = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				uuids.add(UUID.randomUUID().toString());
			}
			Map<String, Object> fileContent = new HashMap<>();
			fileContent.put("ids", uuids);

			ObjectMapper objectMapper = new ObjectMapper();
			String jsonContent = objectMapper.writeValueAsString(fileContent);

			String fileName = Instant.now().toString();

			s3Client.putObject(System.getenv("bucket"), fileName, jsonContent);
			context.getLogger().log("File created: " + fileName);

			return null;

		} catch (Exception e) {
			context.getLogger().log("Error during UUID generation: " + e.getMessage());
			return null;
		}
	}
}
