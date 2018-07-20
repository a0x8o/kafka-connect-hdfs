/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 **/

package io.confluent.connect.hdfs.parquet;

import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.confluent.connect.hdfs.DataWriter;
import io.confluent.connect.hdfs.FileUtils;
import io.confluent.connect.hdfs.HdfsSinkConnectorConfig;
import io.confluent.connect.hdfs.hive.HiveTestBase;
import io.confluent.connect.hdfs.hive.HiveTestUtils;
import io.confluent.connect.hdfs.partitioner.DailyPartitioner;
import io.confluent.connect.hdfs.partitioner.FieldPartitioner;
import io.confluent.connect.hdfs.partitioner.TimeUtils;
import io.confluent.connect.storage.hive.HiveConfig;
import io.confluent.connect.storage.partitioner.PartitionerConfig;

import static org.junit.Assert.assertEquals;

public class HiveIntegrationParquetTest extends HiveTestBase {
  private Map<String, String> localProps = new HashMap<>();

  @Override
  protected Map<String, String> createProps() {
    Map<String, String> props = super.createProps();
    props.put(HdfsSinkConnectorConfig.SHUTDOWN_TIMEOUT_CONFIG, "10000");
    props.put(HdfsSinkConnectorConfig.FORMAT_CLASS_CONFIG, ParquetFormat.class.getName());
    props.putAll(localProps);
    return props;
  }

  //@Before should be omitted in order to be able to add properties per test.
  public void setUp() throws Exception {
    super.setUp();
  }

  @Test
  public void testSyncWithHiveParquet() throws Exception {
    setUp();
    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData);
    hdfsWriter.recover(TOPIC_PARTITION);

    List<SinkRecord> sinkRecords = createSinkRecords(7);

    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();
    hdfsWriter.stop();

    localProps.put(HiveConfig.HIVE_INTEGRATION_CONFIG, "true");
    HdfsSinkConnectorConfig config = new HdfsSinkConnectorConfig(createProps());

    hdfsWriter = new DataWriter(config, context, avroData);
    hdfsWriter.syncWithHive();

    Schema schema = createSchema();
    Struct expectedRecord = createRecord(schema);
    List<String> expectedResult = new ArrayList<>();
    List<String> expectedColumnNames = new ArrayList<>();
    for (Field field : schema.fields()) {
      expectedColumnNames.add(field.name());
      expectedResult.add(String.valueOf(expectedRecord.get(field.name())));
    }

    Table table = hiveMetaStore.getTable(hiveDatabase, TOPIC);
    List<String> actualColumnNames = new ArrayList<>();
    for (FieldSchema column : table.getSd().getCols()) {
      actualColumnNames.add(column.getName());
    }
    assertEquals(expectedColumnNames, actualColumnNames);

    List<String> expectedPartitions = new ArrayList<>();
    String directory = TOPIC + "/" + "partition=" + String.valueOf(PARTITION);
    expectedPartitions.add(FileUtils.directoryName(url, topicsDir, directory));

    List<String> partitions = hiveMetaStore.listPartitions(hiveDatabase, TOPIC, (short)-1);

    assertEquals(expectedPartitions, partitions);

