/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.Component;
import org.apache.camel.LoggingLevel;
import org.apache.camel.PollingConsumer;
import org.apache.camel.ResolveEndpointFailedException;
import org.apache.camel.spi.PollingConsumerPollStrategy;
import org.apache.camel.spi.ScheduledPollConsumerScheduler;
import org.apache.camel.spi.UriParam;
import org.apache.camel.util.CamelContextHelper;
import org.apache.camel.util.EndpointHelper;
import org.apache.camel.util.IntrospectionSupport;

/**
 * A base class for {@link org.apache.camel.Endpoint} which creates a {@link ScheduledPollConsumer}
 *
 * @version 
 */
public abstract class ScheduledPollEndpoint extends DefaultEndpoint {

    private static final String SPRING_SCHEDULER = "org.apache.camel.spring.pollingconsumer.SpringScheduledPollConsumerScheduler";
    private static final String QUARTZ_2_SCHEDULER = "org.apache.camel.pollconsumer.quartz2.QuartzScheduledPollConsumerScheduler";

    private boolean consumerPropertiesInUse;
    private String schedulerName;

    // if adding more options then align with org.apache.camel.impl.ScheduledPollConsumer
    @UriParam(defaultValue = "true", label = "consumer")
    private boolean startScheduler = true;
    @UriParam(defaultValue = "1000", label = "consumer")
    private long initialDelay = 1000;
    @UriParam(defaultValue = "500", label = "consumer")
    private long delay = 500;
    @UriParam(defaultValue = "MILLISECONDS", label = "consumer")
    private TimeUnit timeUnit = TimeUnit.MILLISECONDS;
    @UriParam(defaultValue = "true", label = "consumer")
    private boolean useFixedDelay = true;
    @UriParam(label = "consumer")
    private PollingConsumerPollStrategy pollStrategy = new DefaultPollingConsumerPollStrategy();
    @UriParam(defaultValue = "TRACE", label = "consumer")
    private LoggingLevel runLoggingLevel = LoggingLevel.TRACE;
    @UriParam(label = "consumer")
    private boolean sendEmptyMessageWhenIdle;
    @UriParam(label = "consumer")
    private boolean greedy;
    @UriParam(enums = "spring,quartz2", label = "consumer")
    private ScheduledPollConsumerScheduler scheduler;
    @UriParam(label = "consumer")
    private Map<String, Object> schedulerProperties;
    @UriParam(label = "consumer")
    private ScheduledExecutorService scheduledExecutorService;
    @UriParam(label = "consumer")
    private int backoffMultiplier;
    @UriParam(label = "consumer")
    private int backoffIdleThreshold;
    @UriParam(label = "consumer")
    private int backoffErrorThreshold;

    protected ScheduledPollEndpoint(String endpointUri, Component component) {
        super(endpointUri, component);
    }

    @Deprecated
    protected ScheduledPollEndpoint(String endpointUri, CamelContext context) {
        super(endpointUri, context);
    }

    @Deprecated
    protected ScheduledPollEndpoint(String endpointUri) {
        super(endpointUri);
    }

    protected ScheduledPollEndpoint() {
    }

    public void configureProperties(Map<String, Object> options) {
        super.configureProperties(options);
        configureScheduledPollConsumerProperties(options, getConsumerProperties());
    }

    protected void configureScheduledPollConsumerProperties(Map<String, Object> options, Map<String, Object> consumerProperties) {
        // special for scheduled poll consumers as we want to allow end users to configure its options
        // from the URI parameters without the consumer. prefix
        Map<String, Object> schedulerProperties = IntrospectionSupport.extractProperties(options, "scheduler.");
        if (schedulerProperties != null && !schedulerProperties.isEmpty()) {
            setSchedulerProperties(schedulerProperties);
        }

        if (scheduler == null && schedulerName != null) {
            // special for scheduler if its "spring"
            if ("spring".equals(schedulerName)) {
                try {
                    Class<? extends ScheduledPollConsumerScheduler> clazz = getCamelContext().getClassResolver().resolveMandatoryClass(SPRING_SCHEDULER, ScheduledPollConsumerScheduler.class);
                    setScheduler(getCamelContext().getInjector().newInstance(clazz));
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Cannot load " + SPRING_SCHEDULER + " from classpath. Make sure camel-spring.jar is on the classpath.", e);
                }
            } else if ("quartz2".equals(schedulerName)) {
                try {
                    Class<? extends ScheduledPollConsumerScheduler> clazz = getCamelContext().getClassResolver().resolveMandatoryClass(QUARTZ_2_SCHEDULER, ScheduledPollConsumerScheduler.class);
                    setScheduler(getCamelContext().getInjector().newInstance(clazz));
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Cannot load " + QUARTZ_2_SCHEDULER + " from classpath. Make sure camel-quarz2.jar is on the classpath.", e);
                }
            } else {
                setScheduler(CamelContextHelper.mandatoryLookup(getCamelContext(), schedulerName, ScheduledPollConsumerScheduler.class));
            }
        }
    }

