# Assessment coverage

This table maps the assignment review items to implementation evidence. It describes what the code is intended to support; it does not claim that provider-backed runs have already completed. A live walkthrough should produce the run artifacts and validation results for reviewer inspection.

| Review item | Implementation evidence |
|---|---|
| 1. Actual engineering changes | The model returns file contents or deletions; explicit plan approval is required before changes are applied to a sandbox copy. |
| 2. Context-sensitive reasoning | The configured model receives the requirement, repository evidence, and prior-stage outputs. |
| 3. Brownfield reasoning | The workflow builds a bounded repository inventory, captures Git status, and runs an impact-analysis stage. |
| 4. Validation gates | A supported build or test command is run; the workflow records the command, exit code, output, and duration. |
| 5. Failure-driven repair | Actual validation failure output is passed to a repair stage; no more than two repair attempts are allowed. |
| 6. Rollback | Rejected or failed runs remove the sandbox and record source-fingerprint evidence. |
| 7. Greenfield | An empty starter directory can be used; generated files are proposed and applied only in the sandbox. |
| 8. Brownfield | A repository with existing code can be used; the source remains unchanged while a sandbox contains the proposed patch. |
| 9. Decision lineage | Stage input hashes, output artifact references, audit events, validation records, and repair history are persisted. |
| 10. Effective approvals | Explicit CLI flags gate sandbox application and final review readiness. |
| 11. Dynamic replanning | A new run can use `--supersedes`; the old run is marked superseded and its prior approvals are invalidated. |
| 12. Meaningful fallback | Provider or policy failures stop for manual review; an unvalidated result is never marked successful. |
| 13. Final documentation | After validation passes, the provider is asked to summarize the final changed source and actual test evidence. |
| 14. Three scenarios | Scenario recipes cover greenfield, brownfield, and clarification-required workflows. |
| 15. Execution-derived metrics | `workflow metrics` reads persisted runs and measured validation, repair, and rollback events. |

Some items require a live provider-backed run to demonstrate contextual code generation and scenario evidence. The scenario documents describe how to run those checks; they are not evidence that those runs have already executed.
