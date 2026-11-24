package org.nowstart.eureka

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.web.server.ForwardedRequestHeaders.Naming
import org.springframework.boot.web.server.autoconfigure.ConditionalOnForwardedHeaderStrategy
import org.springframework.boot.web.server.autoconfigure.ForwardedHeaderStrategy
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.boot.web.server.autoconfigure.servlet.ForwardedHeaderFilterCustomizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.ForwardedHeaderFilter

@Configuration(proxyBeanMethods = false)
@ConditionalOnForwardedHeaderStrategy(ForwardedHeaderStrategy.FRAMEWORK)
class EurekaForwardedHeadersConfiguration {

    @Bean
    fun eurekaForwardedHeaderFilter(
        properties: ServerProperties,
        customizers: ObjectProvider<ForwardedHeaderFilterCustomizer>,
    ): FilterRegistrationBean<ForwardedHeaderFilter> {
        val forwarded = properties.request.header.forwarded
        val useStandardHeader = forwarded.naming == Naming.RFC_7239
        val nativeEndpoints = ForwardedHeaderFilter(useStandardHeader)
        val filter =
            object : ForwardedHeaderFilter(useStandardHeader) {
                override fun doFilterInternal(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    filterChain: FilterChain,
                ) {
                    // Eureka's version filter matches static files against the original /eureka
                    // URI.
                    if (isNativeEurekaPath(request)) {
                        nativeEndpoints.doFilter(request, response, filterChain)
                    } else {
                        super.doFilterInternal(request, response, filterChain)
                    }
                }
            }
        filter.setUseForwardedPrefix(forwarded.isUsePrefix)
        customizers.ifAvailable {
            it.customize(filter)
            it.customize(nativeEndpoints)
        }
        nativeEndpoints.setUseForwardedPrefix(false)
        return FilterRegistrationBean<ForwardedHeaderFilter>(filter).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR)
        }
    }

    private fun isNativeEurekaPath(request: HttpServletRequest): Boolean {
        val errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String
        val path = errorUri?.removePrefix(request.servletContext.contextPath) ?: request.servletPath
        return path == "/eureka" || path.startsWith("/eureka/")
    }
}
