# Artifact Discovery Contract

Artifact Discovery provides a consistent contract for detecting repository artifacts and routing them to the right owner.

## Inputs

- repository files;
- optional changed paths;
- optional project map;
- optional index/catalog docs;
- task state files when present.

## Outputs

- inventory;
- findings;
- classified findings;
- route records;
- task candidates;
- Markdown report.

## Owners

- Dispatcher for task creation;
- ProjectMapPlanner for map updates;
- Integrator for integration repair/cleanup review;
- Doctor for policy drift;
- UX Design for UX contract/waiver;
- Human for sensitive-risk findings.