    @Override
    protected void configurePollingConsumer(PollingConsumer consumer) throws Exception {
        Map<String, Object> copy = new HashMap<String, Object>(getConsumerProperties());
        Map<String, Object> throwaway = new HashMap<String, Object>();

        // filter out unwanted options which is intended for the scheduled poll consumer
        // as these options are not supported on the polling consumer
        configureScheduledPollConsumerProperties(copy, throwaway);

        // set reference properties first as they use # syntax that fools the regular properties setter
        EndpointHelper.setReferenceProperties(getCamelContext(), consumer, copy);
        EndpointHelper.setProperties(getCamelContext(), consumer, copy);

        if (!isLenientProperties() && copy.size() > 0) {
            throw new ResolveEndpointFailedException(this.getEndpointUri(), "There are " + copy.size()
                    + " parameters that couldn't be set on the endpoint polling consumer."
                    + " Check the uri if the parameters are spelt correctly and that they are properties of the endpoint."
                    + " Unknown consumer parameters=[" + copy + "]");
        }
    }

    @Override
    protected void doStart() throws Exception {
        // if any of the consumer properties was configured then we need to initialize the options before starting
        if (consumerPropertiesInUse) {
            initConsumerProperties();
        }

        super.doStart();
    }

    protected void initConsumerProperties() {
        // must setup consumer properties before we are ready to start
        Map<String, Object> options = getConsumerProperties();
        if (!options.containsKey("startScheduler")) {
            options.put("startScheduler", isStartScheduler());
        }
        if (!options.containsKey("initialDelay")) {
            options.put("initialDelay", getInitialDelay());
        }
        if (!options.containsKey("delay")) {
            options.put("delay", getDelay());
        }
        if (!options.containsKey("timeUnit")) {
            options.put("timeUnit", getTimeUnit());
        }
        if (!options.containsKey("useFixedDelay")) {
            options.put("useFixedDelay", isUseFixedDelay());
        }
        if (!options.containsKey("pollStrategy")) {
            options.put("pollStrategy", getPollStrategy());
        }
        if (!options.containsKey("runLoggingLevel")) {
            options.put("runLoggingLevel", getRunLoggingLevel());
        }
        if (!options.containsKey("sendEmptyMessageWhenIdle")) {
            options.put("sendEmptyMessageWhenIdle", isSendEmptyMessageWhenIdle());
        }
        if (!options.containsKey("greedy")) {
            options.put("greedy", isGreedy());
        }
        if (!options.containsKey("scheduler")) {
            options.put("scheduler", getScheduler());
        }
        if (!options.containsKey("schedulerProperties")) {
            options.put("schedulerProperties", getSchedulerProperties());
        }
        if (!options.containsKey("scheduledExecutorService")) {
            options.put("scheduledExecutorService", getScheduledExecutorService());
        }
        if (!options.containsKey("backoffMultiplier")) {
            options.put("backoffMultiplier", getBackoffMultiplier());
        }
        if (!options.containsKey("backoffIdleThreshold")) {
            options.put("backoffIdleThreshold", getBackoffIdleThreshold());
        }
        if (!options.containsKey("backoffErrorThreshold")) {
            options.put("backoffErrorThreshold", getBackoffErrorThreshold());
        }
    }

    public boolean isStartScheduler() {
        return startScheduler;
    }

    /**
     * Whether the scheduler should be auto started.
     */
    public void setStartScheduler(boolean startScheduler) {
        this.startScheduler = startScheduler;
        consumerPropertiesInUse = true;
    }

    public long getInitialDelay() {
        return initialDelay;
    }

    /**
     * Milliseconds before the first poll starts.
     */
    public void setInitialDelay(long initialDelay) {
        this.initialDelay = initialDelay;
        consumerPropertiesInUse = true;
    }

    public long getDelay() {
        return delay;
    }

