# Barosteel 통합 실행

프로필 미지정(default)으로 모든 업무를 실행한다. 기존 DB 암호화 값과 공통 Eureka·Actuator 설정을 유지한다.
`barosteel.session.shared: true`로 동일 DB의 JDBC 세션과 로그인 제한을 공유한다.
모든 인스턴스의 서비스 이름은 `barosteel`이다. 업무별 URL·내부 토큰·분리 프로필은 사용하지 않는다.
설정 저장소 반영과 호환 이미지 발행 후 통합 서비스 replicas를 늘린다. 로컬 변경은 서버 반영이 아니다.
