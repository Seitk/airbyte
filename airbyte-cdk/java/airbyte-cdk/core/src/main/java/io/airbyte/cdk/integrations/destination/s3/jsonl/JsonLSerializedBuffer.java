/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.integrations.destination.s3.jsonl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airbyte.cdk.integrations.base.JavaBaseConstants;
import io.airbyte.cdk.integrations.destination.record_buffer.BaseSerializedBuffer;
import io.airbyte.cdk.integrations.destination.record_buffer.BufferCreateFunction;
import io.airbyte.cdk.integrations.destination.record_buffer.BufferStorage;
import io.airbyte.cdk.integrations.destination.s3.S3DestinationConstants;
import io.airbyte.cdk.integrations.destination.s3.util.CompressionType;
import io.airbyte.cdk.integrations.destination.s3.util.Flattening;
import io.airbyte.commons.jackson.MoreMappers;
import io.airbyte.commons.json.Jsons;
import io.airbyte.protocol.models.v0.AirbyteRecordMessage;
import io.airbyte.protocol.models.v0.AirbyteStreamNameNamespacePair;
import io.airbyte.protocol.models.v0.ConfiguredAirbyteCatalog;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

public class JsonLSerializedBuffer extends BaseSerializedBuffer {

  private static final ObjectMapper MAPPER = MoreMappers.initMapper();

  private PrintWriter printWriter;

  private final boolean flattenData;

  protected JsonLSerializedBuffer(final BufferStorage bufferStorage, final boolean gzipCompression, final boolean flattenData) throws Exception {
    super(bufferStorage);
    // we always want to compress jsonl files
    withCompression(gzipCompression);
    this.flattenData = flattenData;
  }

  @Override
  protected void initWriter(final OutputStream outputStream) {
    printWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
  }

  @Override
  protected void writeRecord(final AirbyteRecordMessage record) {
    final ObjectNode json = MAPPER.createObjectNode();
    json.put(JavaBaseConstants.COLUMN_NAME_AB_ID, UUID.randomUUID().toString());
    json.put(JavaBaseConstants.COLUMN_NAME_EMITTED_AT, record.getEmittedAt());

    JsonNode dataNode = record.getData();
    ObjectNode updatedDataNode = MAPPER.createObjectNode();

    // Iterate through each field in dataNode
    dataNode.fields().forEachRemaining(field -> {
        String fieldName = field.getKey();
        JsonNode fieldValue = field.getValue();

        // Check if field value is an object or array
        if (fieldValue.isObject() || fieldValue.isArray()) {
            // Convert object field value to string
            updatedDataNode.put(fieldName, Jsons.serialize(fieldValue));
        } else {
            // Keep field value as is
            updatedDataNode.set(fieldName, fieldValue);
        }
    });

    if (flattenData) {
      final Map<String, JsonNode> data = MAPPER.convertValue(updatedDataNode, new TypeReference<>() {});
      json.setAll(data);
    } else {
      json.set(JavaBaseConstants.COLUMN_NAME_DATA, updatedDataNode);
    }
    printWriter.println(Jsons.serialize(json));
  }

  @Override
  protected void flushWriter() {
    printWriter.flush();
  }

  @Override
  protected void closeWriter() {
    printWriter.close();
  }

  public static BufferCreateFunction createBufferFunction(final S3JsonlFormatConfig config,
                                                          final Callable<BufferStorage> createStorageFunction) {
    return (final AirbyteStreamNameNamespacePair stream, final ConfiguredAirbyteCatalog catalog) -> {
      final CompressionType compressionType = config == null
          ? S3DestinationConstants.DEFAULT_COMPRESSION_TYPE
          : config.getCompressionType();

      final Flattening flattening = config == null
          ? Flattening.NO
          : config.getFlatteningType();
      return new JsonLSerializedBuffer(createStorageFunction.call(), compressionType != CompressionType.NO_COMPRESSION,
          flattening != Flattening.NO);
    };

  }

}
