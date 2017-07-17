/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.wso2.carbon.inbound.kafka;

import org.apache.axiom.om.OMElement;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.wso2.carbon.inbound.endpoint.protocol.generic.GenericEventBasedConsumer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

/**
 * Kafka Event Based Consumer.
 *
 * @since 1.0.0.
 */
public class KafkaEventBasedConsumer extends GenericEventBasedConsumer {

    private static final Log log = LogFactory.getLog(KafkaEventBasedConsumer.class);

    private KafkaConsumer<byte[], byte[]> consumer;
    private MessageContext msgCtx;

    private String bootstrapServersName;
    private String keyDeserializer;
    private String valueDeserializer;
    private String groupId;
    private String pollTimeout;
    private String topic;

    public KafkaEventBasedConsumer(Properties properties, String name, SynapseEnvironment synapseEnvironment,
            String injectingSeq, String onErrorSeq, boolean coordination, boolean sequential) {
        super(properties, name, synapseEnvironment, injectingSeq, onErrorSeq, coordination, sequential);
        validateMandatoryParameters(properties);
    }

    /**
     * Create a kafka consumer, subscribe it and consume the record.
     */
    public void consumeKafkaRecords() {

        Properties kafkaProperties = new Properties();
        kafkaProperties.put(KafkaConstants.BOOTSTRAP_SERVERS_NAME, bootstrapServersName);
        kafkaProperties.put(KafkaConstants.KEY_DESERIALIZER, keyDeserializer);
        kafkaProperties.put(KafkaConstants.VALUE_DESERIALIZER, valueDeserializer);
        kafkaProperties.put(KafkaConstants.GROUP_ID, groupId);
        kafkaProperties.put(KafkaConstants.POLL_TIMEOUT, pollTimeout);

        kafkaProperties.setProperty(KafkaConstants.ENABLE_AUTO_COMMIT, KafkaConstants.ENABLE_AUTO_COMMIT_DEFAULT);
        if (properties.getProperty(KafkaConstants.ENABLE_AUTO_COMMIT) != null) {
            kafkaProperties
                    .put(KafkaConstants.ENABLE_AUTO_COMMIT, properties.getProperty(KafkaConstants.ENABLE_AUTO_COMMIT));
        }

        kafkaProperties
                .setProperty(KafkaConstants.AUTO_COMMIT_INTERVAL_MS, KafkaConstants.AUTO_COMMIT_INTERVAL_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.AUTO_COMMIT_INTERVAL_MS) != null) {
            kafkaProperties.put(KafkaConstants.AUTO_COMMIT_INTERVAL_MS,
                    properties.getProperty(KafkaConstants.AUTO_COMMIT_INTERVAL_MS));
        }

