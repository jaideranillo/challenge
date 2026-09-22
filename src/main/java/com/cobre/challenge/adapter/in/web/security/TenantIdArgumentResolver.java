package com.cobre.challenge.adapter.in.web.security;

import com.cobre.challenge.domain.model.tenant.TenantId;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Binds a {@link TenantId} handler parameter to the current request's authenticated tenant. */
public final class TenantIdArgumentResolver implements HandlerMethodArgumentResolver {

    private final AuthenticatedTenantResolver tenantResolver;

    public TenantIdArgumentResolver(AuthenticatedTenantResolver tenantResolver) {
        this.tenantResolver = tenantResolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return TenantId.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        return tenantResolver.resolve(SecurityContextHolder.getContext().getAuthentication());
    }
}
