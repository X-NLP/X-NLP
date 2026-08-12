# X-NLP Architecture

> Version: 0.2.0 | Date: 2026-07-23
>
> This document describes the refactored architecture of X-NLP, grounded in
> patterns from HanLP's component model, Haystack's pipeline DAG,
> Spring Data's repository abstraction, and Alibaba COLA's layered architecture.

## 1. Reference Projects & Adopted Patterns

| Project | Pattern Adopted | X-NLP Mapping |
|---------|----------------|---------------|
| **HanLP** | Capability-as-component: each NLP unit (tokenizer, tagger, parser) is self-contained with a uniform SPI | `NlpComponent` interface + `CapabilityRegistry` |
| **Haystack** | Pipeline nodes as DAG: components connected by upstream/downstream dependencies | `PipelineNode` interface |
| **Spring Data** | Repository pattern decoupling storage from business logic | `ModelConfigRepository`, `DatasetRepository`, `EvaluationRunRepository` |
| **Apache Flink** | Runtime abstraction separating execution from task definition | `NlpContext` (future: `NlpRuntime` SPI) |
| **COLA (Alibaba)** | Layered architecture: API, Domain, Infrastructure | Module separation: `xnlp-core` (domain) / `xnlp-server` (infrastructure) |

## 2. Module Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     xnlp-core                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐            │
│  │  api/    │ │  config/ │ │  repository/ │            │
│  │ Component│ │          │ │  Interfaces  │            │
│  │ Pipeline │ │  model/  │ │  (SPI)       │            │
│  │ Context  │ │          │ │              │            │
│  └──────────┘ └──────────┘ └──────────────┘            │
│  ┌──────────────┐ ┌──────────┐ ┌────────────┐          │
│  │  pipeline/   │ │  eval/   │ │  errors/   │          │
│  │              │ │          │ │            │          │
│  └──────────────┘ └──────────┘ └────────────┘          │
└─────────────────────────────────────────────────────────┘
                          ▲
                          │  depends on
┌─────────────────────────────────────────────────────────┐
│                    xnlp-server                           │
│  ┌──────────────┐ ┌──────────┐ ┌──────────────────┐    │
│  │ controller/  │ │  nlp/    │ │  component/impl/ │    │
│  │ REST API     │ │ Registry │ │  Tokenizer, POS, │    │
│  │              │ │          │ │  NER, Parser...  │    │
│  └──────────────┘ └──────────┘ └──────────────────┘    │
│  ┌──────────────┐ ┌────────────────────────────────┐    │
│  │  service/    │ │  repository/impl/              │    │
│  │ Orchestration│ │ Spring JDBC + profile adapters │    │
│  └──────────────┘ └────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘

┌──────────┐     ┌──────────┐
│ xnlp-cli │ ──▶ │xnlp-client│ ──HTTP──▶ xnlp-server
└──────────┘     └──────────┘
```

## 3. Core SPI Layer (`xnlp-core/api/`)

### 3.1 NlpComponent

The fundamental building block. Every NLP capability is a component:

```java
public interface NlpComponent {
    String id();           // TOK, POS, NER, DEP, ...
    String displayName();  // "分词", "词性标注", ...
    String description();
    Map<String, String> parameterSchema();
    ComponentResult execute(NlpContext context);
}
```

**Why this pattern**: HanLP's component model allows the framework to
discover, register, and dispatch to components uniformly. Adding a new NLP
capability requires only implementing this interface + registering with the
`CapabilityRegistry`. No changes to orchestration code.

### 3.2 NlpContext

Immutable execution context carrying input text, pair text, language hint,
and task parameters. Ensures components are stateless and testable.

### 3.3 ComponentResult

Standardized result envelope: `{componentId, data: Map<String, Object>}`.
The `data` map shape is component-specific but always key-value structured,
enabling the frontend to render results generically.

### 3.4 PipelineNode

Models NLP processing as a DAG:

```java
public interface PipelineNode {
    String nodeId();
    NlpComponent component();
    List<String> upstream();  // dependencies
    boolean enabled();
}
```

Enables pipelines like:
```
[Tokenizer] → [POS Tagger] → [NER] → [Merge]
[Tokenizer] → [Dependency Parser] ──────┘
```

## 4. Repository Layer (`xnlp-core/repository/`)

Following Spring Data's Repository pattern, storage is abstracted behind interfaces:

| Interface | Implementation | Storage |
|-----------|---------------|---------|
| `ModelConfigRepository` | `JdbcModelConfigRepository` / `FileModelConfigRepository` | MySQL / PostgreSQL / H2 by default; `file` switches model catalog to JSON files |
| `DatasetRepository` | `JdbcDatasetRepository` / `FileDatasetRepository` | MySQL / PostgreSQL / H2 by default; `file` switches datasets to JSON files |
| `EvaluationRunRepository` | `JdbcEvaluationRunRepository` / `InMemoryEvaluationRunRepository` | MySQL / PostgreSQL / H2 by default; `memory` makes evaluation runs transient |
| Optional local adapters | `File*Repository`, `InMemoryEvaluationRunRepository` | `file` uses file model/dataset catalogs; `memory` only replaces evaluation-run storage |

**Why this pattern**: Services no longer know about file paths, JSON
serialization, or caching. Changing storage mechanism requires only a new
implementation, zero changes to business logic.

## 5. Service Layer Refactoring

### Before (pre-refactor)

```
NLPTaskService (500+ lines)
├── listTasks()           ← hardcoded task metadata
├── analyze()             ← 80-line switch on task type
├── tokenization()        ← demo impl mixed in service
├── pos(), ner(), dep()   ← more demo impls
├── tokens(), guessPos()  ← utility methods
└── classify(), sentiment() ← legacy prompt endpoints
```

### After

```
CapabilityRegistry (discovery & dispatch)
├── register(NlpComponent)
├── execute(id, context) → ComponentResult
└── listTasks() → backward-compat metadata

