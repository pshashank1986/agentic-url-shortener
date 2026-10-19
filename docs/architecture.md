# Architecture and interview walkthrough

## Components

- `UrlShortenerServer`: JDK `HttpServer` endpoints for health, create, redirect, and analytics.
- `Json`: small JSON codec so the prototype does not depend on third-party libraries.
- `AgentBackend`: OpenAI-compatible chat-completions client, configured with environment variables.
- `WorkflowCli`: state machine and approval gates for planning, applying, validation, repair, rollback, and final review.
- `RepositorySupport`: bounded repository inventory, fingerprinting, path policy, sandbox copy, diff, and test process runner.
- `Store`: local run records and append-only JSONL audit events under the user's home directory.

## Request and workflow control flow

For the shortener API, create validates absolute `http`/`https` URLs, stores an alias/code, and returns a short link. Redirect performs a lookup, increments the counter, and responds with HTTP 302. Analytics reports the stored target, create time, and count.

For an engineering change, requirement interpretation runs first. Impact analysis and architecture design then run in parallel. The resulting context feeds task decomposition and a repository-specific implementation proposal. The plan gate blocks file application until an explicit approval flag is supplied. The sandbox is a copy; test failure goes to a bounded repair stage. Passing validation pauses at a second approval gate. Final state means “ready for review,” never “deployed.”

## Defensible trade-offs

- A simple in-memory URL store keeps the core service runnable without infrastructure. It loses links on restart and is not horizontally consistent.
- A local CLI makes approval and evidence visible while avoiding accidental public deployment. It is not a multi-user approval service.
- Source edits happen only in an isolated working copy, preserving the starter repository and making rollback verifiable.
- The model provider is pluggable via an OpenAI-compatible endpoint. Offline behavior is a safe stop, not deterministic pseudo-reasoning.
- A provider-backed run is required to demonstrate genuine contextual reasoning; fixtures and docs are not represented as live model evidence.

## Interview summary

“I separated the URL-shortener domain service from the SDLC orchestration. For the orchestration, I modeled requirements, impact, and design as stateful stages with a dependency graph. Impact and design can run in parallel after requirements are normalized. Proposed changes are gated by a human approval, applied in a sandbox, validated, repaired at most twice, and then gated again before they are marked ready for review. Every decision and validation is recorded, while the original repository remains unchanged. I deliberately stop on ambiguity, provider failure, or missing test evidence, and I do not allow the prototype to deploy.”
