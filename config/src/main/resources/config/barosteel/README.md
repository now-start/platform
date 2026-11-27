# Barosteel 통합 실행

기본 프로필로 업무를 실행한다. 기존 DB 암호화 값과 Eureka·Actuator 설정은 유지한다.
애플리케이션 기본 시간대와 JDBC는 UTC를 사용하고, Hikari가 새 DB 연결마다 `SET TIME ZONE 'UTC'`를 실행한다. 서울 기준의 업무 날짜와 화면 표시는 앱에서 `Asia/Seoul`을 명시한다. 기존 `timestamptz` 데이터는 변경하지 않는다.
`app.session.shared=true`로 내장 Hazelcast 세션과 Spring Cache를 활성화한다. 로그인 제한은 DB를 사용한다.

`app.cluster`는 클러스터 이름과 멤버 탐색용 태스크 DNS를 지정하고, 로컬 바인딩 주소는 컨테이너의 `HOSTNAME`을 사용한다. `app.cache`는 cache.* Map의 TTL과 멤버당 항목 수를 지정한다. 캐시 TTL은 세션 Map에 적용하지 않는다. Hazelcast는 단독 멤버와 일시적 분리를 허용하며 복구 후 자동 병합한다.

Config Server는 이 저장소에서 설정을 읽는다. 클러스터 설정은 `app.cluster`만 사용하며 구버전 `barosteel.cluster.members`는 지원하지 않는다. 롤백도 `app.cluster`를 사용하는 버전으로 제한한다. `tasks.barosteel_barosteel`로 공통 `platform_default` overlay의 멤버를 탐색한다. CIDR을 요구하는 구버전으로 롤백할 때는 이전 설정과 Compose도 함께 복구한다.
