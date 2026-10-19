# Brownfield scenario

Run against a clean, known revision of a repository and request one bounded change. The tool inventories files and Git status, then uses repository evidence in its impact/design stages.

```bash
bash scripts/run.sh workflow plan --repo /path/to/clean-checkout \
  --scenario brownfield \
  --requirement 'Add an optional expiration timestamp to shortened links while preserving existing create and redirect behavior.'
```

Capture the baseline commit/hash, impact analysis, proposed changed files, plan approval, sandbox diff, test command/exit code, any repair history, final summary, and release approval. Verify the original checkout hash remains unchanged.
