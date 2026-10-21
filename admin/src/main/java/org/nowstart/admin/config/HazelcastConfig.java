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
            @Value("${admin.cluster.bind-host:}") String bindHost)
            throws SocketException, UnknownHostException {
        Config config = new Config();
        config.setClusterName(applicationName);
        var discoveredAddresses = new ArrayList<InetAddress>();
        // Resolve the bind network independently of peers, which may not be healthy yet.
        for (String member : bindHost.isBlank() ? members : List.of(bindHost)) {
            discoveredAddresses.addAll(List.of(InetAddress.getAllByName(member)));
        }
        List<LocalAddress> localAddresses;
        try (var interfaces = NetworkInterface.networkInterfaces()) {
            localAddresses = interfaces.flatMap(network -> network.getInterfaceAddresses().stream())
                    .map(address -> new LocalAddress(address.getAddress(), address.getNetworkPrefixLength()))
                    .toList();
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
    static String selectLocalAddress(List<InetAddress> discoveredAddresses, List<LocalAddress> localAddresses) {
        var ipv4Addresses = localAddresses.stream()
                .filter(local -> local.address() instanceof Inet4Address)
                .toList();
        var candidates = ipv4Addresses.stream()
                .filter(local -> discoveredAddresses.contains(local.address()))
                .map(LocalAddress::address).distinct().toList();
        if (candidates.isEmpty()) {
            candidates = ipv4Addresses.stream()
                    .filter(local -> discoveredAddresses.stream().anyMatch(local::contains))
                    .map(LocalAddress::address).distinct().toList();
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException((candidates.isEmpty()
                    ? "No local IPv4 address matches" : "Multiple local IPv4 addresses match")
                    + " admin.cluster.members DNS results; dns="
                    + discoveredAddresses.stream().map(InetAddress::getHostAddress).toList()
                    + ", local=" + localAddresses);
        }
        return candidates.getFirst().getHostAddress();
    }

    record LocalAddress(InetAddress address, int prefixLength) {
        boolean contains(InetAddress peer) {
            if (!(address instanceof Inet4Address) || !(peer instanceof Inet4Address)
                    || prefixLength <= 0 || prefixLength > 32) {
                return false;
            }
            byte[] localBytes = address.getAddress();
            byte[] peerBytes = peer.getAddress();
            for (int bit = 0; bit < prefixLength; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((localBytes[bit / 8] & mask) != (peerBytes[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return address.getHostAddress() + "/" + prefixLength;
        }
    }
}
