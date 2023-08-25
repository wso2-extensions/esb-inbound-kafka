/*
 * Copyright (c) 2023, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.inbound.kafka.deserializer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.synapse.SynapseException;
import org.wso2.carbon.inbound.kafka.KafkaConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Map;

public class ErrorHandlingDeserializer<T> implements Deserializer<T> {

    private static final Log LOGGER = LogFactory.getLog(ErrorHandlingDeserializer.class);

    private Deserializer<T> delegate;

    private boolean isForKey;

    public ErrorHandlingDeserializer() {
    }

    public ErrorHandlingDeserializer(Deserializer<T> delegate) {
        this.delegate = setupDelegate(delegate);
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {

        if (this.delegate == null) {
            setupDelegate(configs, isKey ? KafkaConstants.KEY_DELEGATE_DESERIALIZER
                    : KafkaConstants.VALUE_DELEGATE_DESERIALIZER);
        }
        if (this.delegate == null) {
            LOGGER.error("No delegate deserializer configured");
        }
        this.delegate.configure(configs, isKey);
        this.isForKey = isKey;
    }

    @Override
    public T deserialize(String topic, byte[] data) {

        try {
            return this.delegate.deserialize(topic, data);
        } catch (Exception e) {
            Iterable<Header> recordHeaders = null;
            Headers headers = new RecordHeaders(recordHeaders);
            addDeserializationExceptionToHeaders(topic, headers, data, e, this.isForKey);
            return null;
        }
    }

    @Override
    public T deserialize(String topic, Headers headers, byte[] data) {

        try {
            if (this.isForKey) {
                headers.remove(KafkaConstants.KEY_DESERIALIZER_EXCEPTION_HEADER);
            }
            else {
                headers.remove(KafkaConstants.VALUE_DESERIALIZER_EXCEPTION_HEADER);
            }
            return this.delegate.deserialize(topic, headers, data);
        } catch (Exception e) {
            addDeserializationExceptionToHeaders(topic, headers, data, e, this.isForKey);
            return null;
        }
    }

    @Override
    public void close() {

        Deserializer.super.close();
    }

    private void setupDelegate(Map<String, ?> configs, String configKey) {
        if (configs.containsKey(configKey)) {
            try {
                Object value = configs.get(configKey);
                ClassLoader classLoader = getDefaultClassLoader();
                if (classLoader != null) {
                    Class<?> clazz = classLoader.loadClass(value.toString());
                    this.delegate = setupDelegate(clazz.getDeclaredConstructor().newInstance());
                } else {
                    throw new SynapseException("Could not setup the delegate as the ClassLoader was not accessible.");
                }
            } catch (Exception e) {
                throw new SynapseException("Could not setup the delegate.", e);
            }
        }
    }

    private Deserializer<T> setupDelegate(Object delegate) {
        if (!(delegate instanceof Deserializer)) {
            throw new SynapseException("'delegate' must be a 'Deserializer'");
        }
        return (Deserializer<T>) delegate;
    }

    /**
     * Populate the record headers with a serialized {@link DeserializationException}.
     * @param topic the topic.
     * @param headers the headers.
     * @param data the data.
     * @param ex the exception.
     * @param isForKeyArg true if this is a key deserialization problem, otherwise value.
     */
    private static void addDeserializationExceptionToHeaders(String topic, Headers headers, byte[] data,
                                                             Exception ex, boolean isForKeyArg) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        DeserializationException exception =
                new DeserializationException("Failed to deserialize the " + getType(isForKeyArg), topic, data,
                        isForKeyArg, ex);
        try (ObjectOutputStream oos = new ObjectOutputStream(stream)) {
            oos.writeObject(exception);
        }
        catch (IOException e) {
            stream = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(stream)) {
                exception = new DeserializationException("Failed to deserialize the "  + getType(isForKeyArg), topic,
                        data, isForKeyArg, new RuntimeException("Could not serialize type "
                        + ex.getClass().getName() + " with message " + e.getMessage()
                        + ". Original exception message: " + ex.getMessage()));
                oos.writeObject(exception);
            } catch (IOException ex2) {
                LOGGER.error("Poison pill is detected for Topic: " + topic
                        + ". However, unable to inject the error details to 'onError' sequence since"
                        + " there was an error while serializing a DeserializationException. " + ex2.getMessage());
                return;
            }
        }
        headers.add(new RecordHeader(isForKeyArg
                        ? KafkaConstants.KEY_DESERIALIZER_EXCEPTION_HEADER
                        : KafkaConstants.VALUE_DESERIALIZER_EXCEPTION_HEADER,
                        stream.toByteArray()));
    }

    private static String getType(boolean isForKey) {
        if (isForKey) {
            return "Key";
        }
        return "Value";
    }

    private static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ex) {
            // Cannot access thread context ClassLoader - falling back...
        }
        if (cl == null) {
            // No thread context class loader -> use class loader of this class.
            cl = ErrorHandlingDeserializer.class.getClassLoader();
            if (cl == null) {
                // getClassLoader() returning null indicates the bootstrap ClassLoader
                try {
                    cl = ClassLoader.getSystemClassLoader();
                } catch (Throwable ex) {
                    // Cannot access system ClassLoader
                }
            }
        }
        return cl;
    }
}
