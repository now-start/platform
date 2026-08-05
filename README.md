# Platform

Spring Cloud 기반 플랫폼 모노레포입니다. 기존 독립 저장소였던 `admin-service`, `config-service`, `eureka-service`, `gateway-service`를 Gradle 멀티모듈 프로젝트로 통합했습니다.

## 모듈

| 모듈 | 역할 | 기본 포트 | 현재 버전 |
| --- | --- | ---: | --- |
| `config` | Spring Cloud Config Server | `8888` | `2.1.13` |
| `eureka` | Eureka Server | `8761` | `2.4.9` |
| `admin` | Spring Boot Admin Server | `9090` | `2.11.7` |
| `gateway` | Spring Cloud Gateway | `8000` | `6.1.0` |

## 요구 사항

- Java 25
- Gradle Wrapper 사용
- Docker 또는 Docker Compose
- Gateway 인스턴스들이 함께 사용하는 Redis

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
./gradlew :gateway:bootBuildImage --imageName=ghcr.io/now-start/gateway:6.1.0
```

## 실행

로컬 컨테이너 실행:

```bash
cp .env.example .env
# .env의 ENCRYPT_KEY 설정
docker compose up -d
```

Gateway Basic 비밀번호는 Config Server의 `{cipher}` 값으로 관리하며, 복호화 결과는 `{bcrypt}` prefix가 포함된 BCrypt 해시입니다. 원문 비밀번호와 Synology OAuth client secret은 사용하지 않습니다.

`config`가 먼저 올라오고, `eureka`, `admin`, `gateway`는 `SPRING_CONFIG_IMPORT=optional:configserver:http://config:8888` 설정으로 Config Server를 참조합니다. 외부로 publish되는 포트는 `gateway`의 `8000`뿐이며, `config`, `eureka`, `admin`은 Docker 내부 네트워크에서만 접근합니다.

Gateway의 WebFlux 세션은 Spring Session을 통해 Redis에 저장됩니다. Redis host와 password는 Config Server의 `gateway.yaml`에서 `{cipher}` 값으로 관리하며, port는 `6379`를 사용합니다. 모든 Gateway 인스턴스는 같은 Redis 설정을 사용해야 합니다. 세션 만료 시간은 30분이며, Redis 키 namespace는 `nowstart:gateway:session`입니다.

현재 구성은 외부 standalone Redis를 사용하므로 `compose.yaml`에 Redis 서비스를 추가하지 않습니다. Redis는 Gateway 전용 계정/키 권한으로 접근 가능한 사설망에 배치해야 합니다. Redis가 중단되면 로그인과 기존 세션 사용도 영향을 받습니다. Redis 세션 통합 테스트는 Testcontainers를 사용하므로 테스트 실행 시 Docker가 필요합니다.

## 설정

서비스 공통/개별 설정은 `config/src/main/resources/config/**` 아래에서 관리합니다.

- `config/common/application.yaml`: 공통 설정
- `config/config/{service}/{service}.yaml`: 서비스별 설정
- `config/common/test.yaml`: 테스트용 공통 설정

## CI/CD

`.github/workflows/build.yaml`은 `push`와 `pull_request`에서 4개 모듈을 모두 reusable workflow에 전달합니다. 실제 이미지 생성 여부는 reusable workflow가 각 모듈의 `version`과 Git tag 존재 여부로 결정합니다.

- `{module}-{version}` tag가 없으면 빌드, 이미지 푸시, 릴리스 생성
- `{module}-{version}` tag가 이미 있으면 해당 모듈 빌드/이미지 푸시 생략
- 새 이미지를 만들려면 변경 영향에 맞게 해당 모듈의 `build.gradle` version을 올립니다.
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
config-2.1.13
eureka-2.4.9
admin-2.11.7
gateway-6.1.0
```
