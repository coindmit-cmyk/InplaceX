# Version Navigation Gate

Status: Project Standard v2 contract

## Purpose

Every write-capable action must resolve the current project, checkout role, version and navigation files before reading or editing project files.

## Mandatory Entry Order

1. Load Registry entry.
2. Resolve branch role and checkout path.
3. Validate workspace containment.
4. Fetch Git remotes.
5. Fast-forward clean behind checkouts.
6. Stop and report dirty, diverged or wrong-role checkouts.
7. Read and validate `PROJECT_VERSION.json`.
8. Read `PROJECT_INDEX.md`.
9. Read `DOCUMENTATION_MANIFEST.json`.
10. Validate task packet, locks and command scope.

## Version File

`PROJECT_VERSION.json` separates:

- `product_version`: accepted product code version.
- `state_revision`: durable automation state revision.
- `documentation_revision`: navigation and documentation revision.
- `component_versions`: optional per-application, script, document, module or runtime artifact version records.
- `branch_role`: checkout role.
- `git_commit`: recorded source commit.

If self-referential commit recording is impossible, implementations must use an explicit `content_base_commit` / `recorded_by_commit` contract rather than requiring impossible equality.

## Component Versions

Project version is the top-level orientation marker, but a project may contain multiple independently changing applications, scripts, documents and automation components. Each independently reviewed component should record a stable version row in `component_versions`.

Component rows should include:

- `id`: stable component id, such as `android-app`, `control-server`, `telegram-admin-bot`, `deploy-script` or `architecture-spec`;
- `kind`: component category, such as `application`, `script`, `document`, `module`, `service`, `schema` or `automation`;
- `version`: the component's current accepted version;
- `path`: primary file or folder used to locate the component;
- `owner`: responsible role or team;
- `updated_at` and `updated_by`;
- `source_ref`: Git ref, commit, report or document that proves the version.

Before a write-capable agent edits a component, it must record the version it observed. After the change, Integrator or Finalizer must compare the recorded version with the current project state and either update the component version or report a version conflict. This makes drift searchable by version instead of by chat memory.

## Schema

`schemas/agent-control/project_version.schema.json` defines the minimum v2 version object.
