/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.vertx.ext.hawkular.impl;

import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.spi.metrics.EventBusMetrics;
import org.hawkular.metrics.client.common.MetricType;
import org.hawkular.metrics.client.common.SingleMetric;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author Thomas Segismont
 */
public class EventBusMetricsImpl implements EventBusMetrics<EventBusHandlerMetrics>, MetricSupplier {
  private final String baseName;
  private final Scheduler scheduler;
  private final LongAdder handlers = new LongAdder();
  private final ConcurrentMap<String, HandlersMeasurements> handlersMeasurements = new ConcurrentHashMap<>(0);
  private final LongAdder errorCount = new LongAdder();
  private final LongAdder bytesWritten = new LongAdder();
  private final LongAdder bytesRead = new LongAdder();
  private final LongAdder pending = new LongAdder();
  private final LongAdder pendingLocal = new LongAdder();
  private final LongAdder pendingRemote = new LongAdder();
  private final LongAdder publishedMessages = new LongAdder();
  private final LongAdder publishedLocalMessages = new LongAdder();
  private final LongAdder publishedRemoteMessages = new LongAdder();
  private final LongAdder sentMessages = new LongAdder();
  private final LongAdder sentLocalMessages = new LongAdder();
  private final LongAdder sentRemoteMessages = new LongAdder();
  private final LongAdder receivedMessages = new LongAdder();
  private final LongAdder receivedLocalMessages = new LongAdder();
  private final LongAdder receivedRemoteMessages = new LongAdder();
  private final LongAdder deliveredMessages = new LongAdder();
  private final LongAdder deliveredLocalMessages = new LongAdder();
  private final LongAdder deliveredRemoteMessages = new LongAdder();
  private final LongAdder replyFailures = new LongAdder();

  public EventBusMetricsImpl(String prefix, Scheduler scheduler) {
    baseName = prefix + (prefix.isEmpty() ? "" : ".") + "vertx.eventbus.";
    this.scheduler = scheduler;
    scheduler.register(this);
  }

  @Override
  public EventBusHandlerMetrics handlerRegistered(String address, boolean replyHandler) {
    handlers.increment();
    EventBusHandlerMetrics handlerMetrics = new EventBusHandlerMetrics(address);
    while (true) {
      HandlersMeasurements current = handlersMeasurements.get(address);
      if (current != null) {
        HandlersMeasurements candidate = current.incrementHandlersCount();
        if (handlersMeasurements.replace(address, current, candidate)) {
          break;
        }
      } else {
        HandlersMeasurements candidate = new HandlersMeasurements();
        if (handlersMeasurements.putIfAbsent(address, candidate) == null) {
          break;
        }
      }
    }
    return handlerMetrics;
  }

  @Override
  public void handlerUnregistered(EventBusHandlerMetrics handlerMetrics) {
    handlers.decrement();
    String address = handlerMetrics.getAddress();
    while (true) {
      HandlersMeasurements current = handlersMeasurements.get(address);
      HandlersMeasurements candidate = current.decrementHandlersCount();
      if (candidate.handlersCount() == 0) {
        if (handlersMeasurements.remove(address, current)) {
          break;
        }
      } else {
        if (handlersMeasurements.replace(address, current, candidate)) {
          break;
        }
      }
    }
  }

  @Override
  public void beginHandleMessage(EventBusHandlerMetrics handlerMetrics, boolean local) {
    pending.decrement();
    if (local) {
      pendingLocal.decrement();
    } else {
      pendingRemote.decrement();
    }
    handlerMetrics.resetTimer();
  }

  @Override
  public void endHandleMessage(EventBusHandlerMetrics handlerMetrics, Throwable failure) {
    long elapsed = handlerMetrics.elapsed();
    HandlersMeasurements handlersMeasurements = this.handlersMeasurements.get(handlerMetrics.getAddress());
    if (handlersMeasurements != null) {
      handlersMeasurements.addProcessingTime(elapsed);
    }
    if (failure != null) {
      errorCount.increment();
    }
  }

  @Override
  public void messageSent(String address, boolean publish, boolean local, boolean remote) {
    if (publish) {
      publishedMessages.increment();
      if (local) {
        publishedLocalMessages.increment();
      } else {
        publishedRemoteMessages.increment();
      }
    } else {
      sentMessages.increment();
      if (local) {
        sentLocalMessages.increment();
      } else {
        sentRemoteMessages.increment();
      }
    }
  }

  @Override
  public void messageReceived(String address, boolean publish, boolean local, int handlers) {
    pending.add(handlers);
    receivedMessages.increment();
    if (local) {
      receivedLocalMessages.increment();
      pendingLocal.add(handlers);
    } else {
      receivedRemoteMessages.increment();
      pendingRemote.add(handlers);
    }
    if (handlers > 0) {
      deliveredMessages.increment();
      if (local) {
        deliveredLocalMessages.increment();
      } else {
        deliveredRemoteMessages.increment();
      }
    }
  }

  @Override
  public void messageWritten(String address, int numberOfBytes) {
    bytesWritten.add(numberOfBytes);
  }

  @Override
  public void messageRead(String address, int numberOfBytes) {
    bytesRead.add(numberOfBytes);
  }

  @Override
  public void replyFailure(String address, ReplyFailure failure) {
    replyFailures.increment();
  }

  @Override
  public List<SingleMetric> collect() {
    long timestamp = System.currentTimeMillis();
    List<SingleMetric> metrics = new ArrayList<>();
    metrics.add(buildMetric("handlers", timestamp, handlers.sum(), MetricType.GAUGE));
    handlersMeasurements.entrySet().forEach(e -> {
      String address = e.getKey();
      HandlersMeasurements measurements = e.getValue();
      String source = address + ".processingTime";
      metrics.add(buildMetric(source, timestamp, measurements.processingTime(), MetricType.COUNTER));
    });
    metrics.add(buildMetric("errorCount", timestamp, errorCount.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("bytesWritten", timestamp, bytesWritten.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("bytesRead", timestamp, bytesRead.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("pending", timestamp, pending.sum(), MetricType.GAUGE));
    metrics.add(buildMetric("pendingLocal", timestamp, pendingLocal.sum(), MetricType.GAUGE));
    metrics.add(buildMetric("pendingRemote", timestamp, pendingRemote.sum(), MetricType.GAUGE));
    metrics.add(buildMetric("publishedMessages", timestamp, publishedMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("publishedLocalMessages", timestamp, publishedLocalMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("publishedRemoteMessages", timestamp, publishedRemoteMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("sentMessages", timestamp, sentMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("sentLocalMessages", timestamp, sentLocalMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("sentRemoteMessages", timestamp, sentRemoteMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("receivedMessages", timestamp, receivedMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("receivedLocalMessages", timestamp, receivedLocalMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("receivedRemoteMessages", timestamp, receivedRemoteMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("deliveredMessages", timestamp, deliveredMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("deliveredLocalMessages", timestamp, deliveredLocalMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("deliveredRemoteMessages", timestamp, deliveredRemoteMessages.sum(), MetricType.COUNTER));
    metrics.add(buildMetric("replyFailures", timestamp, replyFailures.sum(), MetricType.COUNTER));
    return metrics;
  }

  private SingleMetric buildMetric(String name, long timestamp, Number value, MetricType type) {
    return new SingleMetric(baseName + name, timestamp, value.doubleValue(), type);
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public void close() {
    scheduler.unregister(this);
  }

}
