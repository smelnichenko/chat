package io.schnappy.chat.config;

import com.datastax.oss.driver.api.core.CqlSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.InetSocketAddress;

@Configuration
@Profile("!test")
public class ScyllaConfig {

    @Bean
    public CqlSession cqlSession(ChatProperties props) {
        var scylla = props.scylla();
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress(scylla.contactPoints(), scylla.port()))
                .withLocalDatacenter(scylla.datacenter())
                .withKeyspace(scylla.keyspace())
                .build();
    }
}
