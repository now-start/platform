# Admin 이중화

Spring Boot Admin의 기본 Hazelcast 연동을 사용한다. Hazelcast는 각 Admin JVM에 내장되며 별도 서버를 배포하지 않는다. Gateway의 Redis 세션은 변경하지 않는다.

- `spring.application.name`을 클러스터 이름으로 사용한다.
- 기본 멤버 발견 주소는 `tasks.${spring.application.name}`이며 운영에서는 `tasks.admin`이다. 로컬 테스트에서만 `admin.cluster.members`로 대체한다.
- Swarm에서는 multicast 대신 기본 TCP 탐색을 사용한다. 멤버 주소는 `tasks.admin`의 DNS 결과와 컨테이너의 로컬 IPv4 주소가 일치하는 것으로 선택한다. 자기 주소가 DNS에 없으면 로컬 인터페이스의 실제 prefix를 사용해 다른 멤버와 같은 서브넷의 IPv4를 선택한다. 후보가 없거나 여러 개면 DNS·로컬 주소를 오류에 남기고 기동을 실패시킨다. DNS 조회 자체가 실패한 경우도 기동을 실패시킨다.
- 고정 IP 대역과 `interfaces` 필터는 사용하지 않는다. 선택한 로컬 주소만 `hazelcast.local.localAddress`에 적용하므로 다른 멤버의 DNS 주소는 탐색에서 제외하지 않는다. `admin.cluster.members`에는 포트 없는 DNS 이름 또는 IP를 지정하며, 로컬 실행에서는 `localhost`로 대체한다.
- TCP 5701은 호스트에 게시하지 않는다. 플랫폼·Grafana 내부 overlay 네트워크의 컨테이너를 신뢰하는 구성이다. 클러스터 이름은 인증 수단이 아니며 같은 내부망에서는 Hazelcast에 접근할 수 있다.
- 포트, 동기 백업 1개, 병합 정책은 Hazelcast 기본값을 사용한다. 이벤트 Map만 Spring Boot Admin 권장 OBJECT 형식으로 설정한다.
- Admin은 Swarm global 모드로 노드당 하나씩 배치하고 한 번에 하나씩 교체한다. 모든 멤버가 종료되면 메모리 이력은 사라진다.
- 각 Admin이 모니터링 대상을 조회하므로 상태 확인 요청량은 늘어난다.

`./gradlew :admin:test`에서 로컬 호스트 이름(`localhost`)을 통한 두 멤버 연결, 이벤트 공유와 한 멤버 강제 종료 후 이력 조회, 실제 Admin의 HazelcastEventStore 자동 구성을 확인한다.

운영에서는 노드별 태스크 배치, 로그의 동일한 전체 멤버 클러스터, Gateway를 통한 Admin 접근을 확인한다. UP만으로 클러스터 성공을 판단하지 않는다.

관련 작업: https://github.com/now-start/barosteel/issues/70, https://github.com/now-start/barosteel/issues/72
