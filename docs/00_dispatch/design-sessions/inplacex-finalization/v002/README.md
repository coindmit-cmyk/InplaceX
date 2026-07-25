# InplaceX finalization v002

This package supersedes the planning-only v001 handoff. It is based on the
preserved game-fix commit `2ee8252` and the owner-controlled fork
`coindmit-cmyk/InplaceX`.

The package deliberately keeps current developer/debug capabilities available
for owner testing. Release isolation and real providers are later explicit
gates; they must not block architecture, localization, online foundation, or
UX work.

Execution is dependency-driven. Workers must use separate clean worktrees and
must not broaden `allowed_paths`. Physical-device operations, production/VPS
activation, signing, DNS, TLS, firewall, and real provider credentials are
serialized owner/integrator gates.
