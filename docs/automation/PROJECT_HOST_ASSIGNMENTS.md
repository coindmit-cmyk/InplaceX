# Project Host Assignments

Status: active orientation contract
Last reviewed: 2026-07-20

## Purpose

This table tells an agent which registered automation host and which project
SSH space belong to the current project.

It is intentionally safe for Git. It contains project ids, registered host ids,
SSH profile aliases and server-side workspace paths, but no IP addresses,
private keys, passwords, tokens or provider credentials.

## Mandatory Resolution Rule

Before remote execution, deployment, VPS work or unattended automation:

1. Resolve the current row by `project_id`.
2. Use `automation_host_id` only after confirming the live project Registry and
   runner state.
3. Use `project_ssh_profile` only as the operator route to the isolated project
   space.
4. Do not infer automation authority from an SSH alias or SSH config entry.
5. If the row is missing or conflicts with live Registry, runner or deployment
   state, stop as `host_assignment_unknown` and report the conflict.

Live Registry and deployed runtime state override a stale table entry. The
table must then be corrected in the same documentation change.

## Assignment Table

| `project_id` | Repository / scope | `automation_host_id` | `project_ssh_profile` | Server workspace | Notes |
| --- | --- | --- | --- | --- | --- |
| `ai-project-agent` | `coindmit-cmyk/ai-project-agent` | `aistudio` | `dmit-aistudio-space` | `/srv/agent-projects/aistudio` | Agent Core and shared automation development space. |
| `myvpn` | `coindmit-cmyk/MyVPN` | `aistudio` | `dmit-vpn-space` | `/srv/agent-projects/vpn` | VPN nodes are separate managed hosts and must be resolved from the local node inventory. |
| `e-shop` | `coindmit-cmyk/e-shop` | `aistudio` | `dmit-eshop-space` | `/srv/agent-projects/e-shop` | Product-specific production hosts are separate deployment targets. |
| `mobile-games` | Mobile games portfolio | `aistudio` | `dmit-mobile-games-space` | `/srv/agent-projects/mobile-games` | Shared portfolio space; a game with its own row uses its dedicated space. |
| `residentguard` | `coindmit-cmyk/ResidentGuard` | `aistudio` | `dmit-residentguard-space` | `/srv/agent-projects/residentguard` | Dedicated ResidentGuard project space. |
| `inplacex` | `GoodEvil11/InplaceX` | `aistudio` | `dmit-inplacex-space` | `/srv/agent-projects/inplacex` | Dedicated InplaceX game space; do not default to the shared mobile-games space. |

## Shared And Exceptional Routes

| Route | Purpose | Authority rule |
| --- | --- | --- |
| `aistudio` | Registered automation host id | Use only through live Registry and runner policy. |
| `aistudio-lan` | Local operator route to the AiStudio machine | Connectivity route only; it does not grant automation authority. |
| `dmit-control-01` | Control/admin SSH profile | Root or emergency administration only, not a routine project workspace. |
| `stoiki-vps` | FormaStand production host | E-SHOP product-specific deployment target; use only with the deployment contract and owner approval. |

## Secrets And Access

- Full VPS inventories, node lists, real endpoints and access material belong in
  the operator's local access vault.
- Tracked project documentation may reference a safe SSH profile alias, but
  must never copy a private key, password, token, raw subscription URL or
  production environment value.
- Missing credentials are an access problem, not a reason to add secrets to
  this table.
