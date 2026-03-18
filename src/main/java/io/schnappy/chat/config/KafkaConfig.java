package io.schnappy.chat.config;

import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafka;

@Configuration
@EnableKafka
@Import(KafkaAutoConfiguration.class)
@Profile("!test")
public class KafkaConfig {
}
