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

package io.confluent.connect.hdfs;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.errors.IllegalWorkerStateException;
import org.apache.kafka.connect.errors.SchemaProjectorException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTaskContext;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import io.confluent.common.utils.Time;
import io.confluent.connect.avro.AvroData;
import io.confluent.connect.hdfs.errors.HiveMetaStoreException;
import io.confluent.connect.hdfs.filter.CommittedFileFilter;
import io.confluent.connect.hdfs.filter.TopicPartitionCommittedFileFilter;
import io.confluent.connect.hdfs.hive.HiveMetaStore;
import io.confluent.connect.hdfs.hive.HiveUtil;
import io.confluent.connect.hdfs.partitioner.Partitioner;
import io.confluent.connect.hdfs.storage.HdfsStorage;
import io.confluent.connect.storage.common.StorageCommonConfig;
import io.confluent.connect.storage.hive.HiveConfig;
import io.confluent.connect.storage.partitioner.PartitionerConfig;
import io.confluent.connect.storage.partitioner.TimeBasedPartitioner;
import io.confluent.connect.storage.partitioner.TimestampExtractor;
import io.confluent.connect.storage.schema.StorageSchemaCompatibility;
import io.confluent.connect.storage.wal.WAL;

public class TopicPartitionWriter {
  private static final Logger log = LoggerFactory.getLogger(TopicPartitionWriter.class);
  private static final TimestampExtractor WALLCLOCK =
      new TimeBasedPartitioner.WallclockTimestampExtractor();
  private final io.confluent.connect.storage.format.RecordWriterProvider<HdfsSinkConnectorConfig>
      newWriterProvider;
  private final String zeroPadOffsetFormat;
  private final boolean hiveIntegration;
  private final Time time;
  private final HdfsStorage storage;
  private final WAL wal;
  private final Map<String, String> tempFiles;
  private final Map<String, io.confluent.connect.storage.format.RecordWriter> writers;
  private final TopicPartition tp;
  private final Partitioner partitioner;
  private final TimestampExtractor timestampExtractor;
  private final boolean isWallclockBased;
  private final String url;
  private final String topicsDir;
  private State state;
  private final Queue<SinkRecord> buffer;
  private boolean recovered;
  private final SinkTaskContext context;
  private int recordCounter;
  private final int flushSize;
  private final long rotateIntervalMs;
  private Long lastRotate;
  private final long rotateScheduleIntervalMs;
  private long nextScheduledRotate;
  // This is one case where we cannot simply wrap the old or new RecordWriterProvider with the
  // other because they have incompatible requirements for some methods -- one requires the Hadoop
  // config + extra parameters, the other requires the ConnectorConfig and doesn't get the other
  // extra parameters. Instead, we have to (optionally) store one of each and use whichever one is
  // non-null.
  private final RecordWriterProvider writerProvider;
  private final HdfsSinkConnectorConfig connectorConfig;
  private final AvroData avroData;
  private final Set<String> appended;
  private long offset;
  private final Map<String, Long> startOffsets;
  private final Map<String, Long> offsets;
  private final long timeoutMs;
  private long failureTime;
  private final StorageSchemaCompatibility compatibility;
  private Schema currentSchema;
  private final String extension;
  private final DateTimeZone timeZone;
  private final String hiveDatabase;
  private final HiveMetaStore hiveMetaStore;
  private final io.confluent.connect.storage.format.SchemaFileReader<HdfsSinkConnectorConfig, Path>
      schemaFileReader;
  private final HiveUtil hive;
  private final ExecutorService executorService;
  private final Queue<Future<Void>> hiveUpdateFutures;
  private final Set<String> hivePartitions;

  public TopicPartitionWriter(
      TopicPartition tp,
      HdfsStorage storage,
      RecordWriterProvider writerProvider,
      io.confluent.connect.storage.format.RecordWriterProvider<HdfsSinkConnectorConfig>
          newWriterProvider,
      Partitioner partitioner,
      HdfsSinkConnectorConfig connectorConfig,
      SinkTaskContext context,
      AvroData avroData,
      Time time
  ) {
    this(
        tp,
        storage,
        writerProvider,
        newWriterProvider,
        partitioner,
        connectorConfig,
        context,
        avroData,
        null,
        null,
        null,
        null,
        null,
        time
    );
  }

