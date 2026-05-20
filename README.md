# Platform

Spring Cloud 기반 플랫폼 모노레포입니다. 기존 독립 저장소였던 `admin-service`, `config-service`, `eureka-service`, `gateway-service`를 Gradle 멀티모듈 프로젝트로 통합했습니다.

## 모듈

| 모듈 | 역할 | 기본 포트 | 현재 버전 |
| --- | --- | ---: | --- |
| `config` | Spring Cloud Config Server | `8888` | `2.1.7` |
| `eureka` | Eureka Server | `8761` | `2.4.6` |
| `admin` | Spring Boot Admin Server | `9090` | `2.11.4` |
| `gateway` | Spring Cloud Gateway | `8000` | `4.8.1` |

## 요구 사항

- Java 25
- Gradle Wrapper 사용
- Docker 또는 Docker Compose

## 빌드와 테스트

전체 테스트:

```bash
./gradlew test --no-daemon
```

특정 모듈 빌드:

```bash
./gradlew :config:build
./gradlew :eureka:build
./gradlew :admin:build
./gradlew :gateway:build
```

특정 모듈 이미지 빌드:

```bash
./gradlew :gateway:bootBuildImage --imageName=ghcr.io/now-start/gateway:4.8.1
```

## 실행

로컬 컨테이너 실행:

```bash
docker compose up -d
```

`config`가 먼저 올라오고, `eureka`, `admin`, `gateway`는 `SPRING_CONFIG_IMPORT=optional:configserver:http://config:8888` 설정으로 Config Server를 참조합니다.

## 설정

서비스 공통/개별 설정은 `config/src/main/resources/config/**` 아래에서 관리합니다.

- `config/common/application.yaml`: 공통 설정
- `config/config/{service}/{service}.yaml`: 서비스별 설정
- `config/common/test.yaml`: 테스트용 공통 설정

Config 설정 변경이 `main`에 push되면 `.github/workflows/config-refresh.yaml`이 Config Server의 `/actuator/busrefresh`를 호출합니다.

## CI/CD

`.github/workflows/build.yaml`은 변경된 모듈만 감지해서 reusable workflow를 호출합니다.

- `config/**` 변경 -> `config` 이미지
- `eureka/**` 변경 -> `eureka` 이미지
- `admin/**` 변경 -> `admin` 이미지
- `gateway/**` 변경 -> `gateway` 이미지
- `build.gradle`, `settings.gradle`, `gradle/**` 변경 -> 전체 모듈 대상

이미지는 모듈명을 기준으로 생성됩니다.

```text
ghcr.io/now-start/config:{version}
ghcr.io/now-start/eureka:{version}
ghcr.io/now-start/admin:{version}
ghcr.io/now-start/gateway:{version}
```

각 모듈의 `build.gradle`에 있는 `version`이 이미지 태그와 릴리스 태그에 사용됩니다. 같은 버전 태그가 이미 존재하면 reusable workflow의 skip 로직에 따라 빌드/푸시가 생략될 수 있습니다.

릴리스 이벤트는 `{module}-{version}` 태그 prefix로 모듈을 구분합니다.

```text
config-2.1.7
eureka-2.4.6
admin-2.11.4
gateway-4.8.1
```

