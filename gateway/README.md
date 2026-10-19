# Gateway 감사 이벤트

Gateway의 OAuth2 및 HTTP Basic 인증 성공·실패와 인증된 사용자의 접근 거부를 Redis에 저장한다.
Spring Boot Admin은 기존 `/actuator/auditevents`를 사용해 시각·주체·종류로 조회한다.
기존 Redis 공유 세션과 HTTP 요청 이력 저장 방식은 유지한다.

## 저장 및 보존

- 감사 키 영역: `nowstart:gateway:audit:{events}:*`. 세션 키 영역과 분리한다.
- 이벤트마다 UUID 키와 JSON 본문을 만들고 발생 시각을 점수로 하는 sorted set 색인에 등록한다.
- 본문은 발생 시각 기준 30일 TTL로 Redis가 자동 삭제한다. 이미 30일이 지난 이벤트는 저장하지 않는다.
- 색인의 오래된 참조는 저장·조회 시 제거한다. 기록이 끊기면 색인 자체도 마지막 저장 후 30일에 만료된다.
- Lua로 본문·색인·TTL을 함께 갱신한다. Redis Cluster에서는 공통 hash tag로 같은 슬롯을 사용한다.
- 저장 필드는 timestamp, principal, type 및 data.details.remoteAddress/sessionId다. 기존 세션이 있는 경우 원본 ID를 저장한다.
- WebFlux이므로 Servlet용 WebAuthenticationDetails를 사용하지 않고 Admin이 읽는 동일한 JSON 경로를 제공한다.
- 인증 실패 시 주체를 확정할 수 없으므로 anonymousUser로 기록한다. 비밀번호·OAuth 토큰·예외 메시지는 저장하지 않는다.
- HTTP Basic은 인증 정보가 포함된 요청마다 성공 이벤트가 생길 수 있다. 일반 로그인 화면 접근은 인증 실패로 기록하지 않는다.

## 실행 및 장애 처리

감사 저장은 boundedElastic에서 실행하여 동기 Redis I/O가 WebFlux 이벤트 루프를 막지 않도록 한다.
저장 실패는 예외 타입만 경고 로그에 남기고 원래 인증 응답을 계속한다. 유실된 이벤트를 별도 재시도하거나 메모리에 대체 저장하지 않는다.
조회 실패는 오류로 전달되므로 Admin 화면에서 저장소 장애를 확인할 수 있다.
원격 주소는 요청 연결의 주소를 사용하며, 전달 헤더를 직접 신뢰해 해석하지 않는다. 현재 프록시 구성에서는 프록시 주소가 표시될 수 있다.

Redis 재시작 후 기록 보존 여부는 운영 Redis의 AOF/RDB·백업 및 eviction 정책에 따른다.
TTL은 장기 영속화나 메모리 상한을 보장하지 않으므로 세션과 공유하는 Redis의 메모리 사용량을 함께 확인한다.

## 검증

단위·보안 테스트는 인증 성공·실패·접근 거부, 저장 필드 제한, 조회 필터, 30일 TTL 인수, 기존 세션 ID 및 저장 실패 처리를 검증한다.
`RedisAuditIntegrationTest`는 Docker Redis로 Lua 실행, 저장소 간 공유, 실제 TTL 및 색인 정리를 검증하며 Docker가 없으면 건너뛴다.
기존 `RedisSessionIntegrationTest`는 두 Gateway 간 세션 공유를 검증하며 Docker가 필요하다.

## 실제 접속 IP 전달

`forwarded.enabled=false`로 `Forwarded` 헤더를 제거하고, `x-forwarded.for-enabled=false`로
신뢰 프록시에서 받은 `X-Forwarded-For`를 재작성하지 않는다. Gateway 5.0.3이 공인 접속 IP까지
`trusted-proxies`로 걸러내는 동작을 피하며, 하위 서비스의 `framework` 설정은 원래 IP를 읽는다.
`x-forwarded.port-enabled=false`로 내부 HTTP 포트가 외부 HTTPS URL에 섞이지 않게 한다.
`X-Forwarded-Proto`와 경로 접두사 처리는 유지한다. 앞단 프록시는 외부 입력 전달 헤더를 정리하고
접속 IP를 설정해야 하며, Gateway 공개 포트는 신뢰 프록시에서만 접근 가능해야 한다.
Swarm ingress 주소만으로 외부 호출자를 구분할 수는 없다.
관련 작업: [barosteel #69](https://github.com/now-start/barosteel/issues/69).
