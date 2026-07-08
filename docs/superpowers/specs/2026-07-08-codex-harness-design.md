# Codex Harness Design for X-NLP

Date: 2026-07-08

## Purpose

X-NLP will be developed through repeated Codex tasks that touch a Spring Boot backend, a React frontend, model protocol configuration, evaluation data, and regression testing. The harness gives each task a consistent operating model so Codex can start from the right context, choose the right workflow, run the right verification, clean up test data, and deliver changes through GitHub after tests pass.

The harness is repository-local and versioned with the project. It is not a replacement for `AGENTS.md`; it complements it with task-specific entry points, templates, scripts, and X-NLP-specific validation rules.

## Goals

- Provide a single task entry point for Codex before implementation starts.
- Standardize feature, bugfix, UI regression, and model integration tasks.
- Make verification repeatable across frontend, backend, API smoke, and UI smoke checks.
- Keep temporary test data isolated and easy to clean.
- Encode X-NLP-specific rules for model types, standard protocols, evaluation datasets, and frontend responsiveness.
- Preserve the project rule that passing tasks are committed and pushed to GitHub.

## Non-Goals

- No new CI system in the first version.
- No complex multi-agent orchestration in the first version.
- No replacement for Maven, Vite, or existing project build tooling.
- No production deployment automation.
- No browser-only automation that cannot also be approximated by scripts or documented manual checks.

## Proposed Directory Layout

```text
X-NLP/
├── .codex/
│   ├── harness/
│   │   ├── START_HERE.md
│   │   ├── WORKFLOW.md
│   │   ├── CHECKLISTS.md
│   │   └── XNLP_RULES.md
│   └── templates/
│       ├── feature-task.md
│       ├── bugfix-task.md
│       ├── ui-regression-task.md
│       ├── model-integration-task.md
│       └── handoff-summary.md
├── docs/
│   └── codex/
│       ├── SOP.md
│       └── VERIFICATION.md
└── scripts/
    └── codex/
        ├── check.sh
        ├── smoke-api.sh
        ├── cleanup-test-data.sh
        └── env-status.sh
```

## Harness Files

### `.codex/harness/START_HERE.md`

The first file Codex should read for X-NLP development tasks. It tells Codex to:

- Read `AGENTS.md`, `SPEC.md`, and the relevant task template.
- Check `git status --short` before editing.
- Identify whether the task is feature, bugfix, UI regression, model integration, or documentation.
- Use the matching checklist in `.codex/harness/CHECKLISTS.md`.
- Run the required verification commands before final delivery.
- Commit and push after tests pass, unless the user explicitly says not to.

### `.codex/harness/WORKFLOW.md`

Defines the normal lifecycle:

1. Context: read project instructions, inspect relevant files, understand current runtime state.
2. Plan or spec: for larger tasks, create or update a spec/plan before implementation.
3. Implement: keep changes scoped to the task.
4. Verify: run the required commands for the touched areas.
5. Regression: exercise affected UI/API paths.
6. Clean up: remove temporary `ui-test-*`, `ui-reg-*`, and debug data.
7. Deliver: summarize changes, tests, residual risk, commit hash, and push result.

### `.codex/harness/CHECKLISTS.md`

Contains task-type checklists:

- Feature checklist
- Bugfix checklist
- UI regression checklist
- Backend API checklist
- Model integration checklist
- Release/push checklist

Each checklist states required commands, required manual/browser checks, and cleanup expectations.

### `.codex/harness/XNLP_RULES.md`

Captures X-NLP-specific rules:

- Model types are `CHAT`, `EMBEDDING`, and `RERANKING`; UI and backend behavior must respect the type.
- Model providers must use standard protocol interfaces such as OpenAI-compatible chat, OpenAI embeddings, Ollama, Anthropic Messages, Gemini, Cohere rerank, or Jina rerank.
- Non-standard model adapters are out of scope unless explicitly approved.
- Evaluation datasets must support create, list, view entries, export, and delete regression.
- Frontend pages must remain responsive and avoid page-level horizontal overflow.
- Any model or dataset test data must be clearly prefixed and cleaned after testing.

## Templates

### Feature Task Template

Prompts Codex to capture user goal, affected modules, planned API/UI changes, data model changes, tests, and rollout notes.

### Bugfix Task Template

Prompts Codex to record reproduction, observed behavior, expected behavior, root cause, fix scope, regression test, and cleanup.

