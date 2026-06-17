/*
 * Copyright (c) 2024, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.carbon.inbound.kafka;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.transport.TransportUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaMessageConsumerBatchTest {

    private static final String TOPIC = "test-topic";
    private static final String INBOUND_SEQ = "inSeq";
    private static final String ERROR_SEQ = "errorSeq";
    private static final String ENDPOINT_NAME = "test-ep";

    @Mock private SynapseEnvironment synapseEnvironment;
    @Mock private Axis2MessageContext synapseMessageContext;
    @Mock private org.apache.axis2.context.MessageContext axis2Ctx;
    @Mock private SynapseConfiguration synapseConfiguration;
    @Mock private SequenceMediator sequenceMediator;
    @Mock private KafkaConsumer<byte[], byte[]> mockKafkaConsumer;
    @Mock private OperationContext operationContext;
    @Mock private OMElement omElement;
    @Mock private SOAPEnvelope soapEnvelope;

    private KafkaMessageConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        when(synapseEnvironment.createMessageContext()).thenReturn(synapseMessageContext);
        when(synapseMessageContext.getAxis2MessageContext()).thenReturn(axis2Ctx);
        when(axis2Ctx.getOperationContext()).thenReturn(operationContext);
        when(synapseEnvironment.getSynapseConfiguration()).thenReturn(synapseConfiguration);
        when(synapseConfiguration.getSequence(anyString())).thenReturn(sequenceMediator);

        consumer = new KafkaMessageConsumer(baseProps(true, false), ENDPOINT_NAME,
                synapseEnvironment, 1000L, INBOUND_SEQ, ERROR_SEQ, false, true);
        setField(consumer, "consumer", mockKafkaConsumer);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Minimal properties sufficient to construct a KafkaMessageConsumer. */
    private static Properties baseProps(boolean batchEnabled, boolean manualCommit) {
        Properties p = new Properties();
        p.setProperty(KafkaConstants.BOOTSTRAP_SERVERS_NAME, "localhost:9092");
        p.setProperty(KafkaConstants.KEY_DESERIALIZER,
                "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        p.setProperty(KafkaConstants.VALUE_DESERIALIZER,
                "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        p.setProperty(KafkaConstants.GROUP_ID, "test-group");
        p.setProperty(KafkaConstants.POLL_TIMEOUT, "100");
        p.setProperty(KafkaConstants.TOPIC_NAME, TOPIC);
        p.setProperty(KafkaConstants.CONTENT_TYPE, "application/json");
        p.setProperty(KafkaConstants.BATCH_PROCESSING_ENABLED, String.valueOf(batchEnabled));
        if (manualCommit) {
            p.setProperty(KafkaConstants.ENABLE_AUTO_COMMIT, "false");
            p.setProperty(KafkaConstants.FAILURE_RETRY_COUNT, "3");
        }
        return p;
    }

    /** Creates a consumer with manual commit and failureRetryCount=3. */
    private KafkaMessageConsumer manualCommitConsumer() throws Exception {
        KafkaMessageConsumer c = new KafkaMessageConsumer(baseProps(true, true), ENDPOINT_NAME,
                synapseEnvironment, 1000L, INBOUND_SEQ, ERROR_SEQ, false, true);
        setField(c, "consumer", mockKafkaConsumer);
        return c;
    }

    private static ConsumerRecord<byte[], byte[]> record(String topic, int partition,
            long offset, byte[] value) {
        return new ConsumerRecord<>(topic, partition, offset, null, value);
    }

    private static ConsumerRecord<byte[], byte[]> nullValueRecord(String topic, int partition,
            long offset) {
        return new ConsumerRecord<>(topic, partition, offset, null, null);
    }

    private static ConsumerRecord<byte[], byte[]> poisonPillRecord(String topic, int partition,
            long offset, String errorMsg) throws IOException {
        ConsumerRecord<byte[], byte[]> r = new ConsumerRecord<>(topic, partition, offset, null, null);
        r.headers().add(KafkaConstants.VALUE_DESERIALIZER_EXCEPTION_HEADER,
                serialize(new KafkaException(errorMsg)));
        return r;
    }

    private static byte[] serialize(Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
        }
        return bos.toByteArray();
    }

    private static ConsumerRecords<byte[], byte[]> makeRecords(String topic, int partition,
            List<ConsumerRecord<byte[], byte[]>> list) {
        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> map = new HashMap<>();
        map.put(new TopicPartition(topic, partition), list);
        return new ConsumerRecords<>(map);
    }

    private static Object callPrivate(Object target, String methodName,
            Class<?>[] argTypes, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName, argTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        throw new IllegalArgumentException("Field not found: " + name);
    }

    /**
     * Stubs the Axis2 static entry-points so that injectMessage() can reach
     * synapseEnvironment.injectInbound() without throwing.
     */
    private void stubAxis2(MockedStatic<BuilderUtil> bu, MockedStatic<TransportUtils> tu) {
        bu.when(() -> BuilderUtil.getBuilderFromSelector(anyString(), any())).thenReturn(null);
        tu.when(() -> TransportUtils.createSOAPEnvelope(any())).thenReturn(soapEnvelope);
    }

    // ── buildBatchPayload ──────────────────────────────────────────────────────

    @Test
    void buildBatchPayload_singleRecord_producesJsonArray() throws Exception {
        ConsumerRecord<byte[], byte[]> r = record(TOPIC, 0, 0L, "{\"k\":1}".getBytes());
        String result = (String) callPrivate(consumer, "buildBatchPayload",
                new Class[]{List.class}, Collections.singletonList(r));
        assertEquals("[{\"k\":1}]", result);
    }

    @Test
    void buildBatchPayload_multipleRecords_commaDelimited() throws Exception {
        List<ConsumerRecord<byte[], byte[]>> list = Arrays.asList(
                record(TOPIC, 0, 0L, "A".getBytes()),
                record(TOPIC, 0, 1L, "B".getBytes()),
                record(TOPIC, 0, 2L, "C".getBytes()));
        String result = (String) callPrivate(consumer, "buildBatchPayload",
                new Class[]{List.class}, list);
        assertEquals("[A,B,C]", result);
    }

    @Test
    void buildBatchPayload_emptyList_producesEmptyJsonArray() throws Exception {
        String result = (String) callPrivate(consumer, "buildBatchPayload",
                new Class[]{List.class}, Collections.emptyList());
        assertEquals("[]", result);
    }

    // ── commitRecordsAsBatch — trivial cases ───────────────────────────────────

    @Test
    void commitRecordsAsBatch_emptyPoll_doesNotInteractWithKafkaConsumer() throws Exception {
        callPrivate(consumer, "commitRecordsAsBatch",
                new Class[]{ConsumerRecords.class}, ConsumerRecords.empty());
        verifyNoInteractions(mockKafkaConsumer);
    }

    @Test
    void commitRecordsAsBatch_onlyNullValueRecords_manualCommit_commitsLastOffsetPlusOne()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        TopicPartition tp = new TopicPartition(TOPIC, 0);

        callPrivate(mc, "commitRecordsAsBatch",
                new Class[]{ConsumerRecords.class},
                makeRecords(TOPIC, 0,
                        Collections.singletonList(nullValueRecord(TOPIC, 0, 7L))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(mockKafkaConsumer).commitSync(captor.capture());
        assertEquals(8L, captor.getValue().get(tp).offset());
    }

    // ── commitRecordsAsBatch — valid records, auto-commit ─────────────────────

    @Test
    void commitRecordsAsBatch_validRecords_autoCommit_neverCallsCommitSync() throws Exception {
        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(true);

            callPrivate(consumer, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class},
                    makeRecords(TOPIC, 0, Arrays.asList(
                            record(TOPIC, 0, 5L, "m1".getBytes()),
                            record(TOPIC, 0, 6L, "m2".getBytes()))));

            verify(mockKafkaConsumer, never()).commitSync(any(Map.class));
        }
    }


    // ── commitRecordsAsBatch — valid records, manual commit ───────────────────

    @Test
    void commitRecordsAsBatch_validRecords_manualCommit_success_commitsLastOffsetPlusOne()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        TopicPartition tp = new TopicPartition(TOPIC, 0);

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(true);

            callPrivate(mc, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class},
                    makeRecords(TOPIC, 0, Arrays.asList(
                            record(TOPIC, 0, 10L, "a".getBytes()),
                            record(TOPIC, 0, 11L, "b".getBytes()))));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor =
                    ArgumentCaptor.forClass((Class) Map.class);
            verify(mockKafkaConsumer).commitSync(captor.capture());
            assertEquals(12L, captor.getValue().get(tp).offset(),
                    "should commit lastOffset(11) + 1");
        }
    }

    @Test
    void commitRecordsAsBatch_validRecords_manualCommit_failure_seeksToFirstOffset()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        TopicPartition tp = new TopicPartition(TOPIC, 0);

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(false);

            callPrivate(mc, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class},
                    makeRecords(TOPIC, 0, Arrays.asList(
                            record(TOPIC, 0, 10L, "a".getBytes()),
                            record(TOPIC, 0, 11L, "b".getBytes()))));

            verify(mockKafkaConsumer).seek(tp, 10L);
            verify(mockKafkaConsumer, never()).commitSync(any(Map.class));
        }
    }

    @Test
    void commitRecordsAsBatch_retryCountExhausted_commitsNextOffsetAndInjectsError()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        setField(mc, "retryCounter", 3);  // already at failureRetryCount (3)
        TopicPartition tp = new TopicPartition(TOPIC, 0);

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(false);

            callPrivate(mc, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class},
                    makeRecords(TOPIC, 0, Collections.singletonList(
                            record(TOPIC, 0, 20L, "msg".getBytes()))));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor =
                    ArgumentCaptor.forClass((Class) Map.class);
            verify(mockKafkaConsumer).commitSync(captor.capture());
            assertEquals(21L, captor.getValue().get(tp).offset(),
                    "should commit lastOffset(20) + 1 after retry exhaustion");
            // injectInbound: once for the failed batch injection, once for the error injection
            verify(synapseEnvironment, times(2)).injectInbound(any(), any(), anyBoolean());
        }
    }

    // ── commitRecordsAsBatch — poison pills ────────────────────────────────────

    @Test
    void commitRecordsAsBatch_onlyPoisonPills_autoCommit_skipsRecordWithoutInjection()
            throws Exception {
        callPrivate(consumer, "commitRecordsAsBatch",
                new Class[]{ConsumerRecords.class},
                makeRecords(TOPIC, 0, Collections.singletonList(
                        poisonPillRecord(TOPIC, 0, 3L, "bad deser"))));

        verifyNoInteractions(synapseEnvironment);
        verify(mockKafkaConsumer, never()).commitSync(any(Map.class));
    }

    @Test
    void commitRecordsAsBatch_onlyPoisonPills_manualCommit_commitsLastOffsetPlusOne()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        TopicPartition tp = new TopicPartition(TOPIC, 0);

        callPrivate(mc, "commitRecordsAsBatch",
                new Class[]{ConsumerRecords.class},
                makeRecords(TOPIC, 0, Collections.singletonList(
                        poisonPillRecord(TOPIC, 0, 5L, "deser failed"))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(mockKafkaConsumer).commitSync(captor.capture());
        assertEquals(6L, captor.getValue().get(tp).offset());
    }

    // ── commitRecordsAsBatch — mixed batch ─────────────────────────────────────

    @Test
    void commitRecordsAsBatch_mixedBatch_poisonPillSkipped_validRecordInjected()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(true);

            // offset 0 = poison pill (skipped), offset 1 = valid record (injected)
            callPrivate(mc, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class},
                    makeRecords(TOPIC, 0, Arrays.asList(
                            poisonPillRecord(TOPIC, 0, 0L, "err"),
                            record(TOPIC, 0, 1L, "ok".getBytes()))));

            // only the valid record batch is injected; poison pill is logged and skipped
            verify(synapseEnvironment, times(1)).injectInbound(any(), any(), anyBoolean());
            // offset committed: lastOffset(1) + 1 = 2
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor =
                    ArgumentCaptor.forClass((Class) Map.class);
            verify(mockKafkaConsumer).commitSync(captor.capture());
            assertEquals(2L, captor.getValue().get(new TopicPartition(TOPIC, 0)).offset());
        }
    }

    // ── isBatchEnabled configuration ───────────────────────────────────────────

    @Test
    void isBatchEnabled_whenPropertySetToTrue_fieldIsTrue() throws Exception {
        assertTrue((Boolean) getField(consumer, "isBatchEnabled"),
                "consumer created with batch.processing.enabled=true should have isBatchEnabled=true");
    }

    @Test
    void isBatchEnabled_whenPropertyAbsent_defaultIsFalse() throws Exception {
        Properties props = baseProps(false, false);
        props.remove(KafkaConstants.BATCH_PROCESSING_ENABLED);
        KafkaMessageConsumer c = new KafkaMessageConsumer(props, "ep",
                synapseEnvironment, 1000L, INBOUND_SEQ, ERROR_SEQ, false, true);
        assertFalse((Boolean) getField(c, "isBatchEnabled"),
                "isBatchEnabled should default to false when property is absent");
    }
}
