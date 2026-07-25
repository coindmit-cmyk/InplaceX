# VPS Relay Remote PC Access

Status: implementation contract
Date: 2026-06-22

## Purpose

When the owner's laptop leaves the local network, Codex and automation scripts still need a controlled path to the remote automation PC.

The default path is a reverse SSH relay through the existing VPS:

```text
remote PC -> outbound SSH reverse tunnel -> VPS -> SSH commands/tests -> remote PC
```

The remote PC opens the connection out to the VPS. No inbound port on the local network is required.

## Required Shape

```text
remote PC sshd: 127.0.0.1:22
VPS relay bind: 127.0.0.1:<relay_port>
operator access: ssh to VPS, then ssh to 127.0.0.1:<relay_port>
```

The relay port must bind to `127.0.0.1` on the VPS by default. Do not bind it to `0.0.0.0` unless there is an explicit owner-approved firewall and access policy.

## Remote PC Service

The repository ships the canonical user service at
`scripts/agent_control/systemd/remote-pc-reverse-relay.service`. It uses
`Restart=always`, SSH keepalives, strict host-key checking and a private local
environment file. Agent Update Manager distributes both the unit and its
dry-run-first installer to adopted projects.

Dry run on the remote PC:

```bash
python3 scripts/agent_control/install_remote_pc_relay_service.py \
  --config runtime/agent-control/remote-pc-relay.local.json \
  --identity-file ~/.ssh/aistudio-relay \
  --json
```

Apply only after the dry run is clean and the dedicated identity and
`known_hosts` entries exist:

```bash
python3 scripts/agent_control/install_remote_pc_relay_service.py \
  --config runtime/agent-control/remote-pc-relay.local.json \
  --identity-file ~/.ssh/aistudio-relay \
  --apply \
  --json
systemctl --user status remote-pc-reverse-relay.service
```

The installer writes `~/.config/aistudio/remote-pc-relay.env` with mode `0600`
and enables `remote-pc-reverse-relay.service`. The private key remains outside
the repository and is never copied into reports.

Equivalent service shape:

```ini
[Unit]
Description=AiStudio reverse SSH relay to VPS
After=network-online.target ssh.service
Wants=network-online.target

[Service]
User=agent
Environment=RELAY_HOST=aistudio-vps
Environment=RELAY_PORT=22220
ExecStart=/usr/bin/ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -R 127.0.0.1:${RELAY_PORT}:127.0.0.1:22 relay@${RELAY_HOST}
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Use a dedicated key for this relay. Store the private key on the remote PC only. Store only `secret_ref` identifiers in repository or registry files.

## Connecting Through The VPS

From the VPS:

```bash
ssh -p 22220 agent@127.0.0.1 'hostname; pwd'
```

From another machine through the VPS as a jump host:

```bash
ssh -J root@aistudio-vps -p 22220 agent@127.0.0.1 'hostname; pwd'
```

For script testing:

```bash
ssh -J root@aistudio-vps -p 22220 agent@127.0.0.1 'cd /srv/agent-projects/automation-worktrees/ai-project-agent && python3 -m pytest -q'
```

## Security Rules

- Do not expose remote PC SSH/RDP directly to the public internet.
- Bind reverse tunnel ports to `127.0.0.1` on the VPS unless explicitly approved.
- Use a dedicated relay user/key and rotate it independently from root/admin keys.
- Do not put SSH private keys, passwords or tokens in Git, docs, task packets or prompts.
- Prefer command-line SSH for Codex/script work. RDP/XRDP is only for visual manual work.
- Any write-capable remote command must still follow queue, lock, branch and freshness gates.

## Verification

Use:

```bash
python scripts/agent_control/remote_pc_relay_check.py --config runtime/agent-control/remote-pc-relay.local.json --json
```

The checker reports:

- whether the relay configuration is safe to use;
- the exact local and jump-host SSH commands;
- whether the VPS has the relay port listening when `--check-vps` is used;
- whether a command can run through the relay when `--check-command` is provided.

## Stop Conditions

Stop and require owner review if:

- relay bind host is not loopback;
- relay port is missing or conflicts with a public service;
- private key material appears in a config file;
- SSH command requires a password prompt;
- the remote PC worktree is tracked-dirty;
- the requested script would mutate project state without a task/lock/freshness gate.
