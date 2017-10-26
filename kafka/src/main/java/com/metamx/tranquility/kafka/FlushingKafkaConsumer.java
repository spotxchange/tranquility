/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.metamx.tranquility.kafka;

import com.google.common.base.Throwables;
import com.metamx.common.logger.Logger;
import com.metamx.tranquility.config.DataSourceConfig;
import com.metamx.tranquility.kafka.model.MessageCounters;
import com.metamx.tranquility.kafka.model.PropertiesBasedKafkaConfig;
import com.metamx.tranquility.kafka.writer.TranquilityEventWriter;
import com.metamx.tranquility.kafka.writer.WriterController;
import io.druid.concurrent.Execs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Spawns a number of threads to read messages from Kafka topics and write them by calling
 * WriterController.getWriter(topic).send(). Will periodically call WriterController.flushAll() and when this completes
 * will call ConsumerConnector.commitOffsets() to save the last written offset to ZooKeeper. This implementation
 * guarantees that any events in Kafka will be read at least once even in case of a failure condition but does not
 * guarantee that duplication will not occur.
 */
public class FlushingKafkaConsumer
{
  private static final Logger log = new Logger(FlushingKafkaConsumer.class);

  private final ExecutorService consumerExec;
  private final Thread commitThread;
  private final AtomicBoolean shutdown = new AtomicBoolean();

  // prevents reading the next event from Kafka while events are being flushed and offset is being committed to ZK
  private final ReentrantReadWriteLock commitLock = new ReentrantReadWriteLock();

  private final KafkaConsumer consumerConnector;
//  private final TopicFilter topicFilter;
  private final List<String> topics;
  private final int numThreads;
  private final int commitMillis;
  private final WriterController writerController;

  private Map<String, MessageCounters> previousMessageCounters = new HashMap<>();

  public FlushingKafkaConsumer(
      final PropertiesBasedKafkaConfig globalConfig,
      final Properties kafkaProperties,
      final Map<String, DataSourceConfig<PropertiesBasedKafkaConfig>> dataSourceConfigs,
      final WriterController writerController
  )
  {
    this.consumerConnector = getConsumerConnector(kafkaProperties);
    this.topics = Arrays.asList( "market.forecast");

    log.info("Kafka topic [%s]", this.topics);

    int defaultNumThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    this.numThreads = globalConfig.getConsumerNumThreads() > 0
                      ? globalConfig.getConsumerNumThreads()
                      : defaultNumThreads;

    this.commitMillis = globalConfig.getCommitPeriodMillis();
    this.writerController = writerController;
    this.consumerExec = Execs.multiThreaded(numThreads, "FlushingKafkaConsumer-%d");
    this.commitThread = new Thread(createCommitRunnable());
    this.commitThread.setName("FlushingKafkaConsumer-CommitThread");
    this.commitThread.setDaemon(true);
  }

  public void start()
  {
    commitThread.start();
    startConsumers();
  }

