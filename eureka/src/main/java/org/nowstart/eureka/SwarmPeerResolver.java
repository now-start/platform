package org.nowstart.eureka;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SwarmPeerResolver {

    private static final Logger log = LoggerFactory.getLogger(SwarmPeerResolver.class);
    private final String service;
    private final int port;
    private final AddressLookup lookup;
    private final LocalAddresses localAddresses;
    private List<String> lastKnownPeers = List.of();

    SwarmPeerResolver(String service, int port) {
        this(service, port, SwarmPeerResolver::lookupAddresses, SwarmPeerResolver::localAddresses);
    }

    SwarmPeerResolver(String service, int port, AddressLookup lookup, LocalAddresses localAddresses) {
        this.service = service;
        this.port = port;
        this.lookup = lookup;
        this.localAddresses = localAddresses;
    }

    synchronized List<String> resolve() {
        try {
            Set<String> addresses = lookup.find(service);
            // An empty DNS answer is not proof that all peers have left.
            if (addresses.isEmpty()) {
                throw new NamingException("No task addresses returned for " + service);
            }
            Set<String> ownAddresses = localAddresses.get();
            if (Collections.disjoint(addresses, ownAddresses)) {
                throw new NamingException("Task DNS does not include this container: " + service);
            }
            lastKnownPeers = addresses.stream()
                    .filter(address -> !ownAddresses.contains(address))
                    .sorted()
                    .map(address -> "http://" + address + ":" + port + "/eureka/")
                    .toList();
        }
        catch (NamingException | SocketException ex) {
            log.warn("Cannot refresh Swarm peers from {}; retaining previous peers: {}",
                    service, ex.getMessage());
        }
        return lastKnownPeers;
    }

    String localUrl() {
        return "http://127.0.0.1:" + port + "/eureka/";
    }

    private static Set<String> lookupAddresses(String service) throws NamingException {
        // Query Docker DNS directly; InetAddress's JVM-wide cache can hide task changes.
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("java.naming.provider.url", "dns:");
        environment.put("com.sun.jndi.dns.timeout.initial", "1000");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext context = new InitialDirContext(environment);
        try {
            var attribute = context.getAttributes(service, new String[] {"A"}).get("A");
            Set<String> addresses = new TreeSet<>();
            if (attribute != null) {
                for (int i = 0; i < attribute.size(); i++) {
                    addresses.add(attribute.get(i).toString());
                }
            }
            return addresses;
        }
        finally {
            context.close();
        }
    }

    private static Set<String> localAddresses() throws SocketException {
        return NetworkInterface.networkInterfaces()
                .flatMap(NetworkInterface::inetAddresses)
                .map(address -> address.getHostAddress())
                .collect(Collectors.toSet());
    }

    @FunctionalInterface
    interface AddressLookup {
        Set<String> find(String service) throws NamingException;
    }

    @FunctionalInterface
    interface LocalAddresses {
        Set<String> get() throws SocketException;
    }
}
