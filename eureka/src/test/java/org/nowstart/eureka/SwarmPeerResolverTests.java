package org.nowstart.eureka;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.naming.NamingException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SwarmPeerResolverTests {

    @Test
    void discoversAddedPeersAndReplacedTaskAddressesAndRemovesLastPeer() {
        var addresses = new AtomicReference<>(Set.of("10.0.0.1"));
        var resolver = new SwarmPeerResolver("tasks.eureka", 8761,
                name -> addresses.get(), () -> Set.of("10.0.0.1", "10.1.0.1"));
        assertThat(resolver.resolve()).isEmpty();

        addresses.set(Set.of("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.1.0.1"));
        assertThat(resolver.resolve()).containsExactly(
                "http://10.0.0.2:8761/eureka/", "http://10.0.0.3:8761/eureka/");

        addresses.set(Set.of("10.0.0.1", "10.0.0.4"));
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.4:8761/eureka/");

        addresses.set(Set.of("10.0.0.1"));
        assertThat(resolver.resolve()).isEmpty();
    }

    @Test
    void keepsLastKnownPeersOnDnsFailureOrEmptyOrUnrelatedAnswers() {
        var addresses = new AtomicReference<>(Set.of("10.0.0.1", "10.0.0.2"));
        var resolver = new SwarmPeerResolver("tasks.eureka", 8761, name -> {
            if (addresses.get() == null) {
                throw new NamingException("temporary failure");
            }
            return addresses.get();
        }, () -> Set.of("10.0.0.1"));

        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/");
        addresses.set(null);
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/");
        addresses.set(Set.of());
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/");
        addresses.set(Set.of("10.9.0.2"));
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/");
    }

    @Test
    void permitsStartupBeforeDnsIsAvailable() {
        var resolver = new SwarmPeerResolver("tasks.eureka", 8761,
                name -> { throw new NamingException("not yet registered"); },
                () -> Set.of("10.0.0.1"));
        assertThat(resolver.resolve()).isEmpty();
    }
}
