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
package org.hawkular.vertx.monitor.impl;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.spi.metrics.DatagramSocketMetrics;

import org.hawkular.metrics.client.common.MetricType;
import org.hawkular.metrics.client.common.SingleMetric;
import org.hawkular.vertx.monitor.VertxMonitorOptions;

/**
 * @author Thomas Segismont
 */
public class DatagramSocketMetricsImpl  extends ScheduledMetrics implements DatagramSocketMetrics {
    private final String baseName;

    // Bytes info
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    // Other
    private final AtomicLong errorCount = new AtomicLong(0);

    public DatagramSocketMetricsImpl(Vertx vertx, VertxMonitorOptions vertxMonitorOptions, HttpClient httpClient) {
        super(vertx, httpClient, vertxMonitorOptions.getTenant());
        String prefix = vertxMonitorOptions.getPrefix();
        baseName = prefix + (prefix.isEmpty() ? "" : ".") + "vertx.datagram.server";
    }


    @Override
    public void listening(SocketAddress localAddress) {
    }

    @Override
    public void bytesRead(Void socketMetric, SocketAddress remoteAddress, long numberOfBytes) {
        bytesReceived.addAndGet(numberOfBytes);
    }

    @Override
    public void bytesWritten(Void socketMetric, SocketAddress remoteAddress, long numberOfBytes) {
        bytesSent.addAndGet(numberOfBytes);
    }

    @Override
    public void exceptionOccurred(Void socketMetric, SocketAddress remoteAddress, Throwable t) {
        errorCount.incrementAndGet();
    }

    @Override
    protected List<SingleMetric> collect() {
        long timestamp = System.currentTimeMillis();
        return Arrays.asList(
                buildMetric("bytesReceived", timestamp, bytesReceived.get(), MetricType.COUNTER),
                buildMetric("bytesSent", timestamp, bytesSent.get(), MetricType.COUNTER),
                buildMetric("errorCount", timestamp, errorCount.get(), MetricType.COUNTER)
        );
    }

    private SingleMetric buildMetric(String name, long timestamp, Number value, MetricType type) {
        return new SingleMetric(baseName + "." + name, timestamp, value.doubleValue(), type);
    }
}
