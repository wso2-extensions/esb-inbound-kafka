# Kafka ESB Inbound

WSO2 ESB kafka inbound endpoint acts as a message consumer. It creates a connection to ZooKeeper and requests messages for a topic.

## Getting started

To get started with the inbound endpoint, go to [Configuring Kafka Inbound Endpoint](docs/config.md). Once you have completed your configurations, you can consume the messages that are produced by the kafka producer via a topic.   

## Additional information

To download the inbound JAR file, go to [https://store.wso2.com/store/assets/esbconnector/details/b15e9612-5144-4c97-a3f0-179ea583be88](https://store.wso2.com/store/assets/esbconnector/details/b15e9612-5144-4c97-a3f0-179ea583be88) and 
click **Download Inbound Endpoint**. To use the inbound endpoint with ESB, copy the downloaded JAR file to the `<ESB_HOME>/repository/components/dropins` directory and restart your ESB server.

## Build

mvn clean install

### How You Can Contribute

You can create a third party connector and publish in WSO2 Store.

https://docs.wso2.com/display/ESBCONNECTORS/Creating+and+Publishing+a+Third+Party+Connector