# GPT Chat Route

Use GPT Chat for project discussion, research, requirements, UX, architecture decisions and handoff preparation.

An accepted answer is not executable scope by itself. Durable output must be delivered as one PR-backed package under:

```text
AiStudio/Project_state/input/GPT/PR-<number>-<topic>/
```

Create the package as `PR-PENDING-<topic>` only until the PR number exists, then rename it before merge. Include `INTAKE.md` and `manifest.json`; add sources, research, decisions or proposed changes only when needed.

Do not copy an existing canonical project file into the package as a second source of truth. Record the target file, base ref/commit, intent, expected result and preservation constraints in the manifest.

GPT may recommend a route, skills, model and reasoning. It does not set `worker_ready`, edit shared input registries, merge the PR or authorize implementation.
