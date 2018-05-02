# Kafka Inbound Endpoint

Kafka is a distributed publish-subscribe messaging system that maintains feeds of messages in topics. Producers write data to topics and consumers read from topics. For more information on Apache Kafka, see [Apache Kafka documentation](http://kafka.apache.org/documentation.html). 

The Kafka inbound endpoint acts as a message consumer. It creates a connection to Zookeeper and requests messages for a topic.

## Getting started
To start consuming the messages produced by the kafka producer via a topic, see [Configuring Kafka Inbound Endpoint](config.md). 

## Additional information
To download the inbound JAR file, go to [https://store.wso2.com/store/assets/esbconnector/kafka](https://store.wso2.com/store/assets/esbconnector/details/b15e9612-5144-4c97-a3f0-179ea583be88), and click Download Inbound Endpoint.  To use the inbound endpoint with ESB, copy the downloaded JAR file to the <ESB_HOME>/repository/components/dropins directory and restart your ESB server.
