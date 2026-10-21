package org.nowstart.admin.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MergePolicyConfig;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static de.codecentric.boot.admin.server.config.AdminServerHazelcastAutoConfiguration.DEFAULT_NAME_EVENT_STORE_MAP;

@Configuration(proxyBeanMethods = false)
public class HazelcastConfig {
    @Bean
    public Config adminHazelcastConfig(
            @Value("${app.cluster.name:${spring.application.name}}") String name,
            @Value("${app.cluster.members}") List<String> members,
            @Value("${app.cluster.network-cidr}") String networkCidr,
            @Value("${app.cluster.port:5701}") int port) throws Exception {
        Config config = new Config();
        config.setClusterName(name);
        try (var interfaces = NetworkInterface.networkInterfaces()) {
            var addresses = interfaces.flatMap(NetworkInterface::inetAddresses).toList();
            config.setProperty("hazelcast.local.localAddress", selectLocalAddress(networkCidr, addresses));
        }
        config.setProperty("hazelcast.shutdownhook.policy", "GRACEFUL");
        config.setProperty("hazelcast.graceful.shutdown.max.wait", "90");
        var network = config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        var join = network.getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(members);
        config.addMapConfig(sharedMap("default"));
        config.addMapConfig(sharedMap(DEFAULT_NAME_EVENT_STORE_MAP).setInMemoryFormat(InMemoryFormat.OBJECT));
        return config;
    }

    private static MapConfig sharedMap(String name) {
        return new MapConfig(name).setBackupCount(1).setAsyncBackupCount(0)
                .setMergePolicyConfig(new MergePolicyConfig("PutIfAbsentMergePolicy", 100));
    }

    static String selectLocalAddress(String cidr, List<InetAddress> addresses) throws Exception {
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[0-9.]+")) {
            throw new IllegalArgumentException("Expected IPv4 CIDR: " + cidr);
        }
        InetAddress subnet = InetAddress.getByName(parts[0]);
        int prefix = Integer.parseInt(parts[1]);
        if (!(subnet instanceof Inet4Address) || prefix < 1 || prefix > 32) {
            throw new IllegalArgumentException("Expected IPv4 CIDR: " + cidr);
        }
        var candidates = addresses.stream().filter(address -> address instanceof Inet4Address)
                .filter(address -> {
                    for (int bit = 0; bit < prefix; bit++) {
                        int mask = 1 << (7 - bit % 8);
                        if ((address.getAddress()[bit / 8] & mask) != (subnet.getAddress()[bit / 8] & mask)) return false;
                    }
                    return true;
                }).distinct().toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException("Expected one local IPv4 in " + cidr + "; candidates=" + candidates);
        }
        return candidates.getFirst().getHostAddress();
    }
}
