package org.nowstart.eureka;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import com.sun.net.httpserver.HttpServer;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {EurekaServiceApplication.class, SwarmBootstrapTests.TestPeers.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.config.import=",
                "spring.cloud.config.enabled=false",
                "spring.boot.admin.client.enabled=false",
                "otel.sdk.disabled=true",
                "eureka.swarm.enabled=true",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=true",
                "eureka.server.registry-sync-retries=1"
        })
class SwarmBootstrapTests {

    private static final LinkedBlockingQueue<String> REPLICATIONS = new LinkedBlockingQueue<>();
    private static final HttpServer PEER = startPeer();

    @Autowired
    PeerAwareInstanceRegistry registry;

    @Autowired
    PeerEurekaNodes peers;

    @Test
    void startsServerWithExistingRegistrationsFetchedFromDiscoveredPeer() {
        assertThat(peers.getPeerNodesView()).hasSize(1);
        assertThat(peers.getPeerNodesView().getFirst().getServiceUrl())
                .isEqualTo("http://127.0.0.1:" + PEER.getAddress().getPort() + "/eureka/");
        assertThat(registry.getApplication("EXISTING")).isNotNull();
        assertThat(registry.getApplication("EXISTING").getByInstanceId("existing-1")).isNotNull();
    }

    @Test
    void replicatesRegistrationToDiscoveredPeerOverHttp() throws InterruptedException {
        registry.register(registry.getApplication("EXISTING").getByInstanceId("existing-1"), false);
        assertThat(REPLICATIONS.poll(10, TimeUnit.SECONDS))
                .contains("existing-1", "Register");
    }

    @AfterAll
    static void stopPeer() {
        PEER.stop(0);
    }

    private static HttpServer startPeer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = """
                    {"applications":{"versions__delta":"1","apps__hashcode":"UP_1_",
                      "application":[{"name":"EXISTING","instance":[{
                        "instanceId":"existing-1","hostName":"existing","app":"EXISTING",
                        "ipAddr":"10.0.0.9","status":"UP","overriddenStatus":"UNKNOWN",
                        "port":{"$":8080,"@enabled":"true"},
                        "securePort":{"$":443,"@enabled":"false"},
                        "dataCenterInfo":{"@class":"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo","name":"MyOwn"},
                        "leaseInfo":{"renewalIntervalInSecs":30,"durationInSecs":90},
                        "metadata":{},"vipAddress":"existing","secureVipAddress":"existing",
                        "isCoordinatingDiscoveryServer":"false",
                        "lastUpdatedTimestamp":"1","lastDirtyTimestamp":"1","actionType":"ADDED"
                      }]}]}}
                    """.getBytes(StandardCharsets.UTF_8);
            server.createContext("/eureka/apps", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
                finally {
                    exchange.close();
                }
            });
            server.createContext("/eureka/peerreplication/batch/", exchange -> {
                var input = exchange.getRequestBody();
                if ("gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                    input = new GZIPInputStream(input);
                }
                REPLICATIONS.add(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                input.close();
                byte[] response = "{\"replicationList\":[{\"statusCode\":200}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
                finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        }
        catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestPeers {
        @Bean
        SwarmPeerResolver swarmPeerResolver() {
            return new SwarmPeerResolver("tasks.eureka", PEER.getAddress().getPort(),
                    name -> Set.of("10.0.0.1", "127.0.0.1"), () -> Set.of("10.0.0.1"));
        }
    }
}
