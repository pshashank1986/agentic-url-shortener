# Threat model and operational limits

## Protected assets

- The user's original repository and uncommitted work
- API keys and machine credentials
- Integrity of generated code, tests, and validation evidence
- Correct approval and release-readiness state

## Controls in this prototype

- Reads the source tree but applies proposed files only to a copied sandbox.
- Fingerprints the source before planning and before final approval; a mismatch invalidates the plan.
- Rejects traversal/absolute paths, symlinks, Git internals, common secret/key files, and GitHub workflow files.
- Limits source inventory, text sent to a model, patch size, changed file count, test duration, and repair count.
- Runs tests with a reduced environment and temporary home directory; API keys are not forwarded to build processes.
- Stops when the provider, policy validation, build, or evidence gate fails.
- Requires two explicit human approvals; never invokes Git commit, push, deployment, or promotion.

## Residual risks

The sandbox is a filesystem copy, not OS/container isolation. Build tools and repository scripts still execute as the current user and can access other local files or the network. Run untrusted repositories only inside a disposable VM/container with no credentials and restricted egress. Model prompts include source excerpts; configure only an approved provider and avoid sensitive repositories. The HTTP shortener is a demo: it has no authentication, persistence, rate limiting, expiry, abuse detection, or distributed collision coordination.
