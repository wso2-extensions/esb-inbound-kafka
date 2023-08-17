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

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.Headers;

import java.util.Arrays;

public class DeserializationException extends KafkaException {

    private final String topic;
    private transient Headers headers;
    private final byte[] data;
    private final boolean isKey;

    /**
     * Construct an instance with the provided properties.
     * @param message the message.
     * @param data the data (value or key).
     * @param isKey true if the exception occurred while deserializing the key.
     * @param cause the cause.
     */
    public DeserializationException(String message, String topic, byte[] data, boolean isKey, Throwable cause) {
        super(message, cause);
        this.topic = topic;
        this.data = data;
        this.isKey = isKey;
    }

    /**
     * Get the data that failed deserialization (value or key).
     * @return the data.
     */
    public byte[] getData() {
        return this.data;
    }

    /**
     * True if deserialization of the key failed, otherwise deserialization of the value
     * failed.
     * @return true for the key.
     */
    public boolean isKey() {
        return this.isKey;
    }

    /**
     * Get the headers.
     * @return the headers.
     */
    public Headers getHeaders() {
        return this.headers;
    }

    /**
     * Set the headers.
     * @param headers the headers.
     */
    public void setHeaders(Headers headers) {
        this.headers = headers;
    }

    @Override
    public String getMessage() {

        return "Error details{ message=" + super.getMessage()
                + ", topic=" + this.topic
                + ", data=" + Arrays.toString(this.data)
                + ", cause=" + getCause().getMessage()
                + " }";
    }

}
