package com.cobre.challenge.adapter.in.web.security.config;

import com.cobre.challenge.adapter.in.web.security.AuthenticatedTenantResolver;
import com.cobre.challenge.adapter.in.web.security.TenantIdArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link TenantIdArgumentResolver} so handlers can declare a {@code TenantId} parameter. */
@Configuration
public class TenantWebMvcConfig implements WebMvcConfigurer {

    @Bean
    public AuthenticatedTenantResolver authenticatedTenantResolver() {
        return new AuthenticatedTenantResolver();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new TenantIdArgumentResolver(authenticatedTenantResolver()));
    }
}
