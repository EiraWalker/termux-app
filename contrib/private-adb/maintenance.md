# Same-UID maintenance API (version 1)

**Android device acceptance is pending.** Host framing/lease tests and an Android
build do not prove actual SELinux/OEM socket access or APK lifecycle behavior.

## Fixed local IPC boundary

`MaintenanceCli` uses public `LocalSocket`, not Binder/provider acquisition, hidden
framework bootstrap, TCP, VPN or a second app/coordinator process. The existing
normal `PrivateAdbService` owns an abstract AF_UNIX listener whose fixed name is
`com.termux.privateadb.maintenance.` followed by `Process.myUid()`. No name, URI,
path, UID, executable or command override is accepted. No permission, exported
component, arbitrary IPC method or Manifest entry is added.

Both ends require kernel `SO_PEERCRED` UID to equal their exact actual app UID.
Root and shell are not accepted as owners. Same UID remains a trust boundary, not
isolation from other code already running with Termux's privileges. SELinux type
and MLS compatibility are separate requirements: UID equality alone does not
prove a socket connection is permitted.

The server is created only during the existing guardian service's `onCreate`,
after its normal FGS notification. A bind conflict fails closed without takeover
or unlink. The app owns its listening FD even if bind/listen fails. Service destroy
shuts down the listener and accepted connection, then closes only owned FDs.
The CLI never starts a service, registers an Android application thread, creates
a coordinator, writes a lease itself or repairs flags. The normal service must
already be running; unavailable/absent endpoint is `unsupported`, with no fallback.

The abandoned `ActivityThread.systemMain()`/ContentProvider route was removed:
on pinned AOSP Android 15 it uses `attach(true,0)` rather than registering a normal
application caller. Provider acquisition then sends an unknown ApplicationThread
to AMS, which rejects the missing ProcessRecord. Correct attribution cannot repair
that prerequisite. Synthetic transport fixtures were not proof of real invocation.

## Input and invocation

Run under the installed app's **actual UID**, using the current installed APK on
CLASSPATH and a single fixed verb. JSON is read only from stdin followed by EOF;
stdout is one JSON line. Never put a token in arguments, shell tracing or logs.

```sh
sh contrib/private-adb/maintenance request < private-request.json
sh contrib/private-adb/maintenance status < private-token.json
sh contrib/private-adb/maintenance release < private-token.json
```

These are operator-controlled stdin redirections, not API path fields. The helper
uses the fixed `/system/bin/pm path com.termux` package query, then fixed
`/system/bin/app_process /system/bin com.termux.app.privateadb.MaintenanceCli`.
It accepts no APK/package/name override, fails closed for split/multiline or
non-`/data/app/.../base.apk` results, and never uses `am`/`content` fallback.
The package query and app_process execution themselves still require device
acceptance; an old APK missing the class is unsupported, not a lease bypass.

One strict UTF-8 flat JSON object, maximum **2048 bytes**, including whitespace.
The CLI requires EOF; the host must close stdin and enforce its process timeout.
Request fields are **exactly**:

| Field | Type and value |
| --- | --- |
| `version` | JSON integer `1` |
| `token` | 64 lowercase hex, freshly generated once per transaction |
| `boot` | Current lowercase boot UUID, `8-4-4-4-12` |
| `deadlineElapsedMs` | Positive integer absolute boot-monotonic elapsedRealtime deadline |
| `profileSha256` | 64 lowercase hex, bound to verified host profile |
| `releaseSha256` | 64 lowercase hex, bound to verified release |
| `baselineVersion` | Positive integer actual installed package version on first reservation |
| `targetVersion` | Positive integer not smaller than baseline |

Status/release contain **only** `version: 1` and the same exact `token`.
Duplicate decoded keys, unknown/missing fields, coercions, nested values, invalid
UTF-8, floats/exponents, overflow, comments and trailing documents are rejected.
The app verifies its own boot/time/actual UID/version, not host-supplied identity
substitutes. The absolute deadline and all bindings are immutable across retries.

## Socket framing and bounds

This framing is internal to the fixed CLI; host consumers keep the JSON interface.
All integers are big-endian. One request and one response per connection:

- Request: `uint32 0x544d5131` (TMQ1), `uint8` verb (`1=request`, `2=status`,
  `3=release`), `uint32` payload length in `1..2048`, exact UTF-8 JSON, then EOF.
- Response: `uint32 0x544d5231` (TMR1), `uint32` length in `1..4096`, exact UTF-8
  coordinator JSON, then EOF. The CLI validates and emits a single JSON object.

Socket send/receive timeouts are 3000 ms. An accepted connection has a 5000 ms
absolute shutdown watchdog; the client bounds the entire connect/exchange task
at 6000 ms, shuts down/closes only its own socket and exits. AOSP Android 15's
`LocalSocket.connect(address, timeout)` is unsupported, so it is deliberately
not used. Its one-argument connect is covered by the bounded client task instead.
Scheduler freezing and a stalled coordinator Binder/file operation are not hard
real-time guarantees. A timed-out request may already be durable pending: reconcile
the exact token; never generate a new token or extend a deadline to replay it.

| Exit | Meaning |
| --- | --- |
| `0` | Coordinator JSON returned; **inspect phase**, not unconditional success |
| `1` | Failed/uncertain exchange or malformed response; fixed `failed` error |
| `2` | Invalid argument/stdin; fixed `invalid_request` error |
| `3` | Missing/unavailable server, rejected owner/peer or unsupported helper; fixed `unsupported` error |