  public TopicPartitionWriter(
      TopicPartition tp,
      HdfsStorage storage,
      RecordWriterProvider writerProvider,
      io.confluent.connect.storage.format.RecordWriterProvider<HdfsSinkConnectorConfig>
          newWriterProvider,
      Partitioner partitioner,
      HdfsSinkConnectorConfig connectorConfig,
      SinkTaskContext context,
      AvroData avroData,
      HiveMetaStore hiveMetaStore,
      HiveUtil hive,
      io.confluent.connect.storage.format.SchemaFileReader<HdfsSinkConnectorConfig, Path>
          schemaFileReader,
      ExecutorService executorService,
      Queue<Future<Void>> hiveUpdateFutures,
      Time time
  ) {
    this.time = time;
    this.tp = tp;
    this.context = context;
    this.avroData = avroData;
    this.storage = storage;
    this.writerProvider = writerProvider;
    this.newWriterProvider = newWriterProvider;
    this.partitioner = partitioner;
    TimestampExtractor timestampExtractor = null;
    if (partitioner instanceof DataWriter.PartitionerWrapper) {
      io.confluent.connect.storage.partitioner.Partitioner<?> inner =
          ((DataWriter.PartitionerWrapper) partitioner).partitioner;
      if (TimeBasedPartitioner.class.isAssignableFrom(inner.getClass())) {
        timestampExtractor = ((TimeBasedPartitioner) inner).getTimestampExtractor();
      }
    }
    this.timestampExtractor = timestampExtractor != null ? timestampExtractor : WALLCLOCK;
    this.isWallclockBased = TimeBasedPartitioner.WallclockTimestampExtractor.class.isAssignableFrom(
        this.timestampExtractor.getClass()
    );
    this.url = storage.url();
    this.connectorConfig = storage.conf();
    this.schemaFileReader = schemaFileReader;

    topicsDir = connectorConfig.getString(StorageCommonConfig.TOPICS_DIR_CONFIG);
    flushSize = connectorConfig.getInt(HdfsSinkConnectorConfig.FLUSH_SIZE_CONFIG);
    rotateIntervalMs = connectorConfig.getLong(HdfsSinkConnectorConfig.ROTATE_INTERVAL_MS_CONFIG);
    rotateScheduleIntervalMs = connectorConfig.getLong(HdfsSinkConnectorConfig
        .ROTATE_SCHEDULE_INTERVAL_MS_CONFIG);
    timeoutMs = connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG);
    compatibility = StorageSchemaCompatibility.getCompatibility(
        connectorConfig.getString(HiveConfig.SCHEMA_COMPATIBILITY_CONFIG));

    String logsDir = connectorConfig.getString(HdfsSinkConnectorConfig.LOGS_DIR_CONFIG);
    wal = storage.wal(logsDir, tp);

    buffer = new LinkedList<>();
    writers = new HashMap<>();
    tempFiles = new HashMap<>();
    appended = new HashSet<>();
    startOffsets = new HashMap<>();
    offsets = new HashMap<>();
    state = State.RECOVERY_STARTED;
    failureTime = -1L;
    offset = -1L;
    if (writerProvider != null) {
      extension = writerProvider.getExtension();
    } else if (newWriterProvider != null) {
      extension = newWriterProvider.getExtension();
    } else {
      throw new ConnectException(
          "Invalid state: either old or new RecordWriterProvider must be provided"
      );
    }
    zeroPadOffsetFormat = "%0"
        + connectorConfig.getInt(HdfsSinkConnectorConfig.FILENAME_OFFSET_ZERO_PAD_WIDTH_CONFIG)
        + "d";

    hiveIntegration = connectorConfig.getBoolean(HiveConfig.HIVE_INTEGRATION_CONFIG);
    if (hiveIntegration) {
      hiveDatabase = connectorConfig.getString(HiveConfig.HIVE_DATABASE_CONFIG);
    } else {
      hiveDatabase = null;
    }

    this.hiveMetaStore = hiveMetaStore;
    this.hive = hive;
    this.executorService = executorService;
    this.hiveUpdateFutures = hiveUpdateFutures;
    hivePartitions = new HashSet<>();

    if (rotateScheduleIntervalMs > 0) {
      timeZone = DateTimeZone.forID(connectorConfig.getString(PartitionerConfig.TIMEZONE_CONFIG));
    } else {
      timeZone = null;
    }

