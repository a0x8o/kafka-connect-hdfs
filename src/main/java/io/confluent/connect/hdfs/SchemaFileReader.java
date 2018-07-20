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
 */

package io.confluent.connect.hdfs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.kafka.connect.data.Schema;

import java.io.IOException;
import java.util.Collection;

// NOTE: DO NOT add or modify this class as it is maintained for compatibility
@Deprecated
public interface SchemaFileReader {
  Schema getSchema(Configuration conf, Path path) throws IOException;

  // NOTE: This method is no longer used and was only previously used in tests. It is safe to
  // provide a dummy implementation.
  Collection<Object> readData(Configuration conf, Path path) throws IOException;
}
