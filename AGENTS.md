# Repository Guidelines

## Project Structure & Module Organization

```
X-NLP/
├── pom.xml                   # Parent POM (Spring Boot 4.1.0, Java 21)
├── Dockerfile                # eclipse-temurin:17-jre-alpine
├── configs/                  # Environment-specific YAML overrides
├── docker/                   # Docker Compose & K8s manifests
├── tests/                    # Integration / E2E scripts
│
├── xnlp-core/                # Shared domain model & SPI
│   ├── model/                # PredictRequest, PredictResponse, ModelInfo, ...
│   ├── errors/               # XNLPException hierarchy (6 errors)
│   ├── config/               # AppConfig, ModelConfig, ServerConfig
│   ├── engine/               # InferenceEngine SPI (ServiceLoader)
│   ├── pipeline/             # ProcessingPipeline, PipelineManager
│   └── registry/             # ModelRegistry (in-memory)
│
├── xnlp-client/              # Java SDK -- HTTP client wrapping all REST APIs
│   └── XNLPClient            # health, listModels, loadModel, predict, benchmark
│
├── xnlp-server/              # Spring Boot server
│   ├── controller/           # ModelController, HealthController, BenchmarkController
│   ├── service/              # InferenceService, ModelService, MetricsService, ...
│   ├── backend/              # SimpleONNXBackend, SimpleDJLBackend
│   ├── config/               # TracingConfiguration, GlobalExceptionHandler, ...
│   ├── startup/              # ModelInitializer
│   └── src/test/             # Smoke tests (JUnit 5 + AssertJ)
│
└── xnlp-cli/                 # Picocli CLI -- delegates all logic to xnlp-client
    └── XNLPCli               # health, list, load, unload, predict subcommands
```

**Dependency graph:** `xnlp-cli` -> `xnlp-client` -> `xnlp-core` <- `xnlp-server`

## Build, Test, and Development Commands

| Command | Purpose |
|---|---|
| `mvn clean verify` | Full build: compile + test + package all 4 modules |
| `mvn test` | Run all tests (32 tests, JUnit 5 + AssertJ) |
| `mvn test -pl xnlp-server` | Run server-layer tests only |
| `mvn spring-boot:run -pl xnlp-server` | Start server on port 8760 |
| `java -jar xnlp-cli/target/xnlp-cli-*.jar` | Run CLI (pass `-s` to set server URL) |
| `docker build -t xnlp:latest .` | Build Docker image |

Server exposes Actuator at `/actuator` (health, metrics, prometheus) and K8s probes at `/livez`, `/readyz`, `/startupz`. Swagger UI at `/swagger-ui.html`.

## Coding Style & Naming Conventions

- **Java 21**, no preview features. 4-space indentation.
- **Packages**: `com.xnlp.<module>.<layer>` -- e.g. `com.xnlp.server.controller`
- **Config classes**: `@Configuration`-annotated; properties via `@ConfigurationProperties("xnlp")`
- **Beans**: Constructor injection only (no `@Autowired` fields). Use `private final` + single-constructor convention.
- **Dependency versions**: managed centrally in parent POM's `<dependencyManagement>`.
- No spotless/checkstyle currently configured.

## Testing Guidelines

- **Framework**: JUnit 5 with AssertJ assertions. Spring Boot Test with `RANDOM_PORT` for integration.
- **Test naming**: `classUnderTest_MethodUnderTest_ExpectedBehavior` for unit tests; descriptive `@DisplayName` for integration tests.
- **Coverage**: All `xnlp-core` public APIs must be tested (model, registry, pipeline, errors). Server smoke tests cover every REST endpoint.
- Run before submitting: `mvn verify` must pass across all modules.

## Commit & Pull Request Guidelines

Follow [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` -- new feature or endpoint
- `fix:` -- bug fix
- `refactor:` -- restructuring without behavior change
- `docs:` -- documentation only
- `chore:` -- build, CI, dependency updates

Example: `feat: add batch prediction endpoint with async support`

**PRs**: link the related issue, include a brief **Why / What / How** summary, and attach a curl snippet for API changes. Keep PRs single-purpose.

## Observability

The three pillars are wired into `xnlp-server`:

| Pillar | Tech | Config |
|---|---|---|
| **Metrics** | Micrometer -> Prometheus | `GET /actuator/prometheus` |
| **Tracing** | Micrometer Tracing -> OTel OTLP | `management.otlp.tracing.endpoint` |
| **Logging** | Logback + logstash-encoder | Dev: human-readable; Prod: JSON + size/time rotation |

Log path, max size, and retention are driven by env vars: `LOG_PATH`, `LOG_MAX_SIZE`, `LOG_TOTAL_CAP`.
