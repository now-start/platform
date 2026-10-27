# Platform

Spring Cloud 기반 플랫폼 모노레포입니다. 기존 독립 저장소였던 `admin-service`, `config-service`, `eureka-service`, `gateway-service`를 Gradle 멀티모듈 프로젝트로 통합했습니다.

## 모듈

| 모듈 | 역할 | 기본 포트 | 현재 버전 |
| --- | --- | ---: | --- |
| `config` | Spring Cloud Config Server | `8888` | `2.1.24` |
| `eureka` | Eureka Server | `8761` | `2.5.5` |
| `admin` | Spring Boot Admin Server | `9090` | `2.11.21` |
| `gateway` | Spring Cloud Gateway | `8000` | `6.2.2` |

## 요구 사항

Hazelcast와 Eureka 서버의 다중 네트워크 주소 선택은 [네트워크 설정](docs/network-address-selection.md)을 참고한다.

- Java 25
- Kotlin 2.4.21 (운영 코드·테스트 및 Gradle Kotlin DSL)
- Gradle Wrapper 사용
- Docker 또는 Docker Compose
- Gateway 간 Hazelcast 내부 통신 (기본 TCP 5701)

## 빌드와 테스트

코드와 Gradle 스크립트는 Spotless의 ktfmt Kotlin 스타일을 사용합니다.
각 모듈의 `check`와 `build`에서도 포맷을 검사합니다.

```bash
./gradlew spotlessApply  # 포맷 적용
./gradlew spotlessCheck  # 포맷 검사
```

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
./gradlew :gateway:bootBuildImage --imageName=ghcr.io/now-start/gateway:6.1.3
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

Gateway의 WebFlux 세션과 감사 기록은 Gateway 전용 임베디드 Hazelcast 클러스터로 공유합니다.
세션 만료는 기본 30분, 감사 기록 TTL은 30일이며, 두 맵은 동기 백업 1개를 사용합니다.
전체 Gateway 종료 시 데이터가 초기화됩니다. Redis에서 이관하지 않으므로 전환 시 재로그인이 필요합니다.
Config Server의 `app.cluster.*`에 실제 task DNS와 네트워크 인터페이스를 설정합니다.
세션·감사·URL별 RBAC 및 신뢰 프록시 IP 처리의 상세 설정은 [Gateway 문서](gateway/README.md)를 참고합니다.
통합 테스트는 임베디드 Hazelcast를 사용하며 Docker가 필요하지 않습니다.

## 설정

서비스 공통/개별 설정은 `config/src/main/resources/config/**` 아래에서 관리합니다.

- `config/common/application.yaml`: 공통 설정
- `config/config/{service}/{service}.yaml`: 서비스별 설정
- `config/common/test.yaml`: 테스트용 공통 설정

## CI/CD

`.github/workflows/build.yaml`은 `push`와 `pull_request`에서 4개 모듈을 모두 reusable workflow에 전달합니다. 실제 이미지 생성 여부는 reusable workflow가 각 모듈의 `version`과 Git tag 존재 여부로 결정합니다.

- `{module}-{version}` tag가 없으면 빌드, 이미지 푸시, 릴리스 생성
- `{module}-{version}` tag가 이미 있으면 해당 모듈 빌드/이미지 푸시 생략
- 새 이미지를 만들려면 변경 영향에 맞게 해당 모듈의 `build.gradle.kts` version을 올립니다.
- `release` 이벤트도 4개 모듈을 reusable workflow에 전달하며, reusable workflow가 release tag의 module prefix를 확인해 해당 모듈만 프로모트/롤백합니다.

이미지는 모듈명을 기준으로 생성됩니다.

```text
ghcr.io/now-start/config:{version}
ghcr.io/now-start/eureka:{version}
ghcr.io/now-start/admin:{version}
ghcr.io/now-start/gateway:{version}
```

각 모듈의 `build.gradle.kts`에 있는 `version`이 이미지 태그와 릴리스 태그에 사용됩니다. 루트 `build.gradle.kts`이나 공통 설정을 변경했더라도 모듈 version을 올리지 않으면 기존 tag 기준으로 skip될 수 있습니다.

릴리스 이벤트는 `{module}-{version}` 태그 prefix로 모듈을 구분합니다.

```text
config-2.1.17
eureka-2.4.11
admin-2.11.9
gateway-6.1.3
```

## Eureka Swarm peer discovery

`config/src/main/resources/config/eureka/eureka.yaml` enables Swarm discovery and
sets the task DNS name and refresh intervals. GitOps only selects the image and
global scheduling; no Eureka-specific environment variables are required.

The resolver queries Docker DNS A records directly without the JVM address cache,
excludes all local interface addresses, and keeps the last successful peer list
during DNS failures. A successful answer containing only this task clears the
peer list. Discovery is off unless `eureka.swarm.enabled=true`; when using this
Config Server outside Swarm, override it to `false` in the local configuration.

Both replication and Eureka's existing bootstrap registry fetch use direct task
URLs. A singleton uses loopback for the client and has no replication peers.
The existing Eureka bootstrap retry and heartbeat recovery mechanisms remain in
use; DNS membership is not a readiness or complete-registry guarantee. Runtime
DNS requires the JDK's `jdk.naming.dns` module (included in the normal JRE).

Clients continue using `http://eureka:8761/eureka/` on the shared overlay. No node
IP list, host port, additional dependency, or image health-checker is required.
Publish the new Eureka image and Config Server settings before deploying its
GitOps tag and global mode. Config Server reads these settings from this Git
repository, so this configuration-only change does not require a new Config image.

Run `./gradlew :eureka:test` and validate node join, removal, task IP replacement,
and registry replication on a real Swarm before relying on this for availability.

### Admin Swarm bootstrap

Admin은 `app.cluster.name`, `app.cluster.members`, `app.cluster.network-cidr`를 사용한다. 지정한 CIDR에 속한 로컬 IPv4를 선택하므로 동료 태스크 DNS가 아직 비어 있어도 시작할 수 있다. 구버전 `admin.cluster.*`와 DNS 기반 bind-host 설정은 제거했다. 자세한 기준은 [Admin 운영 안내](admin/README.md)를 따른다.
