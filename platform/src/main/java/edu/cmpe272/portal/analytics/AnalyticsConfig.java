package edu.cmpe272.portal.analytics;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class AnalyticsConfig {

    @Bean
    LiveMetrics liveMetrics(AnalyticsProperties properties) {
        return new LiveMetrics(properties.liveWindow());
    }

    @Bean(destroyMethod = "close")
    AdminClient kafkaAdminClient(KafkaAdmin admin) {
        return AdminClient.create(admin.getConfigurationProperties());
    }
}
