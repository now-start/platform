# Admin 이중화

각 Admin JVM에 Hazelcast 멤버 하나를 내장한다. Spring Boot Admin의 HazelcastEventStore와 이벤트 Map의 OBJECT 형식은 유지한다.

Config Server의 `app.cluster.name`, `app.cluster.members`를 사용한다. 운영 값은 `admin`, `tasks.platform_admin`이다. 이름의 기본값은 `spring.application.name`이며, 로컬 실행에서는 members=127.0.0.1을 지정한다. 운영의 app.cluster.local-address는 Docker HOSTNAME을 사용해 공통 overlay 주소로 바인딩한다. 로컬에서는 생략 가능하다.

TCP 포트는 5701로 고정하며 `app.cluster.port`로 로컬 다중 인스턴스 테스트에서만 변경한다. 멀티캐스트와 자동 발견은 끈다. 동기 백업 1개, 비동기 백업 0개, PutIfAbsentMergePolicy를 사용한다. 단독 멤버와 일시적 분리를 허용하고 네트워크 복구 후 Hazelcast 기본 탐색·병합으로 다시 합류한다. 병합은 즉시 완료되거나 최신 값을 보장하지 않는다. 모든 멤버가 종료되면 메모리 데이터는 사라진다.

Swarm global 배치와 stop-first 순차 배포는 유지한다. 정상 종료의 Hazelcast 대기는 최대 90초, 컨테이너 종료 유예는 120초이다. liveness 성공은 클러스터 합류의 증거가 아니므로 실제 멤버 목록도 확인한다. 플랫폼과 앱은 공통 `platform_default` overlay 하나를 사용한다.

Config Server는 Git의 `app.cluster` 설정을 제공한다. 구버전 `admin.cluster.*` 설정은 지원하지 않으며, 롤백 시 앱과 중앙 설정, Compose의 네트워크 구성을 함께 복구한다. 설정 제공에 Config Server 이미지 재배포는 필요하지 않다.
