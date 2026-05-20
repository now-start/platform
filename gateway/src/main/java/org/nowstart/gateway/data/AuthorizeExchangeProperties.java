package org.nowstart.gateway.data;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "security")
public class AuthorizeExchangeProperties {

    private List<String> users;
    private List<String> publicPaths;

    public List<PathRule> getRules() {
        List<PathRule> rules = new ArrayList<>();

        if (users != null) {
            users.forEach(path ->
                    rules.add(new PathRule(path, List.of(Role.USERS)))
            );
        }

        if (publicPaths != null) {
            publicPaths.forEach(path -> rules.add(new PathRule(path, List.of())));
        }

        return rules;
    }

    public record PathRule(String path, List<Role> roles) {
    }
}
