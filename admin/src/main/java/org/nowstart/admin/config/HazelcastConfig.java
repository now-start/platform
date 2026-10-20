package org.nowstart.admin.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.MapConfig;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static de.codecentric.boot.admin.server.config.AdminServerHazelcastAutoConfiguration.DEFAULT_NAME_EVENT_STORE_MAP;

@Configuration(proxyBeanMethods = false)
public class HazelcastConfig {
    @Bean
    public Config adminHazelcastConfig(
            @Value("${spring.application.name}") String applicationName,
            @Value("${admin.cluster.members:tasks.${spring.application.name}}") List<String> members)
            throws SocketException, UnknownHostException {
        Config config = new Config();
        config.setClusterName(applicationName);
        var discoveredAddresses = new ArrayList<InetAddress>();
        for (String member : members) {
            discoveredAddresses.addAll(List.of(InetAddress.getAllByName(member)));
        }
        Set<InetAddress> localAddresses;
        try (var interfaces = NetworkInterface.networkInterfaces()) {
            localAddresses = interfaces.flatMap(NetworkInterface::inetAddresses).collect(Collectors.toSet());
        }
        // interfaces 설정은 원격 DNS 후보도 제한하므로 로컬 주소 선택에만 적용한다.
        config.setProperty("hazelcast.local.localAddress", selectLocalAddress(discoveredAddresses, localAddresses));
        var join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(members);
        config.addMapConfig(new MapConfig(DEFAULT_NAME_EVENT_STORE_MAP)
                .setInMemoryFormat(InMemoryFormat.OBJECT));
        return config;
    }
    static String selectLocalAddress(List<InetAddress> discoveredAddresses, Set<InetAddress> localAddresses) {
        return discoveredAddresses.stream()
                .filter(Inet4Address.class::isInstance)
                .filter(localAddresses::contains)
                .map(InetAddress::getHostAddress)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No local IPv4 address matches admin.cluster.members DNS results"));
    }
}