  public void stop()
  {
    if (shutdown.compareAndSet(false, true)) {
      log.info("Shutting down - attempting to flush buffers and commit final offsets");

      try {
        commitLock.writeLock().lockInterruptibly(); // prevent Kafka from consuming any more events
        try {
          writerController.flushAll(); // try to flush the remaining events to Druid
          writerController.stop();
          consumerConnector.commitSync(); // update commit offset
        }
        finally {
          commitLock.writeLock().unlock();
          consumerConnector.close();
          commitThread.interrupt();
          consumerExec.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        Throwables.propagate(e);
      }

      log.info("Finished clean shutdown.");
    }
  }

  public void join() throws InterruptedException
  {
    commitThread.join();
  }

  void commit() throws InterruptedException
  {
    commitLock.writeLock().lockInterruptibly();
    try {
      final long flushStartTime = System.currentTimeMillis();
      final Map<String, MessageCounters> messageCounters = writerController.flushAll(); // blocks until complete

      final long commitStartTime = System.currentTimeMillis();
      consumerConnector.commitSync();

      final long finishedTime = System.currentTimeMillis();
      Map<String, MessageCounters> countsSinceLastCommit = new HashMap();
      for (Map.Entry<String, MessageCounters> entry : messageCounters.entrySet()) {
        countsSinceLastCommit.put(
            entry.getKey(),
            entry.getValue().difference(previousMessageCounters.get(entry.getKey()))
        );
      }

      previousMessageCounters = messageCounters;

      log.info(
          "Flushed %s pending messages in %sms and committed offsets in %sms.",
          countsSinceLastCommit.isEmpty() ? "0" : countsSinceLastCommit,
          commitStartTime - flushStartTime,
          finishedTime - commitStartTime
      );
    }
    finally {
      commitLock.writeLock().unlock();
    }
  }

  private Runnable createCommitRunnable()
  {
    return new Runnable()
    {
      @Override
      public void run()
      {
        long lastFlushTime = System.currentTimeMillis();
        try {
          while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(Math.max(commitMillis - (System.currentTimeMillis() - lastFlushTime), 0));
            commit();
            lastFlushTime = System.currentTimeMillis();
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.info("Commit thread interrupted.");
        }
        catch (Throwable e) {
          log.error(e, "Commit thread failed!");
          throw Throwables.propagate(e);
        }
        finally {
          stop();
        }
      }
    };
  }

  private void startConsumers()
  {

    // For committing offset
//    consumer.commitSync();

    consumerConnector.subscribe(topics);

//    final List<KafkaStream<byte[], byte[]>> kafkaStreams = consumerConnector.createMessageStreamsByFilter(
//        topicFilter,
//        numThreads
//    );


      consumerExec.submit(
          new Runnable()
          {
            @Override
            public void run()
            {
              try {
//                final Iterator<MessageAndMetadata<byte[], byte[]>> kafkaIterator = kafkaStream.iterator();

                while (true) {
                  if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                  }

                  // Kafka consumer treats messages as consumed and updates in-memory last offset when the message
                  // is returned by next(). In order to guarantee at-least-once message delivery, we need to a) set
                  // autocommit enable to false so the consumer will not automatically commit offsets to Zookeeper, and
                  // b) synchronize calls of kafkaIterator.next() with the commit thread so that we don't read messages
                  // and then call consumerConnector.commitOffsets() before those messages have been flushed through
                  // Tranquility into the indexing service.
                  commitLock.readLock().lockInterruptibly();

                  try {
                    ConsumerRecords<byte[], byte[]> data = consumerConnector.poll(100);
                    TranquilityEventWriter writer = writerController.getWriter("market.forecast");

                    for (ConsumerRecord<byte[], byte[]> message : data) {
                      writer.send(message.value());
                    }
                  }
                  finally {
                    commitLock.readLock().unlock();
                  }


                }
              }
              catch (InterruptedException e) {
                log.info("Consumer thread interrupted.");
              }
              catch (Throwable e) {
                log.error(e, "Exception: ");
                throw Throwables.propagate(e);
              }
              finally {
                stop();
              }
            }
          }
      );
  }

  private static KafkaConsumer<byte[], byte[]> getConsumerConnector(final Properties props)
  {
    props.setProperty("enable.auto.commit", "false");
//    props.setProperty("auto.commit.enable", "false");
//
//    final ConsumerConfig config = new ConsumerConfig(props);
//    Preconditions.checkState(!config.autoCommitEnable(), "autocommit must be off");

    return new KafkaConsumer<>(props);
  }

  private static String buildTopicFilter(Map<String, DataSourceConfig<PropertiesBasedKafkaConfig>> dataSourceConfigs)
  {
    StringBuilder topicFilter = new StringBuilder();
    for (Map.Entry<String, DataSourceConfig<PropertiesBasedKafkaConfig>> entry : dataSourceConfigs.entrySet()) {
      topicFilter.append(String.format("(%s)|", entry.getValue().propertiesBasedConfig().getTopicPattern()));
    }

    return topicFilter.length() > 0 ? topicFilter.substring(0, topicFilter.length() - 1) : "";
  }
}
