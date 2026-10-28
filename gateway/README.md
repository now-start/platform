# Gateway 세션·감사 이벤트

Gateway는 임베디드 Hazelcast 클러스터로 로그인 세션과 감사 이벤트를 공유한다.
Admin의 Hazelcast 클러스터와 이름·데이터를 분리하며 외부 Redis는 사용하지 않는다.

## 클러스터와 세션

Config Server의 `config/src/main/resources/config/gateway/gateway.yaml`에서
`app.cluster.name`, `members`, `network-cidr`, `port`를 설정한다.
운영 기본값은 `gateway`, `tasks.platform_gateway.platform_default`, `10.0.11.0/24`, `5701`이다.
CIDR에 속하는 로컬 IPv4 하나를 선택하며, 멤버 DNS가 비어 있어도 주소를 선택할 수 있다.
일치하는 주소가 없거나 여러 개이면 기동 오류로 처리한다. GitOps subnet과 network-cidr를 일치시킨다.
로컬에서는 network-cidr를 `127.0.0.0/8`로 지정하며, 여러 인스턴스는 같은 클러스터 이름과 서로 접근 가능한 멤버 주소를 사용한다.
Hazelcast 포트는 클러스터 내부 통신용이며 외부에 공개하지 않는다.

- 세션 맵: `nowstart:gateway:sessions`; 감사 맵: `nowstart:gateway:audit`.
- 두 맵은 동기 백업 1개를 사용한다. 멤버 2개 이상에서 한 멤버 장애를 견딜 수 있다.
- Hazelcast 공식 `HazelcastIndexedSessionRepository`의 속성 변경 병합·세션 ID 교체·만료 처리를 사용한다.
  WebFlux 어댑터는 동기 저장소 호출을 `boundedElastic`에서 실행한다.
- `spring.session.timeout`으로 세션 유효 시간을 설정하며 기본값은 30분이다.
- OAuth 인증 정보와 authorized client는 기존처럼 서버 세션에 보관한다.
- 전체 Gateway가 종료되면 세션과 감사 기록이 모두 초기화된다. 디스크 영속화는 사용하지 않는다.
- Redis에서 Hazelcast로 전환할 때 기존 Redis 세션·감사 기록은 이관하지 않으므로 다시 로그인해야 한다.
  전환 중 Redis 버전과 Hazelcast 버전 Gateway는 세션을 공유하지 않으므로 혼합 운영을 피한다.

## 감사 이벤트

OAuth2와 HTTP Basic 인증 성공·실패, 인증된 사용자의 접근 거부를 기록한다.
Spring Boot Admin은 기존 `/actuator/auditevents`로 시각·주체·종류를 조회한다.
HTTP 요청 이력은 기존 메모리 저장소를 유지한다.

- 이벤트별 UUID 키로 저장하고 발생 시각 기준 30일 TTL을 적용한다. 이미 만료된 이벤트는 저장하지 않는다.
- 저장 필드는 timestamp, principal, type 및 details.remoteAddress/sessionId로 제한한다.
  비밀번호·OAuth 토큰·예외 메시지는 저장하지 않는다.
- 기존 세션 ID만 기록하며 감사 때문에 빈 세션이나 쿠키를 만들지 않는다.
- 저장은 `boundedElastic`에서 실행한다. 저장 실패는 예외 타입만 경고하고 인증 응답을 계속한다.
  조회 실패는 오류로 전달하며 별도 재시도·대체 저장은 하지 않는다.
- 조회는 클러스터의 보존 이벤트를 읽어 필터링한다. 30일 TTL이 메모리 사용량 상한을 보장하지는 않는다.

## 실제 접속 IP