Errors are `{"version":1,"error":"<fixed-code>"}` with no exceptions or input
excerpts. `@Keep` preserves the fixed CLI class/main name; minified release runtime
still needs its own verification.

## Guardian lease and acknowledgement policy

The app keeps `private_adb_install_maintenance` separately from user preferences
and the native-window lease. It binds the **actual app UID**, token, boot,
absolute elapsed-time deadline, profile/release hashes and baseline/target package
versions. On a new reservation, the installed version must equal the baseline;
a surviving record permits only baseline or target after APK replacement. Hashes
are opaque transaction bindings: **the host must verify actual profile and release
bytes**; the app does not authenticate arbitrary host files.

The first request is durable `pending`, not permission to mutate. Only the existing
single guardian worker can publish `active`, after the current recovery returns,
its own native window closes and reads back unowned, and both the wrapper's fixed
`start.pending` path and the app's own dispatch-intent record are clear. A persisted
`active` record does not constitute an ack after process/APK reconstruction: a
live worker must re-ack it. Destroying the worker invalidates its in-memory ack.
A request during discovery cancels undispatched recovery; an already dispatched
wrapper is allowed to return under the original bounded recovery deadline. Timeout,
process death or unknown dispatch leaves maintenance **blocked**, never ready.

The app persists its own `guardianDispatchUnconfirmed` intent before spawning the
fixed manager, closing the gap before the wrapper can create `start.pending`.
Only a confirmed exit (or positively different boot for the app-owned intent)
clears this intent. The API never deletes unknown wrapper `start.pending` files,
replays commands or adopts a new PID. A same-boot unknown app dispatch intentionally
requires a separately audited resolution or reboot; it is not automatically cleared
just because the wrapper latch happens to be absent.

The deadline must be in the future and no more than **600000 ms** from the app's
clock on the first request. Retries must retain the identical absolute deadline
and bindings. Same token/same payload is idempotent; changed payload is rejected.
A different token cannot steal a live lease. Released/expired/revoked records do
not resume, renew or replay a transaction. Used tokens are remembered for the boot
(up to 256 reservations, then fail closed) to prevent replay after intervening
transactions; use a fresh token, never clear user flags to make a retry work.

Stop and safety pause always preempt the lease. Their persistent flags and the
failure budget are not reset by request, status, release or expiry. While a lease
is pending/active the FGS remains on its existing 15-second monitor schedule and
starts no new recovery. Release/expiry first cleans up the guardian-owned window,
then only the original enabled/Stop/safety policy applies. Existing user-owned
native debugging windows and running private shell daemons are not stopped or
adopted. Force-stop, freezing, Doze and OEM scheduling can delay this best-effort
cleanup; there is no new permission or guaranteed real-time expiry callback.

Every response has integer `version: 1` and String `phase`. When the token owns a
record it also has `token`, `boot`, `deadlineElapsedMs`, `profileSha256`,
`releaseSha256`, `baselineVersion`, `targetVersion` and integer `ownerUid`.
**Only `phase: "active"` is an ack.** `pending`, `cleanup_pending`,
`blocked_unknown_dispatch`, `released`, `expired`, `revoked`, `version_mismatch`,
`store_error` and all other phases are not authority to mutate. Status never
starts a service; request/release may wake the existing supported FGS path.

Before **every** host mutation, query status and compare every binding with the
unchanged checkpoint, actual UID, boot, deadline and installed version; retain
independent Stop/safety/native-window/wrapper/heartbeat/process-identity gates.
Historical guardian `phase: healthy` is not maintenance evidence. The API does
not relabel the guardian healthy during maintenance. There is no `capabilities`
verb in v1; a missing CLI/server is `unsupported`, not a reason to bypass the
lease. Bootstrapping an older APK without this API is a separate explicit operation.

## Verification scope and primary sources

Host tests exercise production strict parsing, bounded framing, exact peer UID
guards with synthetic credentials, timeout/cancellation, actual coordinator store,
Settings/native ownership policy and the real serial guardian executor under
Robolectric. The Android SDK compiles the actual socket/service/CLI wiring.
These results are **not** real AF_UNIX/SO_PEERCRED or Android SELinux acceptance.
Device gates include peer credentials on both ends, bind conflict/no takeover,
service-owned close, missing server, malformed/slow connections, same-token
request/status/release, package replacement and expiry/Stop/safety priority.
No device/ADB/install or commit was performed for this implementation.

Pinned primary-source basis is AOSP `android-15.0.0_r1`:

- `frameworks/base/core/java/android/net/LocalSocket.java`: public socket,
  credentials, half-close and unsupported two-argument connect.
- `frameworks/base/core/java/android/net/LocalSocketImpl.java`: SO_TIMEOUT applies
  to both send and receive; close alone does not establish a blocked-I/O wakeup.
- `frameworks/base/core/java/android/net/LocalServerSocket.java`: externally
  supplied FD is not owned by its server wrapper.
- `system/sepolicy/private/domain.te`: self-type stream sockets can connect;
  `system/sepolicy/private/mls`: stream connection requires equivalent levels
  unless a trusted-subject exception applies. No policy changes are made here.
- `ActivityThread.java` and `ContentProviderHelper.java` explain why the removed
  unmanaged-provider bootstrap cannot be accepted as an invocation path.
