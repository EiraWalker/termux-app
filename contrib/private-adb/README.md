# Private ADB recovery and the `tablet` CLI

This is an opt-in extension inside the existing `com.termux` package, not a
root solution or a new app. It monitors an app-private heartbeat and, when
needed, briefly enables native Wireless debugging to bootstrap an already
configured private shell manager. The private daemon core is maintained at
<https://github.com/EiraWalker/private-adbd>.

## Published source and prerequisites

- `app/src/main/java/com/termux/app/privateadb/`: foreground recovery service,
  bounded NSD candidate handling, native-window ownership and policy.
- `app/src/main/AndroidManifest.xml` and `TermuxActivity.java`: private component
  declarations and lifecycle/explicit-confirmation hooks. Existing K390 input,
  terminal-session and `TermuxService` code are not replaced.
- `app/src/test/java/com/termux/app/privateadb/`: config, lease and policy tests.
- `tablet`: public command router; `tablet-guardian`: same-app control helper.

These two shell files are **not a complete, ready-to-deploy distribution**.
They require the matching Termux extension, the existing Termux `am` wrapper,
POSIX shell/core utilities, and a separately provisioned, device-appropriate
`$PREFIX/bin/tablet-daemon` backend and private shell manager. That backend must
validate local device identity and authenticated UID 2000, implement its
start/status/restart/stop/pair/ensure contract, and publish the compatible
heartbeat safely. The previously deployed device-specific bridge and manager
scripts are deliberately not included. Changing only the app config does not
make an existing device-bound backend portable.

The Android SDK, APKs, signing material, local pairing keys, accounts, network
policy, deployment tools and device-specific configuration are not included.
The extension requires an explicitly authorized
`android.permission.WRITE_SECURE_SETTINGS` (WSS) grant to control the native
Wireless debugging setting. WSS does not grant shell UID 2000: that requires
successful authentication through the already paired local native TLS channel.
No permission grant, pairing or backend provisioning is performed by these
scripts. Keep all credentials and actual device identifiers outside this tree.

## Install the CLI into an already configured Termux

After reviewing and provisioning the dependencies above, run from this source
directory **inside the matching Termux app**:

```sh
install -m 700 tablet tablet-guardian "$PREFIX/bin/"
tablet help
tablet status
```

The router resolves its backends beside itself. The guardian helper is fixed to
`com.termux`, Android user 0 and the owning app UID; it is not a multi-user or
arbitrary-package installer. `status` prints last-recorded app state and a local
heartbeat snapshot, not a fresh connectivity or remote-authentication probe.

App configuration is read from
`$HOME/.config/tablet-daemon/autostart.json`. Missing, disabled or invalid config
does not enable recovery. The code requires numeric `version: 1`, boolean
`enabled: true`, a verified `expectedSerial`, and these exact fixed values:

- `managerPath`: `/data/data/com.termux/files/usr/bin/tablet-daemon`
- `heartbeatPath`: `/data/data/com.termux/files/home/.config/tablet-daemon/health.json`

Provision this privately only after verifying the backend identity, pairing,
permissions and real heartbeat writer. The deployed heartbeat contract uses an
app-owned 0700 config directory and a safely written 0600, non-symlink heartbeat
with `version`, `boot_id`, `uptime_ms`, `watch_pid`, `child_pid` and `state`.
The app checks boot identity, freshness and positive PIDs; a healthy heartbeat
alone is not proof of authenticated remote access.

## Commands and stop semantics

```text
tablet start                  Request configured guardian startup
tablet status                 Read guardian/heartbeat snapshots
tablet stop                   Persistently disable automatic recovery only
tablet enable                 Open the explicit on-device re-enable confirmation
tablet daemon help            Show the separately installed backend's commands
tablet daemon start [port]    Invoke backend maintenance over the local native channel
tablet daemon status [port]
tablet daemon restart [port]
tablet daemon stop [port]
tablet daemon ensure
tablet pair <pairing-port>    Pair through the existing backend
```

The default start/status/stop/enable commands target the **guardian**, not the
private daemon. `tablet stop` leaves the current private shell daemon running.
Conversely, stopping the daemon while the guardian is enabled can cause it to
be started again. Stop the guardian first when intentionally stopping both.
Ordinary `start` does not clear persistent Stop or safety-pause state; use
`enable` and confirm on the device. App re-enable does not repair or clear the
backend's own failure latch. Backend maintenance requires a usable native local
channel; pairing ports and connect ports are different.

## Security and lifecycle limits

The normal recovery budget is 45 seconds across NSD discovery and bootstrap;
owned native TLS is closed after successful recovery and on ordinary cleanup.
This is a best-effort business deadline, **not** a guaranteed cleanup bound
under force-stop, process freezing, Binder stalls or OEM scheduling restrictions.
An already enabled native window is not adopted. Native TLS may listen on the
LAN, not only localhost, while enabled; existing native pairing trust still
applies. WSS and the app-private heartbeat are not isolation from other code
running as the same Termux UID.

The manifest lists `USER_UNLOCKED`, but Android 15 delivers that action only to
dynamically registered receivers. That manifest line is not an independent
unlock trigger: `BOOT_COMPLETED` is the actual static boot entry. No direct-boot
or pre-first-unlock recovery guarantee is made.

## Validation scope

Publication reuses hash-bound historical evidence for the deployed v1004
candidate: 186 JUnit tests (177 retained plus 9 new NSD-policy tests) passed,
and eight host CLI-routing tests passed. Controlled device checks covered
same-package deployment, foreground-service reconstruction after SIGKILL,
automatic NSD rediscovery and shell/watch reconstruction, CLI routing and
unchanged existing backends. Those tests were **not rerun for publication**;
pure policy tests do not establish Android radio/callback behavior.

A reboot produced a new boot identity, healthy foreground service and an
observed authenticated UID 2000, but the tailnet service was temporarily offline
and it is unknown whether the app was opened manually. Fully unattended boot
recovery is therefore **not accepted as verified**. Overnight operation, Doze,
network switching and battery impact were not newly validated. No APK, private
operational logs, raw test receipts or device data are published here.

This increment, including these shell scripts, follows the repository's
GPLv3-only license; see `../../LICENSE.md`.
