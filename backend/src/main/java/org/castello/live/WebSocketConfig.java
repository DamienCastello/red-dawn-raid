package org.castello.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {

        registry.addEndpoint("/ws")
                .setAllowedOrigins(
                        "http://localhost:4200",
                        "https://red-dawn-raid-preprod.castello.ovh",
                        "https://red-dawn-raid.castello.ovh"
                );

        registry.addEndpoint("/ws-sockjs")
                .setAllowedOrigins(
                        "http://localhost:4200",
                        "https://red-dawn-raid-preprod.castello.ovh",
                        "https://red-dawn-raid.castello.ovh"
                )
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry r) {
        r.enableSimpleBroker("/topic");
        r.setApplicationDestinationPrefixes("/app");
    }
}
