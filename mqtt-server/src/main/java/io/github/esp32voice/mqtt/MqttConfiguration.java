package io.github.esp32voice.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

@Configuration
public class MqttConfiguration {
    @Bean
    MqttPahoClientFactory mqttClientFactory(
            @Value("${mqtt.url}") String url,
            @Value("${mqtt.username}") String username,
            @Value("${mqtt.password}") String password) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{url});
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        if (!username.isBlank()) {
            options.setUserName(username);
        }
        if (!password.isBlank()) {
            options.setPassword(password.toCharArray());
        }
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        factory.setConnectionOptions(options);
        return factory;
    }

    @Bean
    MessageChannel mqttInboundChannel() {
        return new DirectChannel();
    }

    @Bean
    MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    IntegrationFlow mqttInboundFlow(
            MqttPahoClientFactory factory,
            @Qualifier("mqttInboundChannel") MessageChannel mqttInboundChannel,
            @Value("${mqtt.subscriber-id}") String clientId,
            @Value("${mqtt.up-topic}") String topic) {
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(clientId, factory, topic);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInboundChannel);
        return IntegrationFlow.from(adapter).channel(mqttInboundChannel).get();
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    MessageHandler mqttOutbound(
            MqttPahoClientFactory factory,
            @Value("${mqtt.publisher-id}") String clientId) {
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(clientId, factory);
        handler.setAsync(true);
        handler.setDefaultQos(1);
        handler.setDefaultTopic("devices/unknown/down");
        return handler;
    }

    @Bean
    RedisMessageListenerContainer redisEvents(
            RedisConnectionFactory connectionFactory,
            DeviceEventForwarder forwarder) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(forwarder, new ChannelTopic("device:events"));
        return container;
    }
}
