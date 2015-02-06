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

package io.confluent.kafkarest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.confluent.kafkarest.entities.ConsumerRecord;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.ConsumerTimeoutException;
import kafka.message.MessageAndMetadata;

/**
 * State for tracking the progress of a single consumer read request.
 *
 * To support embedded formats that require translation between the format deserialized by the Kafka
 * decoder and the format returned in the ConsumerRecord entity sent back to the client, this class
 * uses two pairs of key-value generic type parameters: KafkaK/KafkaV is the format returned by the
 * Kafka consumer's decoder/deserializer, ClientK/ClientV is the format returned to the client in
 * the HTTP response. In some cases these may be identical.
 */
class ConsumerReadTask<KafkaK, KafkaV, ClientK, ClientV>
    implements Future<List<ConsumerRecord<ClientK, ClientV>>> {

  private static final Logger log = LoggerFactory.getLogger(ConsumerReadTask.class);

  private ConsumerState parent;
  private final long maxResponseBytes;
  private final ConsumerWorkerReadCallback<ClientK, ClientV> callback;
  private CountDownLatch finished;

  private ConsumerTopicState topicState;
  private ConsumerIterator<KafkaK, KafkaV> iter;
  private List<ConsumerRecord<ClientK, ClientV>> messages;
  private long bytesConsumed = 0;
  private final long started;

  // Expiration if this task is waiting, considering both the expiration of the whole task and
  // a single backoff, if one is in progress
  long waitExpiration;

  public ConsumerReadTask(ConsumerState parent, String topic, long maxBytes,
                          ConsumerWorkerReadCallback<ClientK, ClientV> callback) {
    this.parent = parent;
    this.maxResponseBytes = Math.min(
        maxBytes,
        parent.getConfig().getLong(KafkaRestConfig.CONSUMER_REQUEST_MAX_BYTES_CONFIG));
    this.callback = callback;
    this.finished = new CountDownLatch(1);

    started = parent.getConfig().getTime().milliseconds();
    topicState = parent.getOrCreateTopicState(topic);
    if (topicState == null) {
      finish();
      return;
    }
  }

  /**
   * Performs one iteration of reading from a consumer iterator.
   *
   * @return true if this read timed out, indicating the scheduler should back off
   */
  public boolean doPartialRead() {
    try {
      // Initial setup requires locking, which must be done on this thread.
      if (iter == null) {
        parent.startRead(topicState);
        iter = topicState.getIterator();

        messages = new Vector<ConsumerRecord<ClientK, ClientV>>();
        waitExpiration = 0;
      }

      boolean backoff = false;

      long startedIteration = parent.getConfig().getTime().milliseconds();
      final int requestTimeoutMs = parent.getConfig().getInt(
          KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG);
      try {
        // Read off as many messages as we can without triggering a timeout exception. The
        // consumer timeout should be set very small, so the expectation is that even in the
        // worst case, num_messages * consumer_timeout << request_timeout, so it's safe to only
        // check the elapsed time once this loop finishes.
        while (iter.hasNext()) {
          MessageAndMetadata<KafkaK, KafkaV> msg = iter.peek();
          ConsumerRecordAndSize<ClientK, ClientV> recordAndSize = parent.createConsumerRecord(msg);
          long roughMsgSize = recordAndSize.getSize();
          if (bytesConsumed + roughMsgSize > maxResponseBytes) {
            break;
          }

          iter.next();
          messages.add(recordAndSize.getRecord());
          bytesConsumed += roughMsgSize;
          topicState.getConsumedOffsets().put(msg.partition(), msg.offset());
        }
      } catch (ConsumerTimeoutException cte) {
        backoff = true;
      }

      long now = parent.getConfig().getTime().milliseconds();
      long elapsed = now - started;
      // Compute backoff based on starting time. This makes reasoning about when timeouts
      // should occur simpler for tests.
      int itbackoff
          = parent.getConfig().getInt(KafkaRestConfig.CONSUMER_ITERATOR_BACKOFF_MS_CONFIG);
      long backoffExpiration = startedIteration + itbackoff;
      long requestExpiration =
          started + parent.getConfig().getInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG);
      waitExpiration = Math.min(backoffExpiration, requestExpiration);

      if (elapsed >= requestTimeoutMs || bytesConsumed >= maxResponseBytes) {
        parent.finishRead(topicState);
        finish();
      }

      return backoff;
    } catch (Exception e) {
      parent.finishRead(topicState);
      finish();
      log.error("Unexpected exception in consumer read thread: ", e);
      return false;
    }
  }

  public void finish() {
    callback.onCompletion(messages);
    finished.countDown();
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return false;
  }

  @Override
  public boolean isCancelled() {
    return false;
  }

  @Override
  public boolean isDone() {
    return (finished.getCount() == 0);
  }

  @Override
  public List<ConsumerRecord<ClientK, ClientV>> get()
      throws InterruptedException, ExecutionException {
    finished.await();
    return messages;
  }

  @Override
  public List<ConsumerRecord<ClientK, ClientV>> get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    finished.await(timeout, unit);
    if (finished.getCount() > 0) {
      throw new TimeoutException();
    }
    return messages;
  }
}
