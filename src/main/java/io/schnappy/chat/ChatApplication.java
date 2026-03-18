package io.schnappy.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"io.schnappy.chat", "io.schnappy.common"})
@ConfigurationPropertiesScan(basePackages = {"io.schnappy.chat.config", "io.schnappy.common.config"})
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