감사 이벤트는 연결 주소가 `spring.cloud.gateway.server.webflux.trusted-proxies`에 일치할 때만
`X-Forwarded-For`를 읽는다. 오른쪽부터 신뢰 프록시를 제외하고 처음 만나는 주소를 클라이언트 IP로 기록한다.
직접 연결, 누락되거나 잘못된 헤더에서는 연결 주소를 사용한다. 신뢰 프록시 설정이 없으면 헤더를 사용하지 않는다.
앞단 프록시는 외부 입력 전달 헤더를 정리하거나 실제 연결 IP를 체인에 추가해야 한다.

하위 서비스 전달은 기존 설정을 유지한다. `forwarded.enabled=false`로 `Forwarded`를 제거하고,
`x-forwarded.for-enabled=false`로 `X-Forwarded-For`를 재작성하지 않는다.
`x-forwarded.port-enabled=false`로 내부 HTTP 포트가 외부 HTTPS URL에 섞이지 않게 한다.
Gateway 공개 포트는 신뢰 프록시에서만 접근 가능해야 한다.
관련 작업: [barosteel #69](https://github.com/now-start/barosteel/issues/69).

## 검증

`./gradlew :gateway:test`로 RBAC 기본 정책·설정 교체, 로그인 전용 경로, 신뢰 프록시 IP,
세션 ID 교체·동시 속성 변경·만료, 감사 필드 제한·30일 TTL을 검증한다.
`HazelcastSessionIntegrationTest`는 실제 Gateway 두 개에서 OAuth 세션·토큰·감사 공유 및
원본 Hazelcast 멤버 강제 종료 후 다른 Gateway의 조회를 검증한다. Docker는 필요하지 않다.

## URL별 RBAC 설정

`gateway.rbac`는 `RbacProperties`에 바인딩된다. 기본 정책은 Gateway의
`src/main/resources/application.yaml`에 있으며, Config Server의
`config/src/main/resources/config/gateway/gateway.yaml`에서 같은 키로 덮어쓸 수 있다.
코드의 역할 enum이나 경로 배열을 수정할 필요 없이 역할·그룹·경로를 설정한다.

- 어떤 규칙에도 일치하지 않는 URL은 `default-role: ADMINISTRATORS`로 관리자만 허용한다.
- `rules`와 `paths`의 나열 순서에 관계없이 Spring 경로 패턴의 구체성을 비교해 가장 구체적인 경로를 적용한다.
  예를 들어 `/service/**`가 관리자 전용이어도 `/service/public/**`를 공개로 설정하면 해당 하위 경로는 공개된다.
- 가장 구체적인 일치 경로가 여러 개이고 정책이 서로 다르면 접근을 거부한다. 같은 정책이면 허용 여부를 정상 평가한다.
- 규칙마다 `role`, `authenticated: true`, `permit-all: true` 중 정확히 하나를 지정한다.
- `authenticated`는 역할 없이 로그인만 해도 허용한다. `permit-all`은 익명 요청도 허용한다.
- `role`과 역할 상속에는 `ROLE_` 접두사 없는 대문자 이름을 사용한다.
- OAuth 그룹은 `groups-attribute`에서 읽고 `group-roles`에 있는 그룹만 대소문자 구분 없이 매핑한다.
  기존 인증 권한은 보존하며, `hierarchy`는 OAuth와 Basic 인증 모두에 적용한다.
- 누락된 필수 설정, 잘못된 규칙·경로·역할, 중복 그룹, 순환 역할 상속은 시작 시 실패한다.
- 설정 변경 후 Gateway를 재시작한다. 실행 중인 보안 필터 체인의 동적 갱신은 지원하지 않는다.

다음은 세 가지 접근 방식을 보여주는 예시이며, 기존 운영 정책을 대체하는 목록은 아니다.

```yaml
gateway:
  rbac:
    default-role: ADMINISTRATORS
    groups-attribute: groups
    group-roles:
      users: USERS
      administrators: ADMINISTRATORS
    hierarchy:
      ADMINISTRATORS: [USERS]
    rules:
      - paths: [/oauth2/authorization/**, /login/oauth2/code/**]
        permit-all: true
      - paths: [/reports/private/**]
        role: USERS
      - paths: [/account/**]
        authenticated: true
      - paths: [/reports/public/**]
        permit-all: true
```

Spring 설정 우선순위에 따라 **리스트는 통째로 교체**된다. Config Server에서 `rules`를 지정하면
기본 목록에 추가되는 것이 아니므로 OAuth 로그인 경로를 포함해 필요한 규칙 전체를 작성한다.
맵(`group-roles`, `hierarchy`)은 설정 소스 간 병합될 수 있으므로 기존 매핑 제거 시 원래 설정도 수정한다.
기본 정책의 `/*/v3/api-docs`는 `/barosteel/**` 같은 포괄 경로보다 구체적이므로 USERS 권한을 요구한다.

## 기본 재시도

Config Server의 `config/gateway/gateway.yaml`에서 `default-filters`에 CircuitBreaker 다음으로 `name: Retry`를 지정한다. Retry는 별도 인자 없이 Spring Cloud Gateway 기본 정책을 사용한다.
GET 요청만 전체 HTTP 5xx 응답, `IOException`, Gateway의 `TimeoutException`에 최대 3회 재시도한다(최초 요청 포함 최대 4회). HEAD·POST·PUT·PATCH·DELETE·OPTIONS는 재시도하지 않는다.
재시도만으로 정상 인스턴스 선택이나 롤링 배포 무중단을 보장하지 않는다.

## 서비스별 서킷

Reactive Resilience4j starter와 `default-filters`의 `name: CircuitBreaker`를 추가하고 TimeLimiter만 비활성화한다.
필터 순서는 `CircuitBreaker → Retry → 하위 서비스`다. 별도 인자나 커스텀 필터는 없다.
서킷 이름을 생략하면 경로 ID를 사용하므로 Eureka 자동 탐색 경로는 서비스별로 분리된다.
각 Gateway 프로세스가 독립적으로 상태를 보관하며 Hazelcast로 공유하지 않는다.

- 서킷 기본값: 최근 100건, 최소 100건, 실패율 50%, OPEN 대기 60초, HALF_OPEN 시험 10건.
- `statusCodes` 기본값은 빈 목록이다. 최종 HTTP 5xx 응답 자체는 서킷 실패로 집계하지 않는다.
  GET 5xx의 기본 Retry는 유지되며, 재시도 후 성공하면 서킷 성공 1건으로 집계한다.
- 최종 연결 오류 등 예외는 실패로 집계한다. OPEN에서 거부된 새 요청은 503으로 응답한다.
- `spring.cloud.circuitbreaker.resilience4j.disable-time-limiter=true`로 추가 TimeLimiter만 끈다.
  정상적인 2초 이상 요청을 1초에 끊지 않으며 기존 HTTP 클라이언트 타임아웃은 유지한다.
  서킷은 응답 본문 전달 전에 집계하므로 이후 본문 전송 오류는 집계하지 않는다.
- 서킷 Health Indicator는 별도로 활성화하지 않는다. 지표는 `resilience4j.circuitbreaker.*`로 조회한다.

`CircuitBreakerIntegrationTest`는 실제 HTTP 요청으로 기본 Retry와 서킷 집계, HTTP 오류 응답 보존,
OPEN 차단, 경로별 격리, HALF_OPEN 회복, 2초 응답 허용과 지표 등록을 검증한다.
중앙 설정만 먼저 배포하면 구버전 Gateway가 CircuitBreaker 필터를 찾지 못할 수 있으므로
의존성을 추가한 Gateway와 중앙 설정을 함께 반영한다. Admin 커스텀 화면은 별도 작업이다.

클러스터 설정 접두사는 Admin·Barosteel과 동일하게 `app.cluster`를 사용한다. 로컬 주소 선택도 동일한 `network-cidr` 방식이며, 원격 멤버 주소를 제한하는 interfaces 필터는 사용하지 않는다. Gateway 코드와 중앙 설정을 함께 반영한다.
