# Remote PC Power Recovery

## Purpose

The remote automation PC must recover without the owner's laptop. Recovery is
layered because Wake-on-LAN only works when its initiator can emit the magic
packet on the target PC's layer-2 network.

```text
same-L2 initiator -> magic packet when offline (optional)
remote PC RTC timer -> near-term autonomous wake (fallback)
remote PC firmware/NIC -> power on -> Linux network + SSH
systemd linger -> user automation timers -> reverse SSH relay -> VPS evidence
```

An OpenWrt/R4S box on `192.168.1.0/24` cannot directly wake a PC behind a
MikroTik LAN on `192.168.88.0/24`. Routed UDP, a VPS relay and ordinary ping do
not replace a same-L2 broadcast. In this topology R4S remains an always-on
observer, while RTC is the autonomous recovery path. The tool reports
`initiator_not_on_target_l2` and refuses `--send-wol` instead of claiming a
non-functional setup.

Represent that topology explicitly in the local-only config:

```json
{
  "network": {
    "target_cidr": "192.168.88.0/24",
    "broadcast_address": "192.168.88.255"
  },
  "initiator": {
    "kind": "openwrt",
    "ipv4_cidr": "192.168.1.1/24",
    "same_l2": false,
    "lan_interface": "br-lan"
  },
  "rtc_fallback": {
    "enabled": true,
    "wake_after_seconds": 900,
    "refresh_interval_seconds": 300
  }
}
```

The legacy `router` object remains accepted so already deployed RouterOS
configs continue to work. New configs should use `initiator`; when both are
present, `initiator` is authoritative.

## Preconditions

- The remote PC uses wired Ethernet. Wi-Fi wake is out of scope.
- Firmware enables `Wake on PCI-E/LAN`; disable ErP/deep S5 power saving when it
  removes standby power from the NIC.
- Linux NetworkManager configures `802-3-ethernet.wake-on-lan=magic`.
- The automation user has systemd linger enabled, so user timers start without
  an interactive login.
- RTC wake is available as `/sys/class/rtc/rtc0/wakealarm`; firmware must allow
  RTC wake from the selected power state.
- Router/device credentials stay outside Git. The repository stores generated
  commands and `secret_ref` identifiers only.

## Dry Run

```bash
python scripts/agent_control/remote_pc_power_recovery.py \
  --config runtime/agent-control/remote-pc-power-recovery.local.json \
  --json
```

The report states whether WOL is topologically valid, lists one-time Linux/NIC
commands and exposes the RTC files that apply mode will install. Legacy
MikroTik configs remain supported and still receive idempotent RouterOS
commands.

## RTC Fallback

On the remote Linux PC, apply the fallback from the stable Agent Core checkout:

```bash
sudo python scripts/agent_control/remote_pc_power_recovery.py \
  --config /home/main/agent-runtime/power-recovery/remote-pc-power-recovery.local.json \
  --install-rtc --json
```

The installer creates `aistudio-rtc-wake-refresh.timer`. Every five minutes it
arms an RTC wake fifteen minutes ahead. If the PC shuts down while AC power is
still present, a near-term alarm remains armed. This does not replace firmware
`Restore on AC Power Loss` for a physical power outage or a hardware watchdog
for a hard kernel lockup.

## Local Wake Test

Only from a machine or router on the same layer-2 LAN:

```bash
python scripts/agent_control/remote_pc_power_recovery.py \
  --config runtime/agent-control/remote-pc-power-recovery.local.json \
  --send-wol \
  --wait-seconds 180 \
  --json
```

## Acceptance Test

1. Confirm `ethtool <interface>` reports `Wake-on: g`.
2. Confirm `loginctl show-user <user> -p Linger` reports `yes`.
3. Shut down the remote PC cleanly.
4. Confirm `aistudio-rtc-wake-refresh.timer` is active and `wakealarm` contains
   a future epoch timestamp.
5. Shut down once and confirm RTC powers the PC back on. If same-L2 WOL is also
   configured, test that path separately.
6. Confirm SSH, the reverse relay, limit collector and project scheduler timers
   return without an interactive login.
7. Record boot and recovery timestamps in durable automation evidence.

Do not mark power recovery complete based only on generated units, a future RTC
timestamp or a magic packet sent while the PC is already online. A real
shutdown-to-boot cycle is mandatory.
