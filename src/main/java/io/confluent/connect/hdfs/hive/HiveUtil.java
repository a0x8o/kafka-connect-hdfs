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

package io.confluent.connect.hdfs.hive;

import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.kafka.connect.data.Schema;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.confluent.connect.hdfs.HdfsSinkConnectorConfig;
import io.confluent.connect.hdfs.partitioner.Partitioner;
import io.confluent.connect.storage.common.StorageCommonConfig;

// NOTE: DO NOT add or modify this class as it is maintained for compatibility
@Deprecated
@SuppressFBWarnings("NM_SAME_SIMPLE_NAME_AS_SUPERCLASS")
public abstract class HiveUtil extends io.confluent.connect.storage.hive.HiveUtil {

  public HiveUtil(HdfsSinkConnectorConfig connectorConfig, HiveMetaStore hiveMetaStore) {
    super(connectorConfig, hiveMetaStore);
    String urlKey;

    urlKey = connectorConfig.getString(StorageCommonConfig.STORE_URL_CONFIG);
    if (urlKey == null || urlKey.equals(StorageCommonConfig.STORE_URL_DEFAULT)) {
      urlKey = connectorConfig.getString(HdfsSinkConnectorConfig.HDFS_URL_CONFIG);
    }

    this.url = urlKey;
  }

  @Override
  public void createTable(
      String database,
      String tableName,
      Schema schema,
      io.confluent.connect.storage.partitioner.Partitioner<FieldSchema> partitioner
  ) {
    createTable(database, tableName, schema, (Partitioner) partitioner);
  }

  public abstract void createTable(
      String database,
      String tableName,
      Schema schema,
      Partitioner partitioner
  );

}
