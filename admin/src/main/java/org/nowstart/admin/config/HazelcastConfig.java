package org.nowstart.admin.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.MapConfig;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static de.codecentric.boot.admin.server.config.AdminServerHazelcastAutoConfiguration.DEFAULT_NAME_EVENT_STORE_MAP;

@Configuration(proxyBeanMethods = false)
public class HazelcastConfig {
    @Bean
    public Config adminHazelcastConfig(
            @Value("${spring.application.name}") String applicationName,
            @Value("${admin.cluster.members:tasks.${spring.application.name}}") List<String> members,
            @Value("${admin.cluster.interfaces:127.0.0.1}") List<String> interfaces) {
        Config config = new Config();
        config.setClusterName(applicationName);
        config.getNetworkConfig().getInterfaces().setEnabled(true).setInterfaces(interfaces);
        var join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(members);
        config.addMapConfig(new MapConfig(DEFAULT_NAME_EVENT_STORE_MAP)
                .setInMemoryFormat(InMemoryFormat.OBJECT));
        return config;
    }
}
