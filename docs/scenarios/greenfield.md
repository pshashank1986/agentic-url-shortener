# Greenfield scenario

Create an empty starter directory and ask the model to generate an initial feature. The source directory remains empty; generated files appear in the run's sandbox after plan approval.

```bash
mkdir -p /tmp/url-shortener-starter
bash scripts/run.sh workflow plan --repo /tmp/url-shortener-starter \
  --scenario greenfield \
  --requirement 'Create a small Java HTTP service with POST /api/shorten, GET /{code}, and click-count analytics.'
```

Review the output, approve with `workflow apply --approve-plan`, and capture generated files, build logs, diff, and the final approval. Do not mark the scenario complete until these run artifacts exist.
