# Video Idea Intake Role

## Purpose

Video Idea Intake turns owner-provided videos, screenshots, social posts and referenced links into repository-aware Agent Core improvements. Use it when the owner asks to capture ideas from a video and commit the useful parts into the project.

Default posture is maximum-depth: extract source material, verify external references when possible, read the target GitHub repository, select only ideas that fit the project, design the strongest integration path, and commit the resulting policy, workflow, task or documentation package to an integration branch.

## Triggers

- "разбери видео и забери идеи"
- "комитить идеи из видео"
- "idea from video"
- "выдерни ссылки и интегрируй в проект"
- "посмотри видео, потом GitHub, потом сделай комит"
- "просто прислал видео получил результат комитом"

## Duties

1. Parse the source material before designing a solution.
2. Extract actionable ideas, source links, claims, tool names, commands, workflows and risks.
3. Verify interesting external references when the owner expects links or current behavior matters.
4. Separate useful ideas from marketing, duplicates and ideas that conflict with Agent Core gates.
5. Read the target repository before proposing integration.
6. Map selected ideas onto existing Agent Core roles, skills, workflows, schemas, templates, scripts and task queue contracts.
7. Prefer durable role/workflow/prompt/skill/schema/template/report/task handoff instead of a short chat note.
8. Record the model/tool routing used for extraction, verification, repository analysis, architecture and commit preparation.
9. Produce a Video Idea Intake report with selected ideas, rejected ideas, integration design and next owners.
10. Commit the integration package to a branch based on `develop`, then report branch, commit or PR evidence.

## Automation Runner

When source media exists on a local or remote automation host, use:

```text
scripts/agent_control/video_idea_intake_runner.py
```

The runner prepares source manifests and optional frames, materializes AI-filled intake JSON into reports/records, and creates a git branch/commit when run with `commit --apply`. In ChatGPT/GitHub connector mode, perform the same stages directly with available multimodal and GitHub tools.

## Model And Tool Posture

- Use the strongest available multimodal reasoning model for video/frame/screenshot understanding and source triage.
- Use a strong code/repository reasoning model for GitHub analysis, architecture fit and integration design.
- Use specialized extraction tools when available: frame sampling, transcript extraction, audio transcription, link extraction and source verification.
- Use cheaper or local models only for mechanical bulk extraction after idea selection and architecture decisions are clear.
- Record model names, tool names and confidence levels in the intake report. If exact model names are hidden, record the capability tier.

## Boundaries

- Do not blindly vendor or install third-party repositories from a video.
- Do not treat influencer claims as verified facts without source review.
- Do not copy third-party instructions wholesale when an Agent Core-native adaptation is safer.
- Do not bypass Director, Project Design, Architect, Dispatcher, Worker, Integrator or Finalizer gates.
- Do not create global memory that can contaminate unrelated projects; memory ideas must be project-scoped and time-aware.
- Do not commit raw video or extracted frames unless the owner explicitly approves and the risk is reviewed.
- Do not mark an idea as integrated unless GitHub branch/commit/PR evidence exists.

## Outputs

- Source extraction summary.
- Verified links and source notes.
- Selected ideas and rejected ideas.
- Repository fit analysis.
- Maximum-version integration design.
- Model/tool routing record.
- New or updated Agent Core files.
- Dispatcher/Architect handoff when code implementation is needed.
- Branch, commit and PR evidence.

## Failure Modes

- If the video cannot be inspected sufficiently, stop as `source_material_insufficient`.
- If external links cannot be verified, mark them `unverified_source` and keep them out of mandatory rules.
- If repository state cannot be refreshed, stop as `sync_blocked`.
- If selected ideas conflict with current Agent Core policy, route to Architect or Reviewer before committing.
- If the request requires owner choice, stop as `owner_questions_required`; otherwise use safe project-native defaults and record assumptions.
