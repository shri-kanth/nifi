/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.reporting.sql;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.record.sink.RecordSinkService;
import org.apache.nifi.reporting.AbstractReportingTask;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.reporting.ReportingInitializationContext;
import org.apache.nifi.reporting.sql.util.QueryMetricsUtil;
import org.apache.nifi.serialization.record.ResultSetRecordSet;
import org.apache.nifi.util.StopWatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Tags({"status", "connection", "processor", "jvm", "metrics", "history", "bulletin", "prediction", "process", "group", "record", "sql"})
@CapabilityDescription("Publishes NiFi status information based on the results of a user-specified SQL query. The query may make use of the CONNECTION_STATUS, PROCESSOR_STATUS, "
        + "BULLETINS, PROCESS_GROUP_STATUS, JVM_METRICS, or CONNECTION_STATUS_PREDICTIONS tables, and can use any functions or capabilities provided by Apache Calcite. Note that the "
        + "CONNECTION_STATUS_PREDICTIONS table is not available for querying if analytics are not enabled (see the nifi.analytics.predict.enabled property in nifi.properties). Attempting a "
        + "query on the table when the capability is disabled will cause an error.")
public class QueryNiFiReportingTask extends AbstractReportingTask {


    private List<PropertyDescriptor> properties;

    private volatile RecordSinkService recordSinkService;

    private MetricsQueryService metricsQueryService;

    @Override
    protected void init(final ReportingInitializationContext config) {
        metricsQueryService = new MetricsSqlQueryService(getLogger());
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(QueryMetricsUtil.QUERY);
        properties.add(QueryMetricsUtil.RECORD_SINK);
        properties.add(QueryMetricsUtil.INCLUDE_ZERO_RECORD_RESULTS);
        this.properties = Collections.unmodifiableList(properties);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @OnScheduled
    public void setup(final ConfigurationContext context) throws IOException {
        recordSinkService = context.getProperty(QueryMetricsUtil.RECORD_SINK).asControllerService(RecordSinkService.class);
        recordSinkService.reset();
    }

    @Override
    public void onTrigger(ReportingContext context) {
        final StopWatch stopWatch = new StopWatch(true);
        try {
            final String sql = context.getProperty(QueryMetricsUtil.QUERY).evaluateAttributeExpressions().getValue();
            final QueryResult queryResult = metricsQueryService.query(context, sql);
            final ResultSetRecordSet recordSet;

            try {
                getLogger().debug("Executing query: {}", new Object[]{sql});
                recordSet = metricsQueryService.getResultSetRecordSet(queryResult);
            } catch (final Exception e) {
                getLogger().error("Error creating record set from query results due to {}", new Object[]{e.getMessage()}, e);
                return;
            }

            try {
                final Map<String, String> attributes = new HashMap<>();
                final String transactionId = UUID.randomUUID().toString();
                attributes.put("reporting.task.transaction.id", transactionId);
                attributes.put("reporting.task.name", getName());
                attributes.put("reporting.task.uuid", getIdentifier());
                attributes.put("reporting.task.type", this.getClass().getSimpleName());
                recordSinkService.sendData(recordSet, attributes, context.getProperty(QueryMetricsUtil.INCLUDE_ZERO_RECORD_RESULTS).asBoolean());
            } catch (Exception e) {
                getLogger().error("Error during transmission of query results due to {}", new Object[]{e.getMessage()}, e);
                return;
            } finally {
                metricsQueryService.closeQuietly(queryResult);
            }
            final long elapsedMillis = stopWatch.getElapsed(TimeUnit.MILLISECONDS);
            getLogger().debug("Successfully queried and sent in {} millis", new Object[]{elapsedMillis});
        } catch (Exception e) {
            getLogger().error("Error processing the query due to {}", new Object[]{e.getMessage()}, e);
        }
    }

}
