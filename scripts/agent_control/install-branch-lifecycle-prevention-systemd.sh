#!/usr/bin/env bash
set -euo pipefail

SOURCE_ROOT="${SOURCE_ROOT:-/home/main/agent-relay-worktrees/ai-project-agent-release}"
RUNTIME_ROOT="${RUNTIME_ROOT:-/mnt/d/agent-runtime}"
ARCHIVE_ROOT="${ARCHIVE_ROOT:-/srv/aistudio-hdd/AiStudioData/archive/git-branches}"
EVIDENCE_ROOT="${EVIDENCE_ROOT:-$RUNTIME_ROOT/branch-lifecycle-prevention}"
EVIDENCE_CACHE="${EVIDENCE_CACHE:-$RUNTIME_ROOT/branch-unknown-evidence-v339/evidence-cache.json}"
UNIT_DIR="${UNIT_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user}"
SERVICE_NAME="aistudio-branch-lifecycle-prevention.service"
TIMER_NAME="aistudio-branch-lifecycle-prevention.timer"

if [ "$(basename "$SOURCE_ROOT")" != "ai-project-agent-release" ]; then
  echo "SOURCE_ROOT must be the ai-project-agent release checkout" >&2
  exit 2
fi

mkdir -p "$UNIT_DIR" "$EVIDENCE_ROOT"

cat >"$UNIT_DIR/$SERVICE_NAME" <<EOF
[Unit]
Description=AiStudio ai-project-agent deterministic branch lifecycle prevention
ConditionPathIsMountPoint=/srv/aistudio-hdd

[Service]
Type=oneshot
WorkingDirectory=$SOURCE_ROOT
Environment=HOME=$HOME
ExecStart=/usr/bin/python3 $RUNTIME_ROOT/current/agent-core/scripts/agent_control/branch_recovery_capture_runner.py --prevention --source-root $SOURCE_ROOT --evidence-root $EVIDENCE_ROOT --evidence-cache $EVIDENCE_CACHE --repository ai-project-agent --archive-root $ARCHIVE_ROOT --batch-size 25 --max-branches 25 --max-scan-refs 500 --max-wall-seconds 1200 --command-timeout-seconds 900 --apply --yes --json
TimeoutStartSec=1250
EOF

cat >"$UNIT_DIR/$TIMER_NAME" <<EOF
[Unit]
Description=Schedule ai-project-agent branch lifecycle prevention

[Timer]
OnCalendar=*-*-* 04,10,16,22:17:00
RandomizedDelaySec=10min
AccuracySec=1min
Persistent=false

[Install]
WantedBy=timers.target
EOF

systemctl --user daemon-reload
systemctl --user enable "$TIMER_NAME"
systemctl --user start "$TIMER_NAME"
systemctl --user list-timers "$TIMER_NAME" --no-pager
