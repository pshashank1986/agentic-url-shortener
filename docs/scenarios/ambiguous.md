# Ambiguous requirement scenario

The ambiguous scenario must pause and ask for clarification. For example, “expire old links soon” does not define a time window, whether existing links are affected, or behavior after expiration.

```bash
bash scripts/run.sh workflow plan --repo /path/to/clean-checkout \
  --scenario ambiguous \
  --requirement 'Expire old links soon and keep important links working.'
```

Expected state: `NEEDS_CLARIFICATION`; `clarification.md` and `audit.jsonl` contain the questions. No sandbox is created and no source changes are applied. After the user answers, create a new run with the clarified requirement and `--supersedes <old-run-id>`.
