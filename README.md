# Platform

Spring Cloud 기반 플랫폼 모노레포입니다. 기존 독립 저장소였던 `admin-service`, `config-service`, `eureka-service`, `gateway-service`를 Gradle 멀티모듈 프로젝트로 통합했습니다.

## 모듈

| 모듈 | 역할 | 기본 포트 | 현재 버전 |
| --- | --- | ---: | --- |
| `config` | Spring Cloud Config Server | `8888` | `2.1.9` |
| `eureka` | Eureka Server | `8761` | `2.4.7` |
| `admin` | Spring Boot Admin Server | `9090` | `2.11.5` |
| `gateway` | Spring Cloud Gateway | `8000` | `5.1.0` |

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
./gradlew :gateway:bootBuildImage --imageName=ghcr.io/now-start/gateway:5.1.0
```

## 실행

로컬 컨테이너 실행:

```bash
docker compose up -d
```

`config`가 먼저 올라오고, `eureka`, `admin`, `gateway`는 `SPRING_CONFIG_IMPORT=optional:configserver:http://config:8888` 설정으로 Config Server를 참조합니다. 외부로 publish되는 포트는 `gateway`의 `8000`뿐이며, `config`, `eureka`, `admin`은 Docker 내부 네트워크에서만 접근합니다.

## 설정

서비스 공통/개별 설정은 `config/src/main/resources/config/**` 아래에서 관리합니다.

- `config/common/application.yaml`: 공통 설정
- `config/config/{service}/{service}.yaml`: 서비스별 설정
- `config/common/test.yaml`: 테스트용 공통 설정

## CI/CD

`.github/workflows/build.yaml`은 `push`와 `pull_request`에서 4개 모듈을 모두 reusable workflow에 전달합니다. 실제 이미지 생성 여부는 reusable workflow가 각 모듈의 `version`과 Git tag 존재 여부로 결정합니다.

- `{module}-{version}` tag가 없으면 빌드, 이미지 푸시, 릴리스 생성
- `{module}-{version}` tag가 이미 있으면 해당 모듈 빌드/이미지 푸시 생략
- 새 이미지를 만들려면 해당 모듈의 `build.gradle` patch version을 올립니다.
- `release` 이벤트도 4개 모듈을 reusable workflow에 전달하며, reusable workflow가 release tag의 module prefix를 확인해 해당 모듈만 프로모트/롤백합니다.

이미지는 모듈명을 기준으로 생성됩니다.

```text
ghcr.io/now-start/config:{version}
ghcr.io/now-start/eureka:{version}
ghcr.io/now-start/admin:{version}
ghcr.io/now-start/gateway:{version}
```

각 모듈의 `build.gradle`에 있는 `version`이 이미지 태그와 릴리스 태그에 사용됩니다. 루트 `build.gradle`이나 공통 설정을 변경했더라도 모듈 version을 올리지 않으면 기존 tag 기준으로 skip될 수 있습니다.

릴리스 이벤트는 `{module}-{version}` 태그 prefix로 모듈을 구분합니다.

```text
config-2.1.9
eureka-2.4.7
admin-2.11.5
gateway-5.1.0
```
