# Hazelcast·Eureka 서버 네트워크 설정

GitOps의 고정 overlay subnet을 유지하며 로컬 주소를 DNS 없이 CIDR로 선택한다.
Docker API 접근이나 별도 공통 라이브러리는 추가하지 않는다.

| 대상 | 설정 | 운영 대역 |
| --- | --- | --- |
| Admin·Gateway Hazelcast | `app.cluster.network-cidr` | `10.0.11.0/24` |
| Barosteel Hazelcast | `app.cluster.network-cidr` | `10.0.3.0/24` |
| Eureka 서버 간 피어 연결·자기 등록 주소 | `eureka.swarm.network-cidr` | `10.0.11.0/24` |

세 앱의 Hazelcast 설정은 `HazelcastConfiguration.hazelcastConfig`에서 구성한다.
`app.cluster.name`은 `spring.application.name`을 기본값으로 사용한다.
`members`·`network-cidr`는 필수이며 로컬 실행에서는 `127.0.0.1`, `127.0.0.0/8`을 지정한다.
기본 포트는 5701이고 자동 증가·자동 탐지·multicast를 끈다. TCP 멤버 탐색을 사용한다.
종료 대기는 90초이고 동기 백업 1개·비동기 백업 0개를 사용한다.
Admin·Gateway는 GRACEFUL 종료 훅을 사용한다. Barosteel은 HTTP graceful shutdown 이후 Spring이 인스턴스를 종료하도록 자체 종료 훅을 비활성화한다.
세션 직렬화·이벤트 저장소·캐시 TTL·병합 정책은 서비스별 기존 동작을 유지한다.

각 설정 클래스의 `selectLocalAddress`는 지정 CIDR에 속하는 로컬 IPv4 하나를 선택한다.
잘못된 CIDR, 일치 주소 없음, 여러 주소 일치는 기동 오류로 처리한다.
인터페이스 번호나 DNS 등록 순서에 의존하지 않는다.

Eureka는 `tasks.platform_eureka.platform_default`로 피어를 찾고, 응답 중 지정 CIDR의 주소만 사용한다.
선택한 자기 주소는 복제 대상에서 제외한다. 다른 네트워크의 주소는 피어에 추가하지 않는다.
같은 주소를 Eureka 서버 자신의 `ipAddr`·`hostName` 및 상태·헬스체크 URL에도 적용한다.
설정 바인딩 후 등록 정보 생성 전에 적용하여 자동 감지한 다른 네트워크 주소가 등록되지 않게 한다.
DNS가 아직 없거나 해당 네트워크의 자기 주소가 응답에 없으면 이전 피어 목록을 유지한다.
최초 기동은 피어 없이 시작하고 기존 loopback URL로 부트스트랩하므로 healthy 이후 DNS 등록을 기다리며 기동이 막히지 않는다.
다른 애플리케이션의 Eureka 접속 URL·자기 주소 등록 설정은 변경하지 않는다.

## 반영

Gateway는 기존 `interfaces`를 제거하고 `network-cidr`만 사용한다.
Gateway·Eureka 앱 코드와 중앙 CIDR 설정을 함께 반영한다.
네트워크 subnet 변경 시 해당 CIDR 설정을 함께 변경해야 한다. 이번 변경에서는 네트워크 재생성이 필요 없다.

검증: `./gradlew :admin:test :gateway:test :eureka:test spotlessCheck`
