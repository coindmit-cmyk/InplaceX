# Artifact Discovery Role Integration

## Dispatcher

Creates task routes from findings and blocks only new/current significant unresolved findings according to policy.

## Integrator

Consumes integration-surface, map and cleanup findings during PR/branch review.

## Doctor

Consumes policy drift, stale rules and legacy state references.

## Finalizer

Checks unresolved new/current significant findings before finalization. Legacy unrelated findings may remain as task/backfill routes.

## ProjectMapPlanner

Consumes map findings and updates `PROJECT_MAP.json`, `PROJECT_MAP.md` and related map views.

## UX Design

Consumes human-facing findings that need UX contract or waiver.
