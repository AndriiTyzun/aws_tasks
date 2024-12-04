package com.task06;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.events.DynamoDbTriggerEventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
    lambdaName = "audit_producer",
	roleName = "audit_producer-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@DynamoDbTriggerEventSource(targetTable = "${source_table}", batchSize = 1)
@DependsOn(name = "${source_table}", resourceType = ResourceType.DYNAMODB_TABLE)
@EnvironmentVariable(key = "target_table", value = "${target_table}")
public class AuditProducer implements RequestHandler<DynamodbEvent, Void> {

	private final AmazonDynamoDB dynamoDBClient = AmazonDynamoDBClientBuilder.standard().withRegion(Regions.EU_CENTRAL_1).build();
	private final DynamoDB dynamoDB = new DynamoDB(dynamoDBClient);

	public Void  handleRequest(DynamodbEvent event, Context context) {
		Table auditTable = dynamoDB.getTable(System.getenv("target_table"));

		for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
			if ("INSERT".equals(record.getEventName()) || "MODIFY".equals(record.getEventName())) {
				processRecord(record, auditTable);
			}
		}

		return null;
	}

	private void processRecord(DynamodbEvent.DynamodbStreamRecord record, Table auditTable) {
		Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
		Map<String, AttributeValue> oldImage = record.getDynamodb().getOldImage();

		Item auditItem = new Item()
				.withPrimaryKey("id", UUID.randomUUID().toString())
				.withString("itemKey", newImage.get("key").getS())
				.withString("modificationTime", Instant.now().toString());

		if (oldImage == null) {
			auditItem.withMap("newValue", Map.of(
					"key", newImage.get("key").getS(),
					"value", Integer.parseInt(newImage.get("value").getN())
			));
		} else {
			for (String key : oldImage.keySet()) {
				if (!oldImage.get(key).equals(newImage.get(key))) {
					auditItem.withString("updatedAttribute", key)
							.withNumber("oldValue", Integer.parseInt(oldImage.get(key).getN()))
							.withNumber("newValue", Integer.parseInt(newImage.get(key).getN()));
				}
			}
		}

		auditTable.putItem(auditItem);
	}
}
