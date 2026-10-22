package org.nowstart.gateway.audit

import io.netty.util.NetUtil
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component

@Component
class AuditClientAddressResolver(
    @param:Value("\${spring.cloud.gateway.server.webflux.trusted-proxies:}") trustedProxies: String,
) {
    private val trusted = trustedProxies.takeIf { it.isNotBlank() }?.toRegex()

    fun resolve(request: ServerHttpRequest): String? {
        val remote =
            request.remoteAddress?.address?.hostAddress ?: return request.remoteAddress?.hostString
        if (trusted?.matches(remote) != true) return remote
        val forwarded =
            request.headers["X-Forwarded-For"]
                .orEmpty()
                .flatMap { it.split(',') }
                .map { it.trim().removeSurrounding("[", "]") }
        if (
            forwarded.isEmpty() ||
                forwarded.any { !NetUtil.isValidIpV4Address(it) && !NetUtil.isValidIpV6Address(it) }
        ) {
            return remote
        }
        // 신뢰 프록시에서 클라이언트 방향으로 확인해 위조될 수 있는 맨 왼쪽 주소를 그대로 사용하지 않는다.
        return forwarded.asReversed().firstOrNull { !trusted.matches(it) } ?: forwarded.first()
    }
}