### UI Regression Task Template

Prompts Codex to check all routes, browser console errors, page-level overflow, key interactions, table scrolling, forms, modals, and cleanup.

### Model Integration Task Template

Prompts Codex to capture provider, protocol, model type, required credentials, base URL, request/response semantics, validation method, and unsupported behavior.

### Handoff Summary Template

Provides a structured final summary for long-running tasks or handoff between Codex sessions.

## Scripts

### `scripts/codex/check.sh`

Runs the standard local verification suite:

```bash
npm run build --prefix xnlp-frontend
mvn test -pl xnlp-core -Dmaven.repo.local=/tmp/m2
mvn test -pl xnlp-server -Dmaven.repo.local=/tmp/m2
```

The script should fail fast and print each command before running it.

### `scripts/codex/smoke-api.sh`

Checks the local backend API. It assumes the backend is running on `http://127.0.0.1:8080` unless `XNLP_API_BASE` is set.

Initial smoke endpoints:

- `GET /health`
- `GET /api/v1/models`
- `GET /api/v1/models/capabilities`
- `GET /api/v1/datasets`
- `GET /api/v1/evaluations`

The smoke script should print HTTP status and a compact response preview. It should not require external model credentials.

### `scripts/codex/cleanup-test-data.sh`

Cleans test artifacts created by Codex runs:

- Model profiles with names matching `ui-test-*`, `ui-reg-*`, `codex-test-*`, or `debug-*`.
- Datasets with names matching the same prefixes.

The script should default to dry-run. A `--apply` flag performs deletion.

### `scripts/codex/env-status.sh`

Prints useful local status:

- Current branch and git status.
- Whether ports `5173` and `8080` are listening.
- Frontend and backend command hints.
- Java, Maven, Node, and npm versions when available.

## Verification Policy

Required checks depend on touched areas:

| Change Area | Required Verification |
|---|---|
| Frontend code | `npm run build --prefix xnlp-frontend`, affected page browser check |
| Backend code | `mvn test -pl xnlp-server -Dmaven.repo.local=/tmp/m2`, API smoke if endpoint behavior changed |
| Core code | `mvn test -pl xnlp-core -Dmaven.repo.local=/tmp/m2` |
| Model management | API smoke, model list UI, type-specific behavior check |
| Dataset/evaluation | Dataset create/list/view/export/delete smoke, evaluation list UI |
| Cross-cutting | `scripts/codex/check.sh` |

Frontend browser checks must include:

- Page loads without console errors.
- No page-level horizontal overflow.
- Key controls are enabled/disabled according to type and state.
- Forms show useful validation messages.

## Git and Delivery Policy

- Run `git status --short` before editing and before final delivery.
- Do not revert unrelated user changes.
- After tests pass, stage only task-related files.
- Commit with Conventional Commits.
- Push to the current GitHub repository using the existing token-based workflow when needed.
- Final response must include changed files, verification commands, commit hash, push status, and any residual risk.

## MVP Acceptance Criteria

The first harness implementation is complete when:

- `.codex/harness/START_HERE.md`, `WORKFLOW.md`, `CHECKLISTS.md`, and `XNLP_RULES.md` exist.
- `.codex/templates/*.md` contains the five task templates listed above.
- `docs/codex/SOP.md` and `docs/codex/VERIFICATION.md` explain how to use the harness.
- `scripts/codex/check.sh`, `smoke-api.sh`, `cleanup-test-data.sh`, and `env-status.sh` exist and are executable.
- `scripts/codex/check.sh` passes in the current workspace.
- `scripts/codex/smoke-api.sh` works when the backend is running.
- `scripts/codex/cleanup-test-data.sh` supports dry-run and `--apply`.

## Risks and Mitigations

- Risk: Harness docs become stale.
  - Mitigation: Keep files short and task-oriented; update harness when workflows change.
- Risk: Cleanup script deletes user data.
  - Mitigation: Only match explicit test prefixes and default to dry-run.
- Risk: Smoke scripts fail when services are not running.
  - Mitigation: Print clear startup hints and exit with a useful message.
- Risk: Codex skips the harness.
  - Mitigation: `START_HERE.md` is the single entry point and `AGENTS.md` can later point to it if the user approves.

## Open Decisions

No unresolved design decisions remain for the MVP. CI integration, multi-agent worktrees, and deployment automation are deferred until the harness is used successfully in local development.
