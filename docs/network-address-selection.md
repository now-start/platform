# Hazelcast·Eureka 서버 네트워크 설정

플랫폼·앱·Grafana는 MTU 1200의 공통 platform_default overlay를 사용한다. 고정 subnet과 CIDR 설정은 사용하지 않는다.
Docker API 접근이나 별도 공통 라이브러리는 추가하지 않는다.

| 대상 | 멤버 DNS |
| --- | --- |
| Admin Hazelcast | tasks.platform_admin |
| Gateway Hazelcast | tasks.platform_gateway |
| Barosteel Hazelcast | tasks.barosteel_barosteel |
| Eureka 서버 피어 | tasks.platform_eureka |

## Hazelcast

app.cluster.name은 spring.application.name을 기본값으로 사용한다. members는 필수이며 로컬 실행에서는 127.0.0.1을 지정한다.
기본 포트는 5701이고 자동 증가·자동 탐지·multicast를 끈다. TCP 멤버 탐색을 사용한다.
운영은 app.cluster.local-address=${HOSTNAME}으로 Docker가 /etc/hosts에 등록한 공통 overlay 주소를 사용한다.
실제 Swarm에서 healthy 이전 task DNS가 비어 있으면 Hazelcast 기본 선택이 docker_gwbridge 주소를 광고하는 문제가 확인돼 hostname을 명시한다. CIDR 파싱과 인터페이스 열거는 사용하지 않는다.
로컬 실행에서는 이 설정을 생략해 기존 TCP/IP 기본 주소 선택을 사용할 수 있다.
overlay를 하나로 통합해도 ingress와 docker_gwbridge 등의 인터페이스가 있을 수 있다.

종료 대기는 90초이며 동기 백업 1개·비동기 백업 0개를 유지한다.
Admin·Gateway는 GRACEFUL 종료 훅을 사용한다. Barosteel은 HTTP graceful shutdown 이후 Spring이 인스턴스를 종료한다.
세션 직렬화·이벤트 저장소·캐시 TTL·병합 정책은 서비스별 기존 동작을 유지한다.

## Eureka

task DNS의 IPv4 응답과 로컬 IP의 교집합이 정확히 한 개일 때 자기 주소로 선택한다.
초기 탐색은 최대 10회, 시도 사이 1초 대기한다. DNS 조회 시간은 별도다.
Swarm은 healthy 이전 task를 DNS에 싣지 않으므로 실패 시 Docker 자체 hostname의 IPv4와 로컬 IP의 교집합 한 개로 부트스트랩한다. loopback·모호한 주소는 거부한다. healthy 이후 task DNS로 피어를 갱신한다. 이 방식은 Eureka가 공통 overlay 하나에만 연결되는 구성을 전제로 한다.
다중 인터페이스와 DNS 지연을 고려하며, 최초 task DNS 등록 시점은 실제 Swarm에서 검증한다.
선택한 자기 주소는 복제 대상에서 제외하고 서버 ipAddr·hostName·상태·헬스체크 URL에 적용한다.
기동 후 DNS 실패·빈 응답·자기 주소 누락에는 이전 피어 목록을 유지한다.
자기 주소만 응답하면 정상 단일 인스턴스로 취급해 이전 피어를 제거하고 loopback URL로 부트스트랩한다.
10초 주기 피어 갱신, 기존 연결 재사용 및 제거된 피어 종료 동작을 유지한다.

## 반영과 검증

앱 코드·중앙 설정·GitOps Compose를 함께 준비한다. 기존 앱이 CIDR 설정을 요구하므로 설정만 먼저 제거하지 않는다.
새 이미지 발행 후 배포 참조를 갱신한다. 기존 네트워크는 IPAM 선언 삭제만으로 대역이 바뀌지 않으며 자동 할당으로 전환하려면 재생성이 필요하다.
서비스 중단을 포함한 적용·롤백 순서는 gitops의 docs/shared-overlay-migration.md를 따른다.
다른 앱의 Eureka 접속 URL·자기 등록 설정은 변경하지 않는다.

검증: `./gradlew :admin:test :gateway:test :eureka:test spotlessCheck`
운영에서는 Hazelcast 합류·세션 공유, Eureka 교체·증감, Config 접근·LGTM 수신을 별도로 확인한다.