    /**
     * Milliseconds before the next poll.
     */
    public void setDelay(long delay) {
        this.delay = delay;
        consumerPropertiesInUse = true;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    /**
     * Time unit for initialDelay and delay options.
     */
    public void setTimeUnit(TimeUnit timeUnit) {
        this.timeUnit = timeUnit;
        consumerPropertiesInUse = true;
    }

    public boolean isUseFixedDelay() {
        return useFixedDelay;
    }

    /**
     * Controls if fixed delay or fixed rate is used. See ScheduledExecutorService in JDK for details.
     */
    public void setUseFixedDelay(boolean useFixedDelay) {
        this.useFixedDelay = useFixedDelay;
        consumerPropertiesInUse = true;
    }

    public PollingConsumerPollStrategy getPollStrategy() {
        return pollStrategy;
    }

    /**
     * A pluggable org.apache.camel.PollingConsumerPollingStrategy allowing you to provide your custom implementation
     * to control error handling usually occurred during the poll operation before an Exchange have been created
     * and being routed in Camel. In other words the error occurred while the polling was gathering information,
     * for instance access to a file network failed so Camel cannot access it to scan for files.
     * The default implementation will log the caused exception at WARN level and ignore it.
     */
    public void setPollStrategy(PollingConsumerPollStrategy pollStrategy) {
        this.pollStrategy = pollStrategy;
        consumerPropertiesInUse = true;
    }

    public LoggingLevel getRunLoggingLevel() {
        return runLoggingLevel;
    }

    /**
     * The consumer logs a start/complete log line when it polls. This option allows you to configure the logging level for that.
     */
    public void setRunLoggingLevel(LoggingLevel runLoggingLevel) {
        this.runLoggingLevel = runLoggingLevel;
        consumerPropertiesInUse = true;
    }

    public boolean isSendEmptyMessageWhenIdle() {
        return sendEmptyMessageWhenIdle;
    }

    /**
     * If the polling consumer did not poll any files, you can enable this option to send an empty message (no body) instead.
     */
    public void setSendEmptyMessageWhenIdle(boolean sendEmptyMessageWhenIdle) {
        this.sendEmptyMessageWhenIdle = sendEmptyMessageWhenIdle;
        consumerPropertiesInUse = true;
    }

    public boolean isGreedy() {
        return greedy;
    }

    /**
     * If greedy is enabled, then the ScheduledPollConsumer will run immediately again, if the previous run polled 1 or more messages.
     */
    public void setGreedy(boolean greedy) {
        this.greedy = greedy;
        consumerPropertiesInUse = true;
    }

    public ScheduledPollConsumerScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Allow to plugin a custom org.apache.camel.spi.ScheduledPollConsumerScheduler to use as the scheduler for
     * firing when the polling consumer runs. The default implementation uses the ScheduledExecutorService and
     * there is a Quartz2, and Spring based which supports CRON expressions.
     *
     * Notice: If using a custom scheduler then the options for initialDelay, useFixedDelay, timeUnit,
     * and scheduledExecutorService may not be in use. Use the text quartz2 to refer to use the Quartz2 scheduler;
     * and use the text spring to use the Spring based; and use the text #myScheduler to refer to a custom scheduler
     * by its id in the Registry. See Quartz2 page for an example.
     */
    public void setScheduler(ScheduledPollConsumerScheduler scheduler) {
        this.scheduler = scheduler;
        consumerPropertiesInUse = true;
    }

    /**
     * Allow to plugin a custom org.apache.camel.spi.ScheduledPollConsumerScheduler to use as the scheduler for
     * firing when the polling consumer runs. This option is used for referring to one of the built-in schedulers
     * either <tt>spring</tt>, or <tt>quartz2</tt>.
     */
    public void setScheduler(String schedulerName) {
        this.schedulerName = schedulerName;
        consumerPropertiesInUse = true;
    }

    public Map<String, Object> getSchedulerProperties() {
        return schedulerProperties;
    }

    /**
     * To configure additional properties when using a custom scheduler or any of the Quartz2, Spring based scheduler.
     */
    public void setSchedulerProperties(Map<String, Object> schedulerProperties) {
        this.schedulerProperties = schedulerProperties;
        consumerPropertiesInUse = true;
    }

    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    /**
     * Allows for configuring a custom/shared thread pool to use for the consumer.
     * By default each consumer has its own single threaded thread pool.
     * This option allows you to share a thread pool among multiple consumers.
     */
    public void setScheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = scheduledExecutorService;
        consumerPropertiesInUse = true;
    }

    public int getBackoffMultiplier() {
        return backoffMultiplier;
    }

    /**
     * To let the scheduled polling consumer backoff if there has been a number of subsequent idles/errors in a row.
     * The multiplier is then the number of polls that will be skipped before the next actual attempt is happening again.
     * When this option is in use then backoffIdleThreshold and/or backoffErrorThreshold must also be configured.
     */
    public void setBackoffMultiplier(int backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
        consumerPropertiesInUse = true;
    }

    public int getBackoffIdleThreshold() {
        return backoffIdleThreshold;
    }

    /**
     * The number of subsequent idle polls that should happen before the backoffMultipler should kick-in.
     */
    public void setBackoffIdleThreshold(int backoffIdleThreshold) {
        this.backoffIdleThreshold = backoffIdleThreshold;
        consumerPropertiesInUse = true;
    }

    public int getBackoffErrorThreshold() {
        return backoffErrorThreshold;
    }

    /**
     * The number of subsequent error polls (failed due some error) that should happen before the backoffMultipler should kick-in.
     */
    public void setBackoffErrorThreshold(int backoffErrorThreshold) {
        this.backoffErrorThreshold = backoffErrorThreshold;
        consumerPropertiesInUse = true;
    }
}