        kafkaProperties.setProperty(KafkaConstants.SESSION_TIMEOUT_MS, KafkaConstants.SESSION_TIMEOUT_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.SESSION_TIMEOUT_MS) != null) {
            kafkaProperties
                    .put(KafkaConstants.SESSION_TIMEOUT_MS, properties.getProperty(KafkaConstants.SESSION_TIMEOUT_MS));
        }

        kafkaProperties.setProperty(KafkaConstants.FETCH_MIN_BYTES, KafkaConstants.FETCH_MIN_BYTES_DEFAULT);
        if (properties.getProperty(KafkaConstants.FETCH_MIN_BYTES) != null) {
            kafkaProperties.put(KafkaConstants.FETCH_MIN_BYTES, properties.getProperty(KafkaConstants.FETCH_MIN_BYTES));
        }

        kafkaProperties.setProperty(KafkaConstants.HEARTBEAT_INTERVAL_MS, KafkaConstants.HEARTBEAT_INTERVAL_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.HEARTBEAT_INTERVAL_MS) != null) {
            kafkaProperties.put(KafkaConstants.HEARTBEAT_INTERVAL_MS,
                    properties.getProperty(KafkaConstants.HEARTBEAT_INTERVAL_MS));
        }

        kafkaProperties.setProperty(KafkaConstants.MAX_PARTITION_FETCH_BYTES,
                KafkaConstants.MAX_PARTITION_FETCH_BYTES_DEFAULT);
        if (properties.getProperty(KafkaConstants.MAX_PARTITION_FETCH_BYTES) != null) {
            kafkaProperties.put(KafkaConstants.MAX_PARTITION_FETCH_BYTES,
                    properties.getProperty(KafkaConstants.MAX_PARTITION_FETCH_BYTES));
        }

        if (properties.getProperty(KafkaConstants.SSL_KEY_PASSWORD) != null) {
            kafkaProperties
                    .put(KafkaConstants.SSL_KEY_PASSWORD, properties.getProperty(KafkaConstants.SSL_KEY_PASSWORD));
        }

        if (properties.getProperty(KafkaConstants.SSL_KEYSTORE_LOCATION) != null) {
            kafkaProperties.put(KafkaConstants.SSL_KEYSTORE_LOCATION,
                    properties.getProperty(KafkaConstants.SSL_KEYSTORE_LOCATION));
        }

        if (properties.getProperty(KafkaConstants.SSL_KEYSTORE_PASSWORD) != null) {
            kafkaProperties.put(KafkaConstants.SSL_KEYSTORE_PASSWORD,
                    properties.getProperty(KafkaConstants.SSL_KEYSTORE_PASSWORD));
        }

        if (properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_LOCATION) != null) {
            kafkaProperties.put(KafkaConstants.SSL_TRUSTSTORE_LOCATION,
                    properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_LOCATION));
        }

        if (properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_PASSWORD) != null) {
            kafkaProperties.put(KafkaConstants.SSL_TRUSTSTORE_PASSWORD,
                    properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_PASSWORD));
        }

        kafkaProperties.setProperty(KafkaConstants.AUTO_OFFSET_RESET, KafkaConstants.AUTO_OFFSET_RESET_DEFAULT);
        if (properties.getProperty(KafkaConstants.AUTO_OFFSET_RESET) != null) {
            kafkaProperties
                    .put(KafkaConstants.AUTO_OFFSET_RESET, properties.getProperty(KafkaConstants.AUTO_OFFSET_RESET));
        }

        kafkaProperties
                .setProperty(KafkaConstants.CONNECTIONS_MAX_IDLE_MS, KafkaConstants.CONNECTIONS_MAX_IDLE_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.CONNECTIONS_MAX_IDLE_MS) != null) {
            kafkaProperties.put(KafkaConstants.CONNECTIONS_MAX_IDLE_MS,
                    properties.getProperty(KafkaConstants.CONNECTIONS_MAX_IDLE_MS));
        }

        kafkaProperties
                .setProperty(KafkaConstants.EXCLUDE_INTERNAL_TOPICS, KafkaConstants.EXCLUDE_INTERNAL_TOPICS_DEFAULT);
        if (properties.getProperty(KafkaConstants.EXCLUDE_INTERNAL_TOPICS) != null) {
            kafkaProperties.put(KafkaConstants.EXCLUDE_INTERNAL_TOPICS,
                    properties.getProperty(KafkaConstants.EXCLUDE_INTERNAL_TOPICS));
        }

        kafkaProperties.setProperty(KafkaConstants.FETCH_MAX_BYTES, KafkaConstants.FETCH_MAX_BYTES_DEFAULT);
        if (properties.getProperty(KafkaConstants.FETCH_MAX_BYTES) != null) {
            kafkaProperties.put(KafkaConstants.FETCH_MAX_BYTES, properties.getProperty(KafkaConstants.FETCH_MAX_BYTES));
        }

        kafkaProperties.setProperty(KafkaConstants.MAX_POLL_INTERVAL_MS, KafkaConstants.MAX_POLL_INTERVAL_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.MAX_POLL_INTERVAL_MS) != null) {
            kafkaProperties.put(KafkaConstants.MAX_POLL_INTERVAL_MS,
                    properties.getProperty(KafkaConstants.MAX_POLL_INTERVAL_MS));
        }

        kafkaProperties.setProperty(KafkaConstants.MAX_POLL_RECORDS, KafkaConstants.MAX_POLL_RECORDS_DEFAULT);
        if (properties.getProperty(KafkaConstants.MAX_POLL_RECORDS) != null) {
            kafkaProperties
                    .put(KafkaConstants.MAX_POLL_RECORDS, properties.getProperty(KafkaConstants.MAX_POLL_RECORDS));
        }

        kafkaProperties.setProperty(KafkaConstants.PARTITION_ASSIGNMENT_STRATEGY,
                KafkaConstants.PARTITION_ASSIGNMENT_STRATEGY_DEFAULT);
        if (properties.getProperty(KafkaConstants.PARTITION_ASSIGNMENT_STRATEGY) != null) {
            kafkaProperties.put(KafkaConstants.PARTITION_ASSIGNMENT_STRATEGY,
                    properties.getProperty(KafkaConstants.PARTITION_ASSIGNMENT_STRATEGY));
        }

        kafkaProperties.setProperty(KafkaConstants.RECEIVER_BUFFER_BYTES, KafkaConstants.RECEIVER_BUFFER_BYTES_DEFAULT);
        if (properties.getProperty(KafkaConstants.RECEIVER_BUFFER_BYTES) != null) {
            kafkaProperties.put(KafkaConstants.RECEIVER_BUFFER_BYTES,
                    properties.getProperty(KafkaConstants.RECEIVER_BUFFER_BYTES));
        }

        kafkaProperties.setProperty(KafkaConstants.REQUEST_TIMEOUT_MS, KafkaConstants.REQUEST_TIMEOUT_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.REQUEST_TIMEOUT_MS) != null) {
            kafkaProperties
                    .put(KafkaConstants.REQUEST_TIMEOUT_MS, properties.getProperty(KafkaConstants.REQUEST_TIMEOUT_MS));
        }

        if (properties.getProperty(KafkaConstants.SASL_JAAS_CONFIG) != null) {
            kafkaProperties
                    .put(KafkaConstants.SASL_JAAS_CONFIG, properties.getProperty(KafkaConstants.SASL_JAAS_CONFIG));
        }

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_SERVICE_NAME) != null) {
            kafkaProperties.put(KafkaConstants.SASL_KERBEROS_SERVICE_NAME,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_SERVICE_NAME));
        }

        if (properties.getProperty(KafkaConstants.SASL_MECANISM) != null) {
            kafkaProperties.put(KafkaConstants.SASL_MECANISM, properties.getProperty(KafkaConstants.SASL_MECANISM));
        }

        if (properties.getProperty(KafkaConstants.SECURITY_PROTOCOL) != null) {
            kafkaProperties
                    .put(KafkaConstants.SECURITY_PROTOCOL, properties.getProperty(KafkaConstants.SECURITY_PROTOCOL));
        }

        kafkaProperties.setProperty(KafkaConstants.SEND_BUFFER_BYTES, KafkaConstants.SEND_BUFFER_BYTES_DEFAULT);
        if (properties.getProperty(KafkaConstants.SEND_BUFFER_BYTES) != null) {
            kafkaProperties
                    .put(KafkaConstants.SEND_BUFFER_BYTES, properties.getProperty(KafkaConstants.SEND_BUFFER_BYTES));
        }

        if (properties.getProperty(KafkaConstants.SSL_ENABLED_PROTOCOL) != null) {
            String[] sslEnabledProtocolsArray = properties.getProperty(KafkaConstants.SSL_ENABLED_PROTOCOL).split(",");
            kafkaProperties.put(KafkaConstants.SSL_ENABLED_PROTOCOL, Arrays.asList(sslEnabledProtocolsArray));
        }

        if (properties.getProperty(KafkaConstants.SSL_KEYSTORE_TYPE) != null) {
            kafkaProperties
                    .put(KafkaConstants.SSL_KEYSTORE_TYPE, properties.getProperty(KafkaConstants.SSL_KEYSTORE_TYPE));
        }

        if (properties.getProperty(KafkaConstants.SSL_PROTOCOL) != null) {
            kafkaProperties.put(KafkaConstants.SSL_PROTOCOL, properties.getProperty(KafkaConstants.SSL_PROTOCOL));
        }

        if (properties.getProperty(KafkaConstants.SSL_PROVIDER) != null) {
            kafkaProperties.put(KafkaConstants.SSL_PROVIDER, properties.getProperty(KafkaConstants.SSL_PROVIDER));
        }

        if (properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_TYPE) != null) {
            kafkaProperties.put(KafkaConstants.SSL_TRUSTSTORE_TYPE,
                    properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_TYPE));
        }

        kafkaProperties.setProperty(KafkaConstants.CHECK_CRCS, KafkaConstants.CHECK_CRCS_DEFAULT);
        if (properties.getProperty(KafkaConstants.CHECK_CRCS) != null) {
            kafkaProperties.put(KafkaConstants.CHECK_CRCS, properties.getProperty(KafkaConstants.CHECK_CRCS));
        }

        kafkaProperties.setProperty(KafkaConstants.CLIENT_ID, KafkaConstants.CLIENT_ID_DEFAULT);
        if (properties.getProperty(KafkaConstants.CLIENT_ID) != null) {
            kafkaProperties.put(KafkaConstants.CLIENT_ID, properties.getProperty(KafkaConstants.CLIENT_ID));
        }

        kafkaProperties.setProperty(KafkaConstants.FETCH_MAX_WAIT_MS, KafkaConstants.FETCH_MAX_WAIT_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.FETCH_MAX_WAIT_MS) != null) {
            kafkaProperties
                    .put(KafkaConstants.FETCH_MAX_WAIT_MS, properties.getProperty(KafkaConstants.FETCH_MAX_WAIT_MS));
        }

        kafkaProperties.setProperty(KafkaConstants.INTERCEPTOR_CLASSES, KafkaConstants.INTERCEPTOR_CLASSES_DEFAULT);
        if (properties.getProperty(KafkaConstants.INTERCEPTOR_CLASSES) != null) {
            kafkaProperties.put(KafkaConstants.INTERCEPTOR_CLASSES,
                    properties.getProperty(KafkaConstants.INTERCEPTOR_CLASSES));
        }

        kafkaProperties.setProperty(KafkaConstants.METADATA_MAX_AGE_MS, KafkaConstants.METADATA_MAX_AGE_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.METADATA_MAX_AGE_MS) != null) {
            kafkaProperties.put(KafkaConstants.METADATA_MAX_AGE_MS,
                    properties.getProperty(KafkaConstants.METADATA_MAX_AGE_MS));
        }

        kafkaProperties.setProperty(KafkaConstants.METRIC_REPORTERS, KafkaConstants.METRIC_REPORTERS_DEFAULT);
        if (properties.getProperty(KafkaConstants.METRIC_REPORTERS) != null) {
            kafkaProperties
                    .put(KafkaConstants.METRIC_REPORTERS, properties.getProperty(KafkaConstants.METRIC_REPORTERS));
        }

        kafkaProperties.setProperty(KafkaConstants.METRICS_NUM_SAMPLES, KafkaConstants.METRICS_NUM_SAMPLES_DEFAULT);
        if (properties.getProperty(KafkaConstants.METRICS_NUM_SAMPLES) != null) {
            kafkaProperties.put(KafkaConstants.METRICS_NUM_SAMPLES,
                    properties.getProperty(KafkaConstants.METRICS_NUM_SAMPLES));
        }

        kafkaProperties
                .setProperty(KafkaConstants.METRICS_RECORDING_LEVEL, KafkaConstants.METRICS_RECORDING_LEVEL_DEFAULT);
        if (properties.getProperty(KafkaConstants.METRICS_RECORDING_LEVEL) != null) {
            kafkaProperties.put(KafkaConstants.METRICS_RECORDING_LEVEL,
                    properties.getProperty(KafkaConstants.METRICS_RECORDING_LEVEL));
        }

        kafkaProperties
                .setProperty(KafkaConstants.METRICS_SAMPLE_WINDOW_MS, KafkaConstants.METRICS_SAMPLE_WINDOW_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.METRICS_SAMPLE_WINDOW_MS) != null) {
            kafkaProperties.put(KafkaConstants.METRICS_SAMPLE_WINDOW_MS,
                    properties.getProperty(KafkaConstants.METRICS_SAMPLE_WINDOW_MS));
        }

        kafkaProperties.setProperty(KafkaConstants.RECONNECT_BACKOFF_MS, KafkaConstants.RECONNECT_BACKOFF_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.RECONNECT_BACKOFF_MS) != null) {
            properties.setProperty(KafkaConstants.RECONNECT_BACKOFF_MS,
                    properties.getProperty(KafkaConstants.RECONNECT_BACKOFF_MS));
        }

        kafkaProperties.setProperty(KafkaConstants.RETRY_BACKOFF_MS, KafkaConstants.RETRY_BACKOFF_MS_DEFAULT);
        if (properties.getProperty(KafkaConstants.RETRY_BACKOFF_MS) != null) {
            kafkaProperties
                    .put(KafkaConstants.RETRY_BACKOFF_MS, properties.getProperty(KafkaConstants.RETRY_BACKOFF_MS));
        }

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_KINIT_CMD) != null) {
            kafkaProperties.put(KafkaConstants.SASL_KERBEROS_KINIT_CMD,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_KINIT_CMD));
        }

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_MIN_TIME_BEFORE_RELOGIN) != null) {
            kafkaProperties.put(KafkaConstants.SASL_KERBEROS_MIN_TIME_BEFORE_RELOGIN,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_MIN_TIME_BEFORE_RELOGIN));
        }

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_JITTER) != null) {
            properties.setProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_JITTER,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_JITTER));
        }

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_WINDOW_FACTOR) != null) {
            kafkaProperties.put(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_WINDOW_FACTOR,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_WINDOW_FACTOR));
        }

        if (properties.getProperty(KafkaConstants.SSL_CIPHER_SUITES) != null) {
            properties.setProperty(KafkaConstants.SSL_CIPHER_SUITES,
                    properties.getProperty(KafkaConstants.SSL_CIPHER_SUITES));
        }

        if (properties.getProperty(KafkaConstants.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM) != null) {
            kafkaProperties.put(KafkaConstants.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM,
                    properties.getProperty(KafkaConstants.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM));
        }

        if (properties.getProperty(KafkaConstants.SSL_CIPHER_SUITES) != null) {
            kafkaProperties
                    .put(KafkaConstants.SSL_CIPHER_SUITES, properties.getProperty(KafkaConstants.SSL_CIPHER_SUITES));
        }

        if (properties.getProperty(KafkaConstants.SSL_KEYMANAGER_ALGORITHM) != null) {
            kafkaProperties.put(KafkaConstants.SSL_KEYMANAGER_ALGORITHM,
                    properties.getProperty(KafkaConstants.SSL_KEYMANAGER_ALGORITHM));
        }

        if (properties.getProperty(KafkaConstants.SSL_SECURE_RANDOM_IMPLEMENTATION) != null) {
            kafkaProperties.put(KafkaConstants.SSL_SECURE_RANDOM_IMPLEMENTATION,
                    properties.getProperty(KafkaConstants.SSL_SECURE_RANDOM_IMPLEMENTATION));
        }

        if (properties.getProperty(KafkaConstants.SSL_TRUSTMANAGER_ALGORITHM) != null) {
            kafkaProperties.put(KafkaConstants.SSL_TRUSTMANAGER_ALGORITHM,
                    properties.getProperty(KafkaConstants.SSL_TRUSTMANAGER_ALGORITHM));
        }

        String[] topicsArray = topic.split(",");
        consumer = new KafkaConsumer<>(kafkaProperties);
        consumer.subscribe(Arrays.asList(topicsArray));

        while (true) {
            ConsumerRecords<byte[], byte[]> records = consumer.poll(Long.parseLong(pollTimeout));
            for (ConsumerRecord record : records) {
                msgCtx = createMessageContext();
                msgCtx.setProperty(KafkaConstants.PARTITION_NO, record.partition());
                msgCtx.setProperty(KafkaConstants.MESSAGE_VALUE, record.value());
                msgCtx.setProperty(KafkaConstants.OFFSET, record.offset());
                msgCtx.setProperty(KafkaConstants.CHECKSUM, record.checksum());
                msgCtx.setProperty(KafkaConstants.TIMESTAMP, record.timestamp());
                msgCtx.setProperty(KafkaConstants.TIMESTAMP_TYPE, record.timestampType());
                msgCtx.setProperty(KafkaConstants.TOPIC, record.topic());
                msgCtx.setProperty(KafkaConstants.KEY, record.key());
                injectMessage(record.value().toString(), properties.getProperty(KafkaConstants.CONTENT_TYPE));
            }
        }
    }

    @Override protected boolean injectMessage(String strMessage, String contentType) {
        AutoCloseInputStream in = new AutoCloseInputStream(new ByteArrayInputStream(strMessage.getBytes()));
        return this.injectMessage((InputStream) in, contentType);
    }

    @Override protected boolean injectMessage(InputStream in, String contentType) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Processed Custom inbound EP Message of Content-type : " + contentType + " for " + name);
            }

            org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) msgCtx)
                    .getAxis2MessageContext();
            Object builder;
            if (StringUtils.isEmpty(contentType)) {
                log.warn("Unable to determine content type for message, setting to application/json for " + name);
            }
            int index = contentType.indexOf(';');
            String type = index > 0 ? contentType.substring(0, index) : contentType;
            builder = BuilderUtil.getBuilderFromSelector(type, axis2MsgCtx);
            if (builder == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No message builder found for type '" + type + "'. Falling back to SOAP. for" + name);
                }
                builder = new SOAPBuilder();
            }

            OMElement documentElement1 = ((Builder) builder).processDocument(in, contentType, axis2MsgCtx);
            msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement1));
            if (this.injectingSeq == null || "".equals(this.injectingSeq)) {
                log.error("Sequence name not specified. Sequence : " + this.injectingSeq + " for " + name);
                return false;
            }

            SequenceMediator seq = (SequenceMediator) this.synapseEnvironment.getSynapseConfiguration()
                    .getSequence(this.injectingSeq);
            seq.setErrorHandler(this.onErrorSeq);
            if (seq != null) {
                if (log.isDebugEnabled()) {
                    log.debug("injecting message to sequence : " + this.injectingSeq + " of " + name);
                }
                if (!this.synapseEnvironment.injectInbound(msgCtx, seq, this.sequential)) {
                    return false;
                }
            } else {
                log.error("Sequence: " + this.injectingSeq + " not found for " + name);
            }
        } catch (Exception e) {
            log.error("Error while processing the Kafka inbound endpoint Message ", e);
        }
        return true;
    }

    /**
     * load essential property for Kafka inbound endpoint.
     *
     * @param properties The mandatory parameters of Kafka.
     */
    private void validateMandatoryParameters(Properties properties) {
        if (log.isDebugEnabled()) {
            log.debug("Starting to load the Kafka Mandatory parameters");
        }

        bootstrapServersName = properties.getProperty(KafkaConstants.BOOTSTRAP_SERVERS_NAME);
        keyDeserializer = properties.getProperty(KafkaConstants.KEY_DESERIALIZER);
        valueDeserializer = properties.getProperty(KafkaConstants.VALUE_DESERIALIZER);
        groupId = properties.getProperty(KafkaConstants.GROUP_ID);
        pollTimeout = properties.getProperty(KafkaConstants.POLL_TIMEOUT);
        topic = properties.getProperty(KafkaConstants.TOPIC_NAMES);

        if (StringUtils.isEmpty(bootstrapServersName) || StringUtils.isEmpty(keyDeserializer) || StringUtils
                .isEmpty(valueDeserializer) || StringUtils.isEmpty(groupId) || StringUtils.isEmpty(pollTimeout)
                || StringUtils.isEmpty(topic)) {
            throw new SynapseException(
                    "Mandatory Parameters cannot be Empty, The mandatory parameters are bootstrap.servers, "
                            + "key.deserializer, value.deserializer, group.id, poll.timeout and topic.names");
        }
    }

    /**
     * Create the message context.
     */
    private MessageContext createMessageContext() {
        MessageContext msgCtx = this.synapseEnvironment.createMessageContext();
        org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) msgCtx).getAxis2MessageContext();
        axis2MsgCtx.setServerSide(true);
        axis2MsgCtx.setMessageID(String.valueOf(UUID.randomUUID()));
        return msgCtx;
    }

    @Override public void listen() {
        consumeKafkaRecords();
    }

    /**
     * Close the connection to the Kafka.
     */
    public void destroy() {
        try {
            if (consumer != null) {
                consumer.close();
                if (log.isDebugEnabled()) {
                    log.debug("The Kafka consumer has been close ! for " + name);
                }
            }
        } catch (Exception e) {
            log.error("Error while shutdown the Kafka consumer " + name + " " + e.getMessage(), e);
        }
    }
}