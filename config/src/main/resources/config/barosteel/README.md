# Barosteel 통합 실행

기본 프로필로 업무를 실행한다. 기존 DB 암호화 값과 Eureka·Actuator 설정은 유지한다.
`barosteel.session.shared=true`로 내장 Hazelcast 세션과 Spring Cache를 활성화한다. 로그인 제한은 DB를 사용한다.

`app.cluster`는 이름, 태스크 DNS와 로컬 overlay CIDR을 지정한다. `app.cache`는 cache.* Map의 TTL과 멤버당 항목 수를 지정한다. 캐시 TTL은 세션 Map에 적용하지 않는다. Hazelcast는 단독 멤버와 일시적 분리를 허용하며 복구 후 자동 병합한다.

Config Server는 이 저장소에서 설정을 읽는다. 새 설정 제공을 확인한 뒤 앱 이미지를 갱신한다. 구버전 롤링 배포와 롤백을 위해 barosteel.cluster.members를 유지하며 구버전 지원 종료 후 제거한다. 운영 subnet 변경 시 GitOps IPAM과 app.cluster.network-cidr를 함께 변경한다.