    // Initialize rotation timers
    updateRotationTimers(null);
  }

  @SuppressWarnings("fallthrough")
  public boolean recover() {
    try {
      switch (state) {
        case RECOVERY_STARTED:
          log.info("Started recovery for topic partition {}", tp);
          pause();
          nextState();
        case RECOVERY_PARTITION_PAUSED:
          applyWAL();
          nextState();
        case WAL_APPLIED:
          truncateWAL();
          nextState();
        case WAL_TRUNCATED:
          resetOffsets();
          nextState();
        case OFFSET_RESET:
          resume();
          nextState();
          log.info("Finished recovery for topic partition {}", tp);
          break;
        default:
          log.error(
              "{} is not a valid state to perform recovery for topic partition {}.",
              state,
              tp
          );
      }
    } catch (ConnectException e) {
      log.error("Recovery failed at state {}", state, e);
      setRetryTimeout(timeoutMs);
      return false;
    }
    return true;
  }

  private void updateRotationTimers(SinkRecord currentRecord) {
    long now = time.milliseconds();
    // Wallclock-based partitioners should be independent of the record argument.
    lastRotate = isWallclockBased
                 ? (Long) now
                 : currentRecord != null ? timestampExtractor.extract(currentRecord) : null;
    if (log.isDebugEnabled() && rotateIntervalMs > 0) {
      log.debug(
          "Update last rotation timer. Next rotation for {} will be in {}ms",
          tp,
          rotateIntervalMs
      );
    }
    if (rotateScheduleIntervalMs > 0) {
      nextScheduledRotate = DateTimeUtils.getNextTimeAdjustedByDay(
          now,
          rotateScheduleIntervalMs,
          timeZone
      );
      if (log.isDebugEnabled()) {
        log.debug(
            "Update scheduled rotation timer. Next rotation for {} will be at {}",
            tp,
            new DateTime(nextScheduledRotate).withZone(timeZone).toString()
        );
      }
    }
  }

  @SuppressWarnings("fallthrough")
  public void write() {
    long now = time.milliseconds();
    SinkRecord currentRecord = null;
    if (failureTime > 0 && now - failureTime < timeoutMs) {
      return;
    }
    if (state.compareTo(State.WRITE_STARTED) < 0) {
      boolean success = recover();
      if (!success) {
        return;
      }
      updateRotationTimers(null);
    }
    while (!buffer.isEmpty()) {
      try {
        switch (state) {
          case WRITE_STARTED:
            pause();
            nextState();
          case WRITE_PARTITION_PAUSED:
            if (currentSchema == null) {
              if (compatibility != StorageSchemaCompatibility.NONE && offset != -1) {
                String topicDir = FileUtils.topicDirectory(url, topicsDir, tp.topic());
                CommittedFileFilter filter = new TopicPartitionCommittedFileFilter(tp);
                FileStatus fileStatusWithMaxOffset = FileUtils.fileStatusWithMaxOffset(
                    storage,
                    new Path(topicDir),
                    filter
                );
                if (fileStatusWithMaxOffset != null) {
                  currentSchema = schemaFileReader.getSchema(
                      connectorConfig,
                      fileStatusWithMaxOffset.getPath()
                  );
                }
              }
            }
            SinkRecord record = buffer.peek();
            currentRecord = record;
            Schema valueSchema = record.valueSchema();
            if ((recordCounter <= 0 && currentSchema == null && valueSchema != null)
                || compatibility.shouldChangeSchema(record, null, currentSchema)) {
              currentSchema = valueSchema;
              if (hiveIntegration) {
                createHiveTable();
                alterHiveSchema();
              }
              if (recordCounter > 0) {
                nextState();
              } else {
                break;
              }
            } else {
              if (shouldRotateAndMaybeUpdateTimers(currentRecord, now)) {
                log.info(
                    "Starting commit and rotation for topic partition {} with start offsets {} "
                        + "and end offsets {}",
                    tp,
                    startOffsets,
                    offsets
                );
                nextState();
                // Fall through and try to rotate immediately
              } else {
                SinkRecord projectedRecord = compatibility.project(record, null, currentSchema);
                writeRecord(projectedRecord);
                buffer.poll();
                break;
              }
            }
          case SHOULD_ROTATE:
            updateRotationTimers(currentRecord);
            closeTempFile();
            nextState();
          case TEMP_FILE_CLOSED:
            appendToWAL();
            nextState();
          case WAL_APPENDED:
            commitFile();
            nextState();
          case FILE_COMMITTED:
            setState(State.WRITE_PARTITION_PAUSED);
            break;
          default:
            log.error("{} is not a valid state to write record for topic partition {}.", state, tp);
        }
      } catch (SchemaProjectorException | IllegalWorkerStateException | HiveMetaStoreException e) {
        throw new RuntimeException(e);
      } catch (ConnectException e) {
        log.error("Exception on topic partition {}: ", tp, e);
        failureTime = time.milliseconds();
        setRetryTimeout(timeoutMs);
        break;
      }
    }
    if (buffer.isEmpty()) {
      // committing files after waiting for rotateIntervalMs time but less than flush.size
      // records available
      if (recordCounter > 0 && shouldRotateAndMaybeUpdateTimers(currentRecord, now)) {
        log.info(
            "committing files after waiting for rotateIntervalMs time but less than flush.size "
                + "records available."
        );
        updateRotationTimers(currentRecord);

        try {
          closeTempFile();
          appendToWAL();
          commitFile();
        } catch (ConnectException e) {
          log.error("Exception on topic partition {}: ", tp, e);
          failureTime = time.milliseconds();
          setRetryTimeout(timeoutMs);
        }
      }

      resume();
      state = State.WRITE_STARTED;
    }
  }

  public void close() throws ConnectException {
    log.debug("Closing TopicPartitionWriter {}", tp);
    List<Exception> exceptions = new ArrayList<>();
    for (String encodedPartition : tempFiles.keySet()) {
      try {
        if (writers.containsKey(encodedPartition)) {
          log.debug("Discarding in progress tempfile {} for {} {}",
              tempFiles.get(encodedPartition), tp, encodedPartition
          );
          closeTempFile(encodedPartition);
          deleteTempFile(encodedPartition);
        }
      } catch (DataException e) {
        log.error(
            "Error discarding temp file {} for {} {} when closing TopicPartitionWriter:",
            tempFiles.get(encodedPartition),
            tp,
            encodedPartition,
            e
        );
      }
    }

    writers.clear();

    try {
      wal.close();
    } catch (ConnectException e) {
      log.error("Error closing {}.", wal.getLogFile(), e);
      exceptions.add(e);
    }
    startOffsets.clear();
    offsets.clear();

    if (exceptions.size() != 0) {
      StringBuilder sb = new StringBuilder();
      for (Exception exception : exceptions) {
        sb.append(exception.getMessage());
        sb.append("\n");
      }
      throw new ConnectException("Error closing writer: " + sb.toString());
    }
  }

  public void buffer(SinkRecord sinkRecord) {
    buffer.add(sinkRecord);
  }

  public long offset() {
    return offset;
  }

  Map<String, io.confluent.connect.storage.format.RecordWriter> getWriters() {
    return writers;
  }

  public Map<String, String> getTempFiles() {
    return tempFiles;
  }

  private String getDirectory(String encodedPartition) {
    return partitioner.generatePartitionedPath(tp.topic(), encodedPartition);
  }

  private void nextState() {
    state = state.next();
  }

  private void setState(State state) {
    this.state = state;
  }

  private boolean shouldRotateAndMaybeUpdateTimers(SinkRecord currentRecord, long now) {
    Long currentTimestamp = null;
    if (isWallclockBased) {
      currentTimestamp = now;
    } else if (currentRecord != null) {
      currentTimestamp = timestampExtractor.extract(currentRecord);
      lastRotate = lastRotate == null ? currentTimestamp : lastRotate;
    }

    boolean periodicRotation = rotateIntervalMs > 0
        && currentTimestamp != null
        && lastRotate != null
        && currentTimestamp - lastRotate >= rotateIntervalMs;
    boolean scheduledRotation = rotateScheduleIntervalMs > 0 && now >= nextScheduledRotate;
    boolean messageSizeRotation = recordCounter >= flushSize;

    return periodicRotation || scheduledRotation || messageSizeRotation;
  }

  private void readOffset() throws ConnectException {
    String path = FileUtils.topicDirectory(url, topicsDir, tp.topic());
    CommittedFileFilter filter = new TopicPartitionCommittedFileFilter(tp);
    FileStatus fileStatusWithMaxOffset = FileUtils.fileStatusWithMaxOffset(
        storage,
        new Path(path),
        filter
    );
    if (fileStatusWithMaxOffset != null) {
      offset = FileUtils.extractOffset(fileStatusWithMaxOffset.getPath().getName()) + 1;
    }
  }

  private void pause() {
    context.pause(tp);
  }

  private void resume() {
    context.resume(tp);
  }

  private io.confluent.connect.storage.format.RecordWriter getWriter(
      SinkRecord record,
      String encodedPartition
  ) throws ConnectException {
    if (writers.containsKey(encodedPartition)) {
      return writers.get(encodedPartition);
    }
    String tempFile = getTempFile(encodedPartition);

    final io.confluent.connect.storage.format.RecordWriter writer;
    try {
      if (writerProvider != null) {
        writer = new OldRecordWriterWrapper(
            writerProvider.getRecordWriter(
                connectorConfig.getHadoopConfiguration(),
                tempFile,
                record,
                avroData
            )
        );
      } else if (newWriterProvider != null) {
        writer = newWriterProvider.getRecordWriter(connectorConfig, tempFile);
      } else {
        throw new ConnectException(
            "Invalid state: either old or new RecordWriterProvider must be provided"
        );
      }
    } catch (IOException e) {
      throw new ConnectException("Couldn't create RecordWriter", e);
    }

    writers.put(encodedPartition, writer);
    if (hiveIntegration && !hivePartitions.contains(encodedPartition)) {
      addHivePartition(encodedPartition);
      hivePartitions.add(encodedPartition);
    }
    return writer;
  }

  private String getTempFile(String encodedPartition) {
    String tempFile;
    if (tempFiles.containsKey(encodedPartition)) {
      tempFile = tempFiles.get(encodedPartition);
    } else {
      String directory = HdfsSinkConnectorConstants.TEMPFILE_DIRECTORY
          + getDirectory(encodedPartition);
      tempFile = FileUtils.tempFileName(url, topicsDir, directory, extension);
      tempFiles.put(encodedPartition, tempFile);
    }
    return tempFile;
  }

  private void applyWAL() throws ConnectException {
    if (!recovered) {
      wal.apply();
    }
  }

  private void truncateWAL() throws ConnectException {
    if (!recovered) {
      wal.truncate();
    }
  }

  private void resetOffsets() throws ConnectException {
    if (!recovered) {
      readOffset();
      // Note that we must *always* request that we seek to an offset here. Currently the
      // framework will still commit Kafka offsets even though we track our own (see KAFKA-3462),
      // which can result in accidentally using that offset if one was committed but no files
      // were rolled to their final location in HDFS (i.e. some data was accepted, written to a
      // tempfile, but then that tempfile was discarded). To protect against this, even if we
      // just want to start at offset 0 or reset to the earliest offset, we specify that
      // explicitly to forcibly override any committed offsets.
      if (offset > 0) {
        log.debug("Resetting offset for {} to {}", tp, offset);
        context.offset(tp, offset);
      } else {
        // The offset was not found, so rather than forcibly set the offset to 0 we let the
        // consumer decide where to start based upon standard consumer offsets (if available)
        // or the consumer's `auto.offset.reset` configuration
        log.debug("Resetting offset for {} based upon existing consumer group offsets or, if "
                  + "there are none, the consumer's 'auto.offset.reset' value.",
            tp);
      }
      recovered = true;
    }
  }

  private void writeRecord(SinkRecord record) {
    if (offset == -1) {
      offset = record.kafkaOffset();
    }

    String encodedPartition = partitioner.encodePartition(record);
    io.confluent.connect.storage.format.RecordWriter writer = getWriter(record, encodedPartition);
    writer.write(record);

    if (!startOffsets.containsKey(encodedPartition)) {
      startOffsets.put(encodedPartition, record.kafkaOffset());
    }
    offsets.put(encodedPartition, record.kafkaOffset());
    recordCounter++;
  }

  private void closeTempFile(String encodedPartition) {
    if (writers.containsKey(encodedPartition)) {
      io.confluent.connect.storage.format.RecordWriter writer = writers.get(encodedPartition);
      writer.close();
      writers.remove(encodedPartition);
    }
  }

  private void closeTempFile() {
    for (String encodedPartition : tempFiles.keySet()) {
      closeTempFile(encodedPartition);
    }
  }

  private void appendToWAL(String encodedPartition) {
    String tempFile = tempFiles.get(encodedPartition);
    if (appended.contains(tempFile)) {
      return;
    }
    if (!startOffsets.containsKey(encodedPartition)) {
      return;
    }
    long startOffset = startOffsets.get(encodedPartition);
    long endOffset = offsets.get(encodedPartition);
    String directory = getDirectory(encodedPartition);
    String committedFile = FileUtils.committedFileName(
        url,
        topicsDir,
        directory,
        tp,
        startOffset,
        endOffset,
        extension,
        zeroPadOffsetFormat
    );
    wal.append(tempFile, committedFile);
    appended.add(tempFile);
  }

  private void appendToWAL() {
    beginAppend();
    for (String encodedPartition : tempFiles.keySet()) {
      appendToWAL(encodedPartition);
    }
    endAppend();
  }

  private void beginAppend() {
    if (!appended.contains(WAL.beginMarker)) {
      wal.append(WAL.beginMarker, "");
    }
  }

  private void endAppend() {
    if (!appended.contains(WAL.endMarker)) {
      wal.append(WAL.endMarker, "");
    }
  }

  private void commitFile() {
    appended.clear();
    for (String encodedPartition : tempFiles.keySet()) {
      commitFile(encodedPartition);
    }
  }

  private void commitFile(String encodedPartition) {
    if (!startOffsets.containsKey(encodedPartition)) {
      return;
    }
    long startOffset = startOffsets.get(encodedPartition);
    long endOffset = offsets.get(encodedPartition);
    String tempFile = tempFiles.get(encodedPartition);
    String directory = getDirectory(encodedPartition);
    String committedFile = FileUtils.committedFileName(
        url,
        topicsDir,
        directory,
        tp,
        startOffset,
        endOffset,
        extension,
        zeroPadOffsetFormat
    );

    String directoryName = FileUtils.directoryName(url, topicsDir, directory);
    if (!storage.exists(directoryName)) {
      storage.create(directoryName);
    }
    storage.commit(tempFile, committedFile);
    startOffsets.remove(encodedPartition);
    offsets.remove(encodedPartition);
    offset = offset + recordCounter;
    recordCounter = 0;
    log.info("Committed {} for {}", committedFile, tp);
  }

  private void deleteTempFile(String encodedPartition) {
    storage.delete(tempFiles.get(encodedPartition));
  }

  private void setRetryTimeout(long timeoutMs) {
    context.timeout(timeoutMs);
  }

  private void createHiveTable() {
    Future<Void> future = executorService.submit(new Callable<Void>() {
      @Override
      public Void call() throws HiveMetaStoreException {
        try {
          hive.createTable(hiveDatabase, tp.topic(), currentSchema, partitioner);
        } catch (Throwable e) {
          log.error("Creating Hive table threw unexpected error", e);
        }
        return null;
      }
    });
    hiveUpdateFutures.add(future);
  }

  private void alterHiveSchema() {
    Future<Void> future = executorService.submit(new Callable<Void>() {
      @Override
      public Void call() throws HiveMetaStoreException {
        try {
          hive.alterSchema(hiveDatabase, tp.topic(), currentSchema);
        } catch (Throwable e) {
          log.error("Altering Hive schema threw unexpected error", e);
        }
        return null;
      }
    });
    hiveUpdateFutures.add(future);
  }

  private void addHivePartition(final String location) {
    Future<Void> future = executorService.submit(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        try {
          hiveMetaStore.addPartition(hiveDatabase, tp.topic(), location);
        } catch (Throwable e) {
          log.error("Adding Hive partition threw unexpected error", e);
        }
        return null;
      }
    });
    hiveUpdateFutures.add(future);
  }

  private enum State {
    RECOVERY_STARTED,
    RECOVERY_PARTITION_PAUSED,
    WAL_APPLIED,
    WAL_TRUNCATED,
    OFFSET_RESET,
    WRITE_STARTED,
    WRITE_PARTITION_PAUSED,
    SHOULD_ROTATE,
    TEMP_FILE_CLOSED,
    WAL_APPENDED,
    FILE_COMMITTED;

    private static State[] vals = values();

    public State next() {
      return vals[(this.ordinal() + 1) % vals.length];
    }
  }
}