TokenizeComponent ──┬── registered via constructor(registry)
PosTaggerComponent ─┤
NerComponent ───────┤
ParserComponent ────┤
SentimentComponent ─┤
ClassifierComponent ┤
BuiltinDemoComponents (SRL, CON, AMR, KEYPHRASE, EXSUM, ABSUM, COR, STS, TST)

NLPTaskService (now ~140 lines, facade)
├── listTasks() → delegates to registry
├── analyze()  → builds NlpContext, delegates to registry
└── classify(), sentiment()... ← legacy, kept for backward compat
```

## 6. Package Structure

```
xnlp-core/
├── api/              ← Public SPI (NlpComponent, NlpContext, ComponentResult, PipelineNode)
├── config/           ← ModelConfig, ModelType, ModelProtocol, ModelSource
├── model/            ← PredictRequest, PredictResponse, ModelInfo, BenchmarkResult
├── pipeline/         ← ProcessingPipeline, PipelineManager, TextNormalizerPipeline
├── registry/         ← ModelRegistry (ChatModel lifecycle)
├── repository/       ← Storage interfaces (ModelConfigRepository, DatasetRepository, ...)
├── eval/             ← Evaluation domain: NLPTaskType, EvaluationRun, CompareResult...
└── errors/           ← XNLPException hierarchy

xnlp-server/
├── controller/       ← REST controllers (ModelController, DatasetController, ...)
├── service/          ← Orchestration services (ModelService, EvaluationService, ...)
├── nlp/              ← CapabilityRegistry (component discovery & dispatch)
├── component/impl/   ← NlpComponent implementations
├── repository/        ← JDBC repositories (default) + optional file/memory adapters
├── config/           ← Spring configuration (WebConfig, XNLPConfiguration, ...)
├── startup/          ← ModelInitializer
└── dto/              ← Request DTOs
```

## 7. Design Decisions

### 7.1 Why component-per-capability over monolithic service?

- **Testability**: Each NLP component can be unit-tested in isolation with
  mock contexts.
- **Extensibility**: New capability = new `@Component` class, zero changes
  to orchestration.
- **Swapability**: Demo implementation can be replaced with a real HanLP
  runtime or a model-backed implementation without touching the framework.

### 7.2 Why Repository pattern over direct file I/O?

- Services like `DatasetService` were doing file path resolution, JSON
  serialization, caching, and business logic all in one class.
- With Repository interfaces, the service focuses on business logic
  (validation, ID generation, timestamps), and storage is an
  implementation detail.
- The active Spring profile selects MySQL, PostgreSQL, or H2 through the
  standard Boot DataSource; services do not change when the database changes.

### 7.3 Why keep ModelCatalogService as a facade?

`ModelCatalogService` owns provider presets and API-facing conversion while
`ModelConfigRepository` owns persistence. The default implementation uses
Spring JDBC, so database switching remains a configuration concern.

### 7.4 Why persist evaluation runs?

Evaluation runs are part of the product workflow, so the default repository
persists them in the configured database. A `memory` profile remains available
for fast local experiments without changing `EvaluationService`; `file` remains
available for the two file-backed catalog/dataset adapters.

## 8. Future Roadmap

| Phase | Item | Status |
|-------|------|--------|
| 0.2 | Component-based NLP architecture | ✅ This refactoring |
| 0.3 | Pipeline DAG execution engine | `PipelineNode` defined, execution TBD |
| 0.4 | `NlpRuntime` SPI: LocalRuntime, RemoteRuntime, HanlpRuntime | Interfaces ready |
| 0.5 | Portable persistence (MySQL/PostgreSQL/H2) | ✅ Spring JDBC + datasource profiles |
| 0.6 | Async evaluation with progress tracking | ✅ |
| 0.7 | Model hot-reload via Actuator endpoint | |
