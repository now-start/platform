package org.nowstart.gateway.config;

import lombok.RequiredArgsConstructor;
import org.nowstart.gateway.data.AuthorizeExchangeProperties;
import org.nowstart.gateway.data.Role;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.CollectionUtils;

@Configuration
@RequiredArgsConstructor
public class GatewayUserDetailsServiceConfig {

    private final AuthorizeExchangeProperties authorizeProperties;

    @RefreshScope
    @Bean
    public ReactiveUserDetailsService reactiveUserDetailsService(SecurityProperties securityProperties) {
        if (!CollectionUtils.isEmpty(authorizeProperties.getBasicUsers())) {
            UserDetails[] users = authorizeProperties.getBasicUsers().stream()
                    .map(this::toUserDetails)
                    .toArray(UserDetails[]::new);
            return new MapReactiveUserDetailsService(users);
        }

        SecurityProperties.User user = securityProperties.getUser();
        return new MapReactiveUserDetailsService(User.withUsername(user.getName())
                .password(user.getPassword())
                .roles(user.getRoles().toArray(String[]::new))
                .build());
    }

    private UserDetails toUserDetails(AuthorizeExchangeProperties.BasicUser basicUser) {
        return User.withUsername(basicUser.getUsername())
                .password(basicUser.getPassword())
                .roles(basicUser.getEffectiveRoles().stream().map(Role::name).toArray(String[]::new))
                .build();
    }
}
