/**
 * Copyright 2017 Confluent Inc.
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

package io.confluent.connect.hdfs.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import io.confluent.connect.hdfs.DataFileReader;

public class JsonDataFileReader implements DataFileReader {

  private static final ObjectMapper mapper = new ObjectMapper();

  @Override
  public Collection<Object> readData(Configuration conf, Path path) throws IOException {
    String uri = "hdfs://127.0.0.1:9001";
    FileSystem fs;
    try {
      fs = FileSystem.get(new URI(uri), conf);
    } catch (URISyntaxException e) {
      throw new IOException("Failed to create URI: " + uri);
    }
    InputStream in = fs.open(path);
    JsonParser reader = mapper.getFactory().createParser(in);

    ArrayList<Object> records = new ArrayList<>();
    Iterator<Object> iterator = reader.readValuesAs(Object.class);
    while (iterator.hasNext()) {
      records.add(iterator.next());
    }
    return records;
  }
}
