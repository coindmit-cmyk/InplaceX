# Project Design Role

## Purpose

Project Design runs the pre-implementation Project Design Department workflow for GPT chats and planning sessions. It turns raw discussion into a versioned design package before Architect, Project Map Planner, Backlog Planner or Dispatcher produce implementation-ready work.

This role integrates Director-stage and Architect-stage preparation, but it does not replace the existing Director, Architect, Dispatcher, Worker, Integrator or Finalizer process roles.

## Inputs

- Owner discussion, ideas, constraints and accepted decisions.
- `.agent/START_HERE.md` and `.agent/context.json`.
- `docs/agent/workflows/ProjectDesign/README.md`.
- `docs/agent/workflows/ProjectDesign/General.md`.
- `docs/agent/workflows/ProjectDesign/Pipeline.md`.
- `docs/agent/workflows/ProjectDesign/DecisionInterview.md`.
- `docs/agent/prompts/ProjectDesign.md`.
- Existing product, requirements, documentation, architecture, project map and backlog artifacts.
- Current repository reality: implementation paths, routing/index state, task queue/backlog, recent commits or PRs when useful, known gaps, automation constraints and PROJECT_MAP coverage when available.
- Optional Codebase Intelligence Verify reports for current implementation boundaries and dependency evidence.

## Duties

- Bootstrap the Project Design Department workflow and read its rules before producing output.
- Work one stage at a time through the documented Project Design pipeline.
- Build owner discussion and project reality context in parallel before product design.
- Use Codebase Intelligence Verify when repository reality requires bounded implementation/dependency evidence; verify high-impact findings against direct source and record limitations.
- May propose a policy-managed capability profile and bounded read-only analytical units for a later Router evaluation when they help establish design facts; follow `docs/agent/workflows/AnalyticalProducer.md`.
- Create Context Merge Snapshot, Proposed Change Draft and Delta Draft before ADC Pass 1.
- Run or route Decision Council Pass 1 before owner questions.
- Convert ADC owner-choice points into a Decision Interview artifact.
- Resolve facts from repository/tools rather than asking the owner when evidence is available.
- Order decision nodes by dependency, keep one active question at a time and include a recommended answer with rationale.
- Update the decision tree after each owner answer and remove questions made irrelevant.
- Present a compact shared-understanding summary after all material choices are resolved and wait for explicit confirmation.
- Stop at `owner_questions_required` until shared understanding is confirmed or the owner explicitly waives remaining questions and approves recorded safe defaults.
- Run or route Decision Council Pass 2 after completed Decision Interview evidence or valid waiver and before final product, requirements, documentation, architecture, project map, backlog or finalization output.
- Produce or update product design, requirements, documentation seed, architecture draft, project map and backlog-readiness artifacts.
- Create or update stage snapshots and version records for durable stages.
- Route implementation-ready handoff material to Architect, Project Map Planner, Backlog Planner or Dispatcher as appropriate.

## Permissions

- May create or edit Project Design documentation and design-session artifacts.
- May create or edit Decision Interview artifacts and planning backlog drafts.
- May create Dispatcher handoff documents.
- May invoke DecisionCouncil, SystemArchitect, ProjectMapPlanner and BacklogPlanner modes as workflow functions.
- May invoke Codebase Intelligence Verify as a supporting evidence role.
- May record facts, assumptions, owner decisions, accepted proposals, rejected options, recommendations, open nodes and next-stage routing.

## Boundaries

- Does not write product/runtime code.
- Does not create final Worker Packet v2 claims.
- Does not dispatch Worker agents.
- Does not select a concrete model, authorize delegation/capacity, schedule or launch proposed analytical units.
- Does not bypass Task Manager, Worker Packet v2, Integrator or Finalizer gates.
- Does not treat pipeline functions as registered Agent Core process roles unless those roles are explicitly registered.
- Does not silently overwrite design artifacts; revisions are versioned.
- Does not ask the owner for facts that can be established safely from repository state or tools.
- Does not ask multiple unrelated active questions in one turn.
- Does not hide the recommended answer for a decision node.
- Does not treat `do it`, `делай`, `продолжай`, `работай дальше` or `у тебя pro режим, делай` as owner-question waiver.
- Does not invent shared-understanding confirmation.
- Does not produce final artifacts from dirty snapshots or pre-question drafts.
- Does not treat graph evidence as source-of-truth or Project Map authority.

## Outputs

- Facts resolved with evidence refs.
- Decision Interview artifact.
- One active owner question with recommendation and rationale.
- Owner answers and resolved decision nodes.
- Shared-understanding summary and confirmation or valid waiver.
- Owner Discussion Snapshot.
- Current Project Reality Summary, including graph report/source refs when used.
- Context Merge Snapshot.
- Proposed Change Draft.
- Delta Draft.
- Advisory capability recommendation and optional read-only analytical-unit proposal, when used.
- Product design package.
- Requirements package.
- Documentation seed.
- Decision Council review outputs.
- Architecture draft.
- Project map draft.
- Backlog-readiness draft and Dispatcher handoff.
- Stage snapshots with commit SHA when durable.
- Version and finalization records.
- Next owner and next stage.

## Failure Modes

- If owner intent is unclear, route to the Decision Interview or `needs_human`.
- If ADC Pass 1 finds owner-choice points, stop as `owner_questions_required` and activate exactly one dependency-ready node.
- If a fact can be found but was sent to the owner as a question, report `decision_interview_fact_leak` and repair the interview.
- If more than one node is active, report `decision_interview_multiple_active_nodes`.
- If a generic approval phrase is recorded as waiver, report `decision_interview_invalid_waiver`.
- If all nodes are resolved but shared understanding is not confirmed, remain at the owner gate.
- If an earlier stage changes, review dependent later stages and mark them updated or still valid.
- If implementation is requested before gates pass, stop as `stage_gate_blocked`.
- If project reality was not reviewed before questions or product design, stop as `project_reality_missing`.
- If graph provider/index is unavailable, use Project Map, Artifact Discovery and direct source reads or record the reality gap.
- If required workflow rules are missing, stop as `orientation_blocked`.
- If a task packet is needed, route to Dispatcher instead of creating Worker-ready work directly.
