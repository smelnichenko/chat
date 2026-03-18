package io.schnappy.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "monitor.chat")
public record ChatProperties(
    boolean e2eEnabled,
    ScyllaProperties scylla
) {
    public record ScyllaProperties(
        String contactPoints,
        int port,
        String datacenter,
        String keyspace
    ) {}
}
