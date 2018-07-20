/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.connect.hdfs;

import org.apache.hadoop.conf.Configuration;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.junit.After;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.confluent.connect.avro.AvroData;
import io.confluent.connect.hdfs.avro.AvroFormat;
import io.confluent.connect.hdfs.partitioner.DefaultPartitioner;
import io.confluent.connect.storage.StorageSinkTestBase;
import io.confluent.connect.storage.common.StorageCommonConfig;
import io.confluent.connect.storage.hive.schema.DefaultSchemaGenerator;
import io.confluent.connect.storage.partitioner.PartitionerConfig;

public class HdfsSinkConnectorTestBase extends StorageSinkTestBase {

  protected HdfsSinkConnectorConfig connectorConfig;
  protected Map<String, Object> parsedConfig;
  protected Configuration conf;
  protected String topicsDir;
  protected String logsDir;
  protected AvroData avroData;

  protected static final String TOPIC_WITH_DOTS = "topic.with.dots";
  protected static final TopicPartition TOPIC_WITH_DOTS_PARTITION = new TopicPartition(TOPIC_WITH_DOTS, PARTITION);

  @Override
  protected Map<String, String> createProps() {
    Map<String, String> props = super.createProps();
    url = "memory://";
    props.put(HdfsSinkConnectorConfig.HDFS_URL_CONFIG, url);
    props.put(StorageCommonConfig.STORE_URL_CONFIG, url);
    props.put(HdfsSinkConnectorConfig.FLUSH_SIZE_CONFIG, "3");
    props.put(
        StorageCommonConfig.STORAGE_CLASS_CONFIG,
        "io.confluent.connect.hdfs.storage.HdfsStorage"
    );
    props.put(HdfsSinkConnectorConfig.FORMAT_CLASS_CONFIG, AvroFormat.class.getName());
    props.put(
        PartitionerConfig.PARTITIONER_CLASS_CONFIG,
        DefaultPartitioner.class.getName()
    );
    props.put(PartitionerConfig.PARTITION_FIELD_NAME_CONFIG, "int");
    props.put(
        PartitionerConfig.PARTITION_DURATION_MS_CONFIG,
        String.valueOf(TimeUnit.HOURS.toMillis(1))
    );
    props.put(PartitionerConfig.PATH_FORMAT_CONFIG, "'year'=YYYY/'month'=MM/'day'=dd/'hour'=HH/");
    props.put(PartitionerConfig.LOCALE_CONFIG, "en");
    props.put(PartitionerConfig.TIMEZONE_CONFIG, "America/Los_Angeles");

    return props;
  }

  protected Struct createRecord(Schema schema, int ibase, float fbase) {
    return new Struct(schema)
        .put("boolean", true)
        .put("int", ibase)
        .put("long", (long) ibase)
        .put("float", fbase)
        .put("double", (double) fbase);
  }

  // Create a batch of records with incremental numeric field values. Total number of records is
  // given by 'size'.
  protected List<Struct> createRecordBatch(Schema schema, int size) {
    ArrayList<Struct> records = new ArrayList<>(size);
    int ibase = 16;
    float fbase = 12.2f;

    for (int i = 0; i < size; ++i) {
      records.add(createRecord(schema, ibase + i, fbase + i));
    }
    return records;
  }

  // Create a list of records by repeating the same record batch. Total number of records: 'batchesNum' x 'batchSize'
  protected List<Struct> createRecordBatches(Schema schema, int batchSize, int batchesNum) {
    ArrayList<Struct> records = new ArrayList<>();
    for (int i = 0; i < batchesNum; ++i) {
      records.addAll(createRecordBatch(schema, batchSize));
    }
    return records;
  }

  //@Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    connectorConfig = new HdfsSinkConnectorConfig(properties);
    parsedConfig = new HashMap<>(connectorConfig.plainValues());
    conf = connectorConfig.getHadoopConfiguration();
    topicsDir = connectorConfig.getString(StorageCommonConfig.TOPICS_DIR_CONFIG);
    logsDir = connectorConfig.getString(HdfsSinkConnectorConfig.LOGS_DIR_CONFIG);
    avroData = new AvroData(
        connectorConfig.getInt(HdfsSinkConnectorConfig.SCHEMA_CACHE_SIZE_CONFIG)
    );
  }

  @After
  @Override
  public void tearDown() throws Exception {
    super.tearDown();
  }
}
