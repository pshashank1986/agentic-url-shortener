# Agentic URL Shortener (Java 17)

This Java 17 prototype contains two intentionally separate parts:

1. A small HTTP URL shortener that demonstrates link creation, redirects, and click analytics.
2. An approval-gated SDLC workflow that reads a local repository, proposes a change, and validates it in a separate working copy.

The URL API is the sample application. The workflow is a CLI that can target a repository path supplied at runtime; it is not wired into the URL API. The workflow never edits the supplied source repository and never deploys.

## Interview pitch

> I built a Java 17 prototype with a URL-shortening API and a governed SDLC workflow. The workflow takes a requirement and a repository path, uses an OpenAI-compatible model to produce a requirement interpretation, impact analysis, design, task plan, and concrete code proposal. A human must approve the plan before the proposal is applied to a separate working copy. The workflow runs the repository’s supported tests and allows up to two repair attempts based on actual failures. After a passing run, a second approval marks the result ready for review. The original source repository remains unchanged, and the prototype does not commit, push, deploy, or promote a release.

## How the workflow runs

~~~mermaid
flowchart TD
  A[Requirement and repository] --> B[Interpret requirement]
  B --> C[Impact analysis and design in parallel]
  C --> D[Task plan and proposed patch]
  D --> E{Human plan approval}
  E --> F[Apply to working copy and run tests]
  F --> G{Human review and approval}
  G --> H[Ready for review]
~~~

The workflow records stage outputs, validation results, and audit events under the run-artifact directory. If the requirement is unclear, the model or policy fails, or no supported test command is found, the run stops for manual review. A passing run is still a review candidate, not a production release.

## Important design decisions

- **Read-only source boundary:** Planning reads the target repository and stores its artifacts separately. After plan approval, changes are applied only to a copied working directory. “Read-only” refers to the supplied source repository; the workflow does write proposal and audit artifacts outside it.
- **Two human gates:** One approval allows sandbox application. A second approval, after reviewing the diff and test evidence, changes the status to `READY_FOR_REVIEW`.
- **Parallel analysis:** Requirement interpretation happens first. Code impact analysis and architecture design run in parallel, then feed task planning and the implementation proposal.
- **Evidence-based validation:** The workflow records the test command, exit code, duration, output, and repair history. It allows one initial validation plus at most two repair attempts.
- **Safe stops:** Ambiguity, provider errors, unsafe paths, or missing validation evidence do not count as success.
- **No production actions:** The CLI does not create commits, push to GitHub, deploy, or promote a release.

## Quick start

Requirements: JDK 17 or later. Git is used to generate diffs. Maven or Gradle is optional and only needed when a target repository uses it for tests.

Run the project’s compile and smoke checks:

~~~bash
bash scripts/test.sh
~~~

Start the URL shortener on port 8080:

~~~bash
bash scripts/run.sh serve 8080
~~~

The service uses the JDK HTTP server and stores links in memory. Restarting the process clears links.

## Try the URL API

Create a link with a custom alias:

~~~bash
curl -i http://localhost:8080/api/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/articles/agentic-sdlc","alias":"demo1"}'
~~~

The alias is optional. Without one, the service generates a random Base62 code.

~~~bash
curl -i http://localhost:8080/demo1
curl http://localhost:8080/api/analytics/demo1
curl http://localhost:8080/health
~~~

The API validates absolute HTTP/HTTPS URLs, rejects duplicate aliases, redirects with HTTP 302, and tracks GET redirect counts. Its in-memory storage, lack of authentication, and lack of rate limiting make it a demonstration service, not a production deployment.

## Configure the model provider

The workflow requires an OpenAI-compatible chat-completions endpoint. Set the key and model in your shell; do not commit credentials:

~~~bash
export AGENTIC_LLM_API_KEY='your-key'
export AGENTIC_LLM_MODEL='your-model'
# Optional. Defaults to https://api.openai.com/v1
export AGENTIC_LLM_BASE_URL='https://api.openai.com/v1'
~~~

Repository excerpts are sent to the configured provider. Use only repositories and providers you are authorized to share. If configuration is missing or a model stage fails, the workflow stops for manual review instead of inventing a result.

## Run the SDLC workflow

Plan a change against an existing repository:

~~~bash
bash scripts/run.sh workflow plan \
  --repo /path/to/repository \
  --requirement 'Add a configurable expiration time to shortened URLs' \
  --scenario brownfield
~~~

The command prints a run ID and stores the proposal, stage records, and audit log under `~/.agentic-url-shortener/runs/<run-id>`. Review `proposal.md`, `proposal-diff.patch`, the risks, and the test plan before approving.

Apply the approved proposal to the run’s working copy and validate it:

~~~bash
bash scripts/run.sh workflow apply --run <run-id> --approve-plan
~~~

If validation passes, review `diff.patch`, validation logs, and `final-engineering-summary.md`. Then record the second approval:

~~~bash
bash scripts/run.sh workflow finalize --run <run-id> --approve-release
~~~

This marks the sandbox result `READY_FOR_REVIEW`. It does not copy changes into the supplied repository, commit, push, deploy, or promote a release. To reject a run and remove its sandbox while retaining audit evidence:

~~~bash
bash scripts/run.sh workflow reject --run <run-id> --reason 'Risk is too high'
~~~

For clarification, greenfield, and brownfield walkthroughs, see [`docs/scenarios/`](docs/scenarios/). For the 15-item assessment evidence map, see [`docs/assessment-coverage.md`](docs/assessment-coverage.md).

## Architecture at a glance

| Area | Implementation |
|---|---|
| URL API | `UrlShortenerServer`: JDK HTTP server for health, creation, redirect, and analytics |
| Workflow orchestration | `WorkflowCli`: CLI state machine for planning, approvals, sandbox validation, repair, and review |
| Model provider | `AgentBackend`: OpenAI-compatible chat-completions client configured through environment variables |
| Repository controls | `RepositorySupport`: bounded inventory, source fingerprinting, path validation, sandbox copy, diff, and test runner |
| Run evidence | Local run records and append-only JSONL audit events under the user's home directory |

The sandbox is a filesystem copy, not OS or container isolation. Repository build scripts execute as the current user, with a reduced environment, and may still access the network or other local resources. Use a disposable container or VM for untrusted repositories. See [`docs/threat-model.md`](docs/threat-model.md) for details.

## Project documentation

- [`docs/architecture.md`](docs/architecture.md) — components, workflow control flow, and interview trade-offs
- [`docs/threat-model.md`](docs/threat-model.md) — protections and residual risks
- [`docs/scenarios/`](docs/scenarios/) — greenfield, brownfield, and ambiguous-input walkthroughs
- [`docs/reference-comparison.md`](docs/reference-comparison.md) — high-level design references
