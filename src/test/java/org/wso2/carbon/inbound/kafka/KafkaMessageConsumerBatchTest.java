/*
 * Copyright (c) 2026, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
import org.apache.kafka.clients.producer.KafkaProducer;
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
import java.time.Duration;
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

    /** Invokes a private method on {@code target} via reflection, bypassing access control. */
    private static Object callPrivate(Object target, String methodName,
            Class<?>[] argTypes, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName, argTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /** Sets a private field on {@code target} via reflection, bypassing access control. */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Reads a private field from {@code target} via reflection, bypassing access control. */
    private static Object getField(Object target, String fieldName) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    /** Walks the class hierarchy to find a declared field by name, including inherited private fields. */
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

    @Test
    void buildBatchPayload_applicationXml_wrapsRecordsInMessagesRoot() throws Exception {
        setField(consumer, "contentType", "application/xml");
        List<ConsumerRecord<byte[], byte[]>> list = Arrays.asList(
                record(TOPIC, 0, 0L, "<item>one</item>".getBytes()),
                record(TOPIC, 0, 1L, "<item>two</item>".getBytes()));
        String result = (String) callPrivate(consumer, "buildBatchPayload",
                new Class[]{List.class}, list);
        assertEquals("<messages><item>one</item><item>two</item></messages>", result);
    }

    @Test
    void buildBatchPayload_textXml_wrapsRecordsInMessagesRoot() throws Exception {
        setField(consumer, "contentType", "text/xml");
        ConsumerRecord<byte[], byte[]> r = record(TOPIC, 0, 0L, "<item>one</item>".getBytes());
        String result = (String) callPrivate(consumer, "buildBatchPayload",
                new Class[]{List.class}, Collections.singletonList(r));
        assertEquals("<messages><item>one</item></messages>", result);
    }

    @Test
    void buildBatchPayload_textPlain_wrapsInTextElementsAndEscapesXml() throws Exception {
        setField(consumer, "contentType", "text/plain");
        List<ConsumerRecord<byte[], byte[]>> list = Arrays.asList(
                record(TOPIC, 0, 0L, "hello".getBytes()),
                record(TOPIC, 0, 1L, "a<b>&c".getBytes()));
        String result = (String) callPrivate(consumer, "buildBatchPayload",
                new Class[]{List.class}, list);
        assertEquals(
                "<messages>" +
                "<text xmlns=\"http://ws.apache.org/commons/ns/payload\">hello</text>" +
                "<text xmlns=\"http://ws.apache.org/commons/ns/payload\">a&lt;b&gt;&amp;c</text>" +
                "</messages>",
                result);
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

    // ── commitRecordsAsBatch — auto-commit + failure ───────────────────────────

    @Test
    void commitRecordsAsBatch_validRecords_autoCommit_failure_noSeekNoCommit() throws Exception {
        // With auto-commit, a failed injection is silently ignored — no seek, no manual commit.
        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(false);

            callPrivate(consumer, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class},
                    makeRecords(TOPIC, 0, Collections.singletonList(
                            record(TOPIC, 0, 5L, "msg".getBytes()))));

            verify(mockKafkaConsumer, never()).seek(any(), anyLong());
            verify(mockKafkaConsumer, never()).commitSync(any(Map.class));
        }
    }

    // ── commitRecordsAsBatch — multi-partition ─────────────────────────────────

    @Test
    void commitRecordsAsBatch_multiPartition_manualCommit_success_commitsAllPartitions()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        TopicPartition tp0 = new TopicPartition(TOPIC, 0);
        TopicPartition tp1 = new TopicPartition(TOPIC, 1);

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> partitionMap = new HashMap<>();
        partitionMap.put(tp0, Arrays.asList(
                record(TOPIC, 0, 10L, "a".getBytes()),
                record(TOPIC, 0, 11L, "b".getBytes())));
        partitionMap.put(tp1, Arrays.asList(
                record(TOPIC, 1, 20L, "c".getBytes()),
                record(TOPIC, 1, 21L, "d".getBytes())));

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(true);

            callPrivate(mc, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class}, new ConsumerRecords<>(partitionMap));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor =
                    ArgumentCaptor.forClass((Class) Map.class);
            verify(mockKafkaConsumer).commitSync(captor.capture());
            Map<TopicPartition, OffsetAndMetadata> committed = captor.getValue();
            assertEquals(12L, committed.get(tp0).offset(), "partition 0: lastOffset(11)+1");
            assertEquals(22L, committed.get(tp1).offset(), "partition 1: lastOffset(21)+1");
        }
    }

    @Test
    void commitRecordsAsBatch_multiPartition_manualCommit_failure_seeksAllPartitionsToFirstOffset()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        TopicPartition tp0 = new TopicPartition(TOPIC, 0);
        TopicPartition tp1 = new TopicPartition(TOPIC, 1);

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> partitionMap = new HashMap<>();
        partitionMap.put(tp0, Arrays.asList(
                record(TOPIC, 0, 10L, "a".getBytes()),
                record(TOPIC, 0, 11L, "b".getBytes())));
        partitionMap.put(tp1, Arrays.asList(
                record(TOPIC, 1, 20L, "c".getBytes()),
                record(TOPIC, 1, 21L, "d".getBytes())));

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(false);

            callPrivate(mc, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class}, new ConsumerRecords<>(partitionMap));

            verify(mockKafkaConsumer).seek(tp0, 10L);
            verify(mockKafkaConsumer).seek(tp1, 20L);
            verify(mockKafkaConsumer, never()).commitSync(any(Map.class));
        }
    }

    // ── commitRecordsAsBatch — retryCounter lifecycle ──────────────────────────

    @Test
    void commitRecordsAsBatch_failure_incrementsRetryCounter() throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();

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
                            record(TOPIC, 0, 5L, "msg".getBytes()))));

            assertEquals(1, (int) getField(mc, "retryCounter"));
        }
    }

    @Test
    void commitRecordsAsBatch_successAfterPreviousFailures_resetsRetryCounterToZero()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        setField(mc, "retryCounter", 2);

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(true);

            callPrivate(mc, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class},
                    makeRecords(TOPIC, 0, Collections.singletonList(
                            record(TOPIC, 0, 5L, "msg".getBytes()))));

            assertEquals(0, (int) getField(mc, "retryCounter"));
        }
    }

    @Test
    void commitRecordsAsBatch_retryExhaustion_resetsRetryCounterToZero() throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        setField(mc, "retryCounter", 3);

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
                            record(TOPIC, 0, 5L, "msg".getBytes()))));

            assertEquals(0, (int) getField(mc, "retryCounter"));
        }
    }

    // ── commitRecordsAsBatch — failureRetryCount edge values ───────────────────

    @Test
    void commitRecordsAsBatch_infiniteRetryCount_alwaysSeeksNeverCommits() throws Exception {
        // failureRetryCount=-1 is the default when the property is absent
        Properties p = baseProps(true, false);
        p.setProperty(KafkaConstants.ENABLE_AUTO_COMMIT, "false");
        KafkaMessageConsumer mc = new KafkaMessageConsumer(p, ENDPOINT_NAME,
                synapseEnvironment, 1000L, INBOUND_SEQ, ERROR_SEQ, false, true);
        setField(mc, "consumer", mockKafkaConsumer);
        setField(mc, "retryCounter", 100);  // large value; infinite mode never exhausts

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
                            record(TOPIC, 0, 5L, "msg".getBytes()))));

            verify(mockKafkaConsumer).seek(new TopicPartition(TOPIC, 0), 5L);
            verify(mockKafkaConsumer, never()).commitSync(any(Map.class));
        }
    }

    @Test
    void commitRecordsAsBatch_zeroRetryCount_exhaustsImmediatelyOnFirstFailure() throws Exception {
        Properties p = baseProps(true, false);
        p.setProperty(KafkaConstants.ENABLE_AUTO_COMMIT, "false");
        p.setProperty(KafkaConstants.FAILURE_RETRY_COUNT, "0");
        KafkaMessageConsumer mc = new KafkaMessageConsumer(p, ENDPOINT_NAME,
                synapseEnvironment, 1000L, INBOUND_SEQ, ERROR_SEQ, false, true);
        setField(mc, "consumer", mockKafkaConsumer);
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
                            record(TOPIC, 0, 5L, "msg".getBytes()))));

            verify(mockKafkaConsumer, never()).seek(any(), anyLong());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor =
                    ArgumentCaptor.forClass((Class) Map.class);
            verify(mockKafkaConsumer).commitSync(captor.capture());
            assertEquals(6L, captor.getValue().get(tp).offset());
            // batch injection + error injection = 2 calls
            verify(synapseEnvironment, times(2)).injectInbound(any(), any(), anyBoolean());
        }
    }

    // ── commitRecordsAsBatch — DLQ ─────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void commitRecordsAsBatch_poisonPill_dlqConfigured_sendsRecordToDlq() throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        KafkaProducer<byte[], byte[]> mockDlqProducer = mock(KafkaProducer.class);
        setField(mc, "dlqTopic", "dlq-topic");
        setField(mc, "dlqProducer", mockDlqProducer);

        callPrivate(mc, "commitRecordsAsBatch",
                new Class[]{ConsumerRecords.class},
                makeRecords(TOPIC, 0, Collections.singletonList(
                        poisonPillRecord(TOPIC, 0, 3L, "deser error"))));

        verify(mockDlqProducer).send(any(), any());
    }

    // ── commitRecordsAsBatch — interleaved poison pill deduplication ──────────────

    @Test
    @SuppressWarnings("unchecked")
    void commitRecordsAsBatch_interleavedPoisonPill_onRetry_notRepublishedToDlq() throws Exception {
        // [offset 10: valid, offset 11: poison pill, offset 12: valid]
        // First call fails → seek back to 10. Second call presents the same records.
        // The poison pill at 11 must be sent to the DLQ exactly once.
        KafkaMessageConsumer mc = manualCommitConsumer();
        KafkaProducer<byte[], byte[]> mockDlqProducer = mock(KafkaProducer.class);
        setField(mc, "dlqTopic", "dlq-topic");
        setField(mc, "dlqProducer", mockDlqProducer);

        ConsumerRecords<byte[], byte[]> batch = makeRecords(TOPIC, 0, Arrays.asList(
                record(TOPIC, 0, 10L, "a".getBytes()),
                poisonPillRecord(TOPIC, 0, 11L, "bad deser"),
                record(TOPIC, 0, 12L, "b".getBytes())));

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(false);

            callPrivate(mc, "commitRecordsAsBatch", new Class[]{ConsumerRecords.class}, batch);
            callPrivate(mc, "commitRecordsAsBatch", new Class[]{ConsumerRecords.class}, batch);

            verify(mockDlqProducer, times(1)).send(any(), any());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitRecordsAsBatch_poisonPillWithValidRecords_onSuccess_dlqPublishedOffsetsCleared()
            throws Exception {
        // After a successful batch commit the tracking set must be cleared so the
        // next independent batch can re-use offset numbers without false dedup.
        KafkaMessageConsumer mc = manualCommitConsumer();
        KafkaProducer<byte[], byte[]> mockDlqProducer = mock(KafkaProducer.class);
        setField(mc, "dlqTopic", "dlq-topic");
        setField(mc, "dlqProducer", mockDlqProducer);

        ConsumerRecords<byte[], byte[]> batch = makeRecords(TOPIC, 0, Arrays.asList(
                poisonPillRecord(TOPIC, 0, 5L, "bad deser"),
                record(TOPIC, 0, 6L, "ok".getBytes())));

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(true);

            callPrivate(mc, "commitRecordsAsBatch", new Class[]{ConsumerRecords.class}, batch);

            Map<?, ?> tracking = (Map<?, ?>) getField(mc, "dlqPublishedOffsets");
            assertTrue(tracking.isEmpty(),
                    "dlqPublishedOffsets must be cleared after a successful commit");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitRecordsAsBatch_poisonPillWithValidRecords_onRetryExhaustion_dlqPublishedOffsetsCleared()
            throws Exception {
        KafkaMessageConsumer mc = manualCommitConsumer();
        setField(mc, "retryCounter", 3); // already at failureRetryCount=3
        KafkaProducer<byte[], byte[]> mockDlqProducer = mock(KafkaProducer.class);
        setField(mc, "dlqTopic", "dlq-topic");
        setField(mc, "dlqProducer", mockDlqProducer);

        ConsumerRecords<byte[], byte[]> batch = makeRecords(TOPIC, 0, Arrays.asList(
                poisonPillRecord(TOPIC, 0, 5L, "bad deser"),
                record(TOPIC, 0, 6L, "ok".getBytes())));

        try (MockedStatic<BuilderUtil> bu = mockStatic(BuilderUtil.class);
             MockedStatic<TransportUtils> tu = mockStatic(TransportUtils.class);
             MockedConstruction<SOAPBuilder> ignored = mockConstruction(SOAPBuilder.class,
                     (m, ctx) -> when(m.processDocument(any(), anyString(), any()))
                             .thenReturn(omElement))) {
            stubAxis2(bu, tu);
            when(synapseEnvironment.injectInbound(any(), any(), anyBoolean())).thenReturn(false);

            callPrivate(mc, "commitRecordsAsBatch", new Class[]{ConsumerRecords.class}, batch);

            Map<?, ?> tracking = (Map<?, ?>) getField(mc, "dlqPublishedOffsets");
            assertTrue(tracking.isEmpty(),
                    "dlqPublishedOffsets must be cleared after retry exhaustion commit");
        }
    }

    // ── commitRecordsAsBatch — null + valid mix, failure seek ──────────────────

    @Test
    void commitRecordsAsBatch_nullBeforeValidRecord_failure_seeksToFirstValidOffset()
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

            // offset 5: null (skipped), offset 7: first valid record
            callPrivate(mc, "commitRecordsAsBatch",
                    new Class[]{ConsumerRecords.class},
                    makeRecords(TOPIC, 0, Arrays.asList(
                            nullValueRecord(TOPIC, 0, 5L),
                            record(TOPIC, 0, 7L, "msg".getBytes()))));

            // seek must land on the first valid record's offset, not the null record's offset
            verify(mockKafkaConsumer).seek(tp, 7L);
            verify(mockKafkaConsumer, never()).commitSync(any(Map.class));
        }
    }

    // ── suspend configuration ───────────────────────────────────────────────────

    @Test
    void suspend_whenPropertySetToTrue_isPausedIsTrue() throws Exception {
        Properties props = baseProps(true, false);
        props.setProperty(KafkaConstants.SUSPEND, "true");
        KafkaMessageConsumer c = new KafkaMessageConsumer(props, ENDPOINT_NAME,
                synapseEnvironment, 1000L, INBOUND_SEQ, ERROR_SEQ, false, true);
        assertTrue((Boolean) getField(c, "isPaused"),
                "isPaused should be true when suspend property is set to true");
    }

    @Test
    void suspend_whenPropertyAbsent_defaultIsFalse() throws Exception {
        assertFalse((Boolean) getField(consumer, "isPaused"),
                "isPaused should default to false when suspend property is absent");
    }

    @Test
    void suspend_whenPropertySetToTrue_pollDoesNotConsumeRecords() throws Exception {
        Properties props = baseProps(true, false);
        props.setProperty(KafkaConstants.SUSPEND, "true");
        KafkaMessageConsumer c = new KafkaMessageConsumer(props, ENDPOINT_NAME,
                synapseEnvironment, 1000L, INBOUND_SEQ, ERROR_SEQ, false, true);
        setField(c, "consumer", mockKafkaConsumer);

        c.poll();

        verify(mockKafkaConsumer, never()).poll(any(Duration.class));
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
