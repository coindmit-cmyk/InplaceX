# Security Risk Consumer

## Purpose

Security Risk Consumer handles possible secrets or sensitive infrastructure findings.

## Rule

Possible secret patterns are blocking findings, even with low confidence. They route to Human/Doctor/security review and must not be printed in full into reports.

## Redaction Rule

Reports must show:

- path;
- line number when available;
- detector name;
- confidence;
- redacted snippet only when safe.

Reports must not show raw tokens, passwords, SSH keys, production credentials or private infrastructure secrets.

## Route

```yaml
owner: Human
task_type: security_review
blocking_gate: human_security_review
auto_task_allowed: false
```
