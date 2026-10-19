# Reference comparison notes

The assignment discussion referenced other agentic URL-shortener projects, including the public `forge` example. This reconstruction uses those references only to compare broad engineering patterns such as explicit workflow state, observable artifacts, and human review. It contains no copied source from those repositories.

The implementation keeps the design intentionally small: standard Java 17 APIs, a provider boundary, explicit run records, a sandbox, bounded retry/rollback, and a separate URL API. Before submission, compare this repository against the actual assignment checklist and run a live provider-backed walkthrough; do not claim capabilities that have not been demonstrated by run artifacts.
