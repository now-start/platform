# Admin 이중화

각 Admin JVM에 Hazelcast 멤버 하나를 내장한다. Spring Boot Admin의 HazelcastEventStore와 이벤트 Map의 OBJECT 형식은 유지한다.

Config Server의 `app.cluster.name`, `app.cluster.members`, `app.cluster.network-cidr`를 사용한다. 운영 값은 각각 `admin`, `tasks.platform_admin.platform_default`, `10.0.11.0/24`이다. CIDR에 속하는 로컬 IPv4 하나를 선택하며 동료 DNS가 비어 있어도 시작할 수 있다. 잘못된 CIDR이나 모호한 로컬 주소는 기동 오류로 처리한다. 로컬 실행에서는 members=127.0.0.1, network-cidr=127.0.0.0/8을 지정한다.

TCP 포트는 5701로 고정하며 `app.cluster.port`로 로컬 다중 인스턴스 테스트에서만 변경한다. 멀티캐스트와 자동 발견은 끈다. 동기 백업 1개, 비동기 백업 0개, PutIfAbsentMergePolicy를 사용한다. 단독 멤버와 일시적 분리를 허용하고 네트워크 복구 후 Hazelcast 기본 탐색·병합으로 다시 합류한다. 병합은 즉시 완료되거나 최신 값을 보장하지 않는다. 모든 멤버가 종료되면 메모리 데이터는 사라진다.

Swarm global 배치와 stop-first 순차 배포는 유지한다. 정상 종료의 Hazelcast 대기는 최대 90초, 컨테이너 종료 유예는 120초이다. liveness 성공은 클러스터 합류의 증거가 아니므로 실제 멤버 목록도 확인한다. 네트워크 subnet을 변경할 때는 Compose IPAM과 Config Server CIDR을 함께 갱신한다.

Config Server는 Git의 `app.cluster` 설정을 제공한다. 구버전 `admin.cluster.*` 설정은 지원하지 않으며, 롤백 대상도 `app.cluster`를 사용하는 Admin 2.11.17 이상으로 선택한다. 설정 제공에 Config Server 이미지 재배포는 필요하지 않다.