    hdfsWriter.close();
    hdfsWriter.stop();
  }

  @Test
  public void testHiveIntegrationParquet() throws Exception {
    localProps.put(HiveConfig.HIVE_INTEGRATION_CONFIG, "true");
    setUp();

    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData);
    hdfsWriter.recover(TOPIC_PARTITION);

    List<SinkRecord> sinkRecords = createSinkRecords(7);

    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();
    hdfsWriter.stop();

    Schema schema = createSchema();
    Table table = hiveMetaStore.getTable(hiveDatabase, TOPIC);
    List<String> expectedColumnNames = new ArrayList<>();
    for (Field field : schema.fields()) {
      expectedColumnNames.add(field.name());
    }

    List<String> actualColumnNames = new ArrayList<>();
    for (FieldSchema column : table.getSd().getCols()) {
      actualColumnNames.add(column.getName());
    }
    assertEquals(expectedColumnNames, actualColumnNames);

    List<String> expectedPartitions = new ArrayList<>();
    String directory = TOPIC + "/" + "partition=" + String.valueOf(PARTITION);
    expectedPartitions.add(FileUtils.directoryName(url, topicsDir, directory));

    List<String> partitions = hiveMetaStore.listPartitions(hiveDatabase, TOPIC, (short)-1);

    assertEquals(expectedPartitions, partitions);
  }

  @Test
  public void testHiveIntegrationFieldPartitionerParquet() throws Exception {
    localProps.put(HiveConfig.HIVE_INTEGRATION_CONFIG, "true");
    localProps.put(PartitionerConfig.PARTITIONER_CLASS_CONFIG, FieldPartitioner.class.getName());
    localProps.put(PartitionerConfig.PARTITION_FIELD_NAME_CONFIG, "int");
    setUp();
    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData);

    Schema schema = createSchema();
    List<Struct> records = createRecordBatches(schema, 3, 3);
    List<SinkRecord> sinkRecords = createSinkRecords(records, schema);

    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();
    hdfsWriter.stop();

    Table table = hiveMetaStore.getTable(hiveDatabase, TOPIC);

    List<String> expectedColumnNames = new ArrayList<>();
    for (Field field : schema.fields()) {
      expectedColumnNames.add(field.name());
    }

    List<String> actualColumnNames = new ArrayList<>();
    for (FieldSchema column : table.getSd().getCols()) {
      actualColumnNames.add(column.getName());
    }
    assertEquals(expectedColumnNames, actualColumnNames);

    List<String> partitionFieldNames = connectorConfig.getList(
        PartitionerConfig.PARTITION_FIELD_NAME_CONFIG
    );
    String partitionFieldName = partitionFieldNames.get(0);
    String directory1 = TOPIC + "/" + partitionFieldName + "=" + String.valueOf(16);
    String directory2 = TOPIC + "/" + partitionFieldName + "=" + String.valueOf(17);
    String directory3 = TOPIC + "/" + partitionFieldName + "=" + String.valueOf(18);

    List<String> expectedPartitions = new ArrayList<>();
    expectedPartitions.add(FileUtils.directoryName(url, topicsDir, directory1));
    expectedPartitions.add(FileUtils.directoryName(url, topicsDir, directory2));
    expectedPartitions.add(FileUtils.directoryName(url, topicsDir, directory3));

    List<String> partitions = hiveMetaStore.listPartitions(hiveDatabase, TOPIC, (short)-1);

    assertEquals(expectedPartitions, partitions);

    List<List<String>> expectedResults = new ArrayList<>();
    for (int i = 0; i < 3; ++i) {
      for (int j = 0; j < 3; ++j) {
        List<String> result = new ArrayList<>();
        for (Field field : schema.fields()) {
          result.add(String.valueOf(records.get(i).get(field.name())));
        }
        expectedResults.add(result);
      }
    }

    String result = HiveTestUtils.runHive(
        hiveExec,
        "SELECT * FROM " + hiveMetaStore.tableNameConverter(TOPIC)
    );
    String[] rows = result.split("\n");
    assertEquals(9, rows.length);
    for (int i = 0; i < rows.length; ++i) {
      String[] parts = HiveTestUtils.parseOutput(rows[i]);
      int j = 0;
      for (String expectedValue : expectedResults.get(i)) {
        assertEquals(expectedValue, parts[j++]);
      }
    }
  }

  @Test
  public void testHiveIntegrationFieldPartitionerParquetMultiple() throws Exception {
    localProps.put(HiveConfig.HIVE_INTEGRATION_CONFIG, "true");
    localProps.put(PartitionerConfig.PARTITIONER_CLASS_CONFIG, FieldPartitioner.class.getName());
    localProps.put(PartitionerConfig.PARTITION_FIELD_NAME_CONFIG, "country,state");
    setUp();
    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData);

    Schema schema = SchemaBuilder.struct()
        .field("count", Schema.INT64_SCHEMA)
        .field("country", Schema.STRING_SCHEMA)
        .field("state", Schema.OPTIONAL_STRING_SCHEMA)
        .build();

    List<Struct> records = Arrays.asList(
        new Struct(schema)
            .put("count", 1L)
            .put("country", "us")
            .put("state", "tx"),
        new Struct(schema)
            .put("count", 1L)
            .put("country", "us")
            .put("state", "ca"),
        new Struct(schema)
            .put("count", 1L)
            .put("country", "mx")
            .put("state", null)
    );
    List<SinkRecord> sinkRecords = createSinkRecords(records, schema);

    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();
    hdfsWriter.stop();

    Table table = hiveMetaStore.getTable(hiveDatabase, TOPIC);

    List<String> expectedColumnNames = new ArrayList<>();
    for (Field field : schema.fields()) {
      expectedColumnNames.add(field.name());
    }

    List<String> actualColumnNames = new ArrayList<>();
    for (FieldSchema column : table.getSd().getCols()) {
      actualColumnNames.add(column.getName());
    }
    assertEquals(expectedColumnNames, actualColumnNames);

    List<String> expectedPartitions = new ArrayList<>();
    expectedPartitions.add(FileUtils.directoryName(url, topicsDir, "test-topic/country=mx/state=null"));
    expectedPartitions.add(FileUtils.directoryName(url, topicsDir, "test-topic/country=us/state=ca"));
    expectedPartitions.add(FileUtils.directoryName(url, topicsDir, "test-topic/country=us/state=tx"));

    List<String> partitions = hiveMetaStore.listPartitions(hiveDatabase, TOPIC, (short)-1);

    assertEquals(expectedPartitions, partitions);

    List<List<String>> expectedResults = Arrays.asList(
        Arrays.asList("1", "mx", "NULL"),
        Arrays.asList("1", "us", "ca"),
        Arrays.asList("1", "us", "tx")
    );

    String result = HiveTestUtils.runHive(
        hiveExec,
        "SELECT * FROM " +
            hiveMetaStore.tableNameConverter(TOPIC) +
            " order by country, state"
    );
    String[] rows = result.split("\n");
    assertEquals(expectedResults.size(), rows.length);
    for (int i = 0; i < rows.length; ++i) {
      String[] parts = HiveTestUtils.parseOutput(rows[i]);
      int j = 0;
      for (String expectedValue : expectedResults.get(i)) {
        assertEquals(expectedValue, parts[j++]);
      }
    }
  }

  @Test
  public void testHiveIntegrationTimeBasedPartitionerParquet() throws Exception {
    localProps.put(HiveConfig.HIVE_INTEGRATION_CONFIG, "true");
    localProps.put(PartitionerConfig.PARTITIONER_CLASS_CONFIG, DailyPartitioner.class.getName());
    setUp();
    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData);

    Schema schema = createSchema();
    List<Struct> records = createRecordBatches(schema, 3, 3);
    List<SinkRecord> sinkRecords = createSinkRecords(records, schema);

    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();
    hdfsWriter.stop();

    Table table = hiveMetaStore.getTable(hiveDatabase, TOPIC);

    List<String> expectedColumnNames = new ArrayList<>();
    for (Field field : schema.fields()) {
      expectedColumnNames.add(field.name());
    }

    List<String> actualColumnNames = new ArrayList<>();
    for (FieldSchema column : table.getSd().getCols()) {
      actualColumnNames.add(column.getName());
    }
    assertEquals(expectedColumnNames, actualColumnNames);

    String pathFormat = "'year'=YYYY/'month'=MM/'day'=dd";
    DateTime dateTime = DateTime.now(DateTimeZone.forID("America/Los_Angeles"));
    String encodedPartition = TimeUtils
        .encodeTimestamp(TimeUnit.HOURS.toMillis(24), pathFormat, "America/Los_Angeles",
                         dateTime.getMillis());
    String directory =  TOPIC + "/" + encodedPartition;
    List<String> expectedPartitions = new ArrayList<>();
    expectedPartitions.add(FileUtils.directoryName(url, topicsDir, directory));

    List<String> partitions = hiveMetaStore.listPartitions(hiveDatabase, TOPIC, (short)-1);
    assertEquals(expectedPartitions, partitions);

    ArrayList<String> partitionFields = new ArrayList<>();
    String[] groups = encodedPartition.split("/");
    for (String group : groups) {
      String field = group.split("=")[1];
      partitionFields.add(field);
    }

    List<List<String>> expectedResults = new ArrayList<>();
    for (int j = 0; j < 3; ++j) {
      for (int i = 0; i < 3; ++i) {
        List<String> result = Arrays.asList("true",
                                            String.valueOf(16 + i),
                                            String.valueOf((long) (16 + i)),
                                            String.valueOf(12.2f + i),
                                            String.valueOf((double) (12.2f + i)),
                                            partitionFields.get(0),
                                            partitionFields.get(1),
                                            partitionFields.get(2));
        expectedResults.add(result);
      }
    }

    String result = HiveTestUtils.runHive(
        hiveExec,
        "SELECT * FROM " + hiveMetaStore.tableNameConverter(TOPIC)
    );
    String[] rows = result.split("\n");
    assertEquals(9, rows.length);
    for (int i = 0; i < rows.length; ++i) {
      String[] parts = HiveTestUtils.parseOutput(rows[i]);
      int j = 0;
      for (String expectedValue : expectedResults.get(i)) {
        assertEquals(expectedValue, parts[j++]);
      }
    }
  }
}
