# Architecture

**Role of this document:** architecture and platform review for a family “priority ringer” Android app. It is a contract for a future implementer, not an implementation.

**Product goal:** On Android 11+, when a configured Priority Contact calls, the device should ring as loudly as **official, supported APIs and user-granted permissions allow**. The app must never use root, ADB, hidden APIs, accessibility overlays as a ringer substitute, or manufacturer-private SDKs.

**Non-claim:** The app **cannot guarantee** that every call rings through Silent, Do Not Disturb (DND), Bedtime, or OEM “Focus” modes. Those modes are user- and OEM-controlled. The app must detect limits, log them, and explain them in the UI.

---

## 1. Reviewer verdict on requirements vs platform

| Requirement | Official support | Verdict for implementer |
|-------------|------------------|-------------------------|
| FR1 Priority contacts | ContactsContract, Room | **Implement as specified.** |
| FR2 Incoming detection + number | `TelephonyManager.ACTION_PHONE_STATE_CHANGED` + `READ_PHONE_STATE` + `READ_CALL_LOG`; `TelephonyCallback.CallStateListener` for state **without** number (API 31+) | **Implement with dual path.** Do **not** rely on `TelephonyCallback` for the number. |
| FR3 DND bypass | `ACCESS_NOTIFICATION_POLICY`, `NotificationManager.isNotificationPolicyAccessGranted()`, `setInterruptionFilter` / `setNotificationPolicy` / `AutomaticZenRule` | **Partial.** User grant required. On **apps targeting API 35+**, `setInterruptionFilter` / `setNotificationPolicy` no longer change **global** DND; they toggle an **implicit AutomaticZenRule**. Combined policy is **most-restrictive-wins**. User DND / other apps can still block ringing. |
| FR4 Vibrate → audible + volume | `AudioManager.getRingerMode` / `setRingerMode`, `STREAM_RING` volume, `isVolumeFixed()` | **Partial.** Vibrate → Normal is the realistic supported path **when not in DND silent**. Transitions that **toggle DND** need notification-policy access (N+). Fixed-volume devices: **no volume change**. Silent (`RINGER_MODE_SILENT`) is **not** the same as Vibrate and **must not** be advertised as always overridable. |
| FR5 Repeat-call escalation | App-owned timestamps + max `STREAM_RING` + full-screen `Activity` via high-priority notification `fullScreenIntent` | **Implement with caveats.** Full-screen intent is a **system privilege** (`USE_FULL_SCREEN_INTENT`); Android 14+ may require the user to allow full-screen notifications. It does **not** replace the system InCallUI. |
| FR6 Test mode | App-internal simulation | **Implement.** Simulation must **not** fake OS permissions. |
| FR7 Audit log | Room, cap 500 | **Implement.** |
| FR8 Permissions UI | Settings intents | **Implement.** Include `READ_CALL_LOG` and full-screen notifications, which the original FR8 list omitted but are required for a truthful product. |

**Optional official alternative (not default):** `CallScreeningService` + `RoleManager.ROLE_CALL_SCREENING` can observe `Call.Details` (including handle) on Android 10+. That **requires the user to set this app as the default Caller ID & spam app**, which is a poor default for a family ringer. Document it in `Permissions.md` / `DeviceCompatibility.md`; do **not** make it the primary path unless product later accepts that UX.

---

## 2. Clean architecture

Layers depend **inward**. Android framework types stay in `data` and a thin `platform` adapter layer. Domain is JVM-testable.

```
presentation/   Compose, ViewModels, Navigation, Activities
domain/         Use cases, models, repository interfaces, policy
data/           Room, Android Audio/Notification/Telephony adapters, Hilt bindings
```

Package layout (suggested):

```
com.elham.priorityringer
  app/                 Application, Hilt @HiltAndroidApp
  presentation/
    navigation/
    dashboard/
    contacts/
    addcontact/
    permissions/
    audit/
    settings/
    testmode/
    alert/             Full-screen alert Activity + Compose
  domain/
    model/
    repository/        interfaces only
    usecase/
    phone/             PhoneNumberNormalizer
    escalation/        EscalationPolicy
    capability/        CapabilityStatus (pure)
  data/
    local/             Room entities, DAOs, Database, mappers
    platform/
      audio/           AudioManagerFacade
      dnd/             DndController
      telephony/       IncomingCallMonitor
      contacts/        DeviceContactsDataSource
      permission/      PermissionStatusProvider
    repository/        implementations
    di/                Hilt modules
```

**MVVM:** each screen has a `@HiltViewModel`. Domain use cases are injected. ViewModels expose `StateFlow` UI state; one-off events via `SharedFlow` or a sealed `UiEffect`.

**Unidirectional data:** UI → ViewModel intent → use case → repository/platform → Flow back to UI.

---

## 3. Core domain models

Keep domain models independent of Room annotations.

**PriorityContact**

| Field | Type | Notes |
|-------|------|--------|
| id | Long | |
| displayName | String | |
| phoneE164OrRaw | String | Store **normalized** form used for matching; also keep `originalInput` if needed for display |
| enabled | Boolean | |
| createdAtEpochMs | Long | |

**AuditLogEntry**

| Field | Type | Notes |
|-------|------|--------|
| id | Long | |
| timestampEpochMs | Long | |
| type | enum | See FR7 + extras below |
| message | String | Human-readable, no raw secrets beyond last-4 of number if logging numbers |
| relatedContactId | Long? | |
| recoverable | Boolean | Distinguishes restriction vs crash |

**AppSettings** (single-row Room entity, id = 1)

| Field | Default | Notes |
|-------|---------|--------|
| ringtoneVolumePercent | 80 | 0–100 of `STREAM_RING` max |
| escalateAfterCalls | 2 | Window A count |
| escalateWindowMinutes | 5 | Window A |
| escalateAltAfterCalls | 3 | Window B |
| escalateAltWindowMinutes | 10 | Window B |
| autoRestoreTimeoutSeconds | 90 | Safety restore if call-end missed |
| loggingEnabled | true | If false, still persist **errors** (reviewer recommendation) |

**CallSnapshot** (in-memory, not Room)

Captured **before** any mutation:

- `ringerMode`
- `streamRingVolume`
- `interruptionFilter`
- `notificationPolicy` (if readable)
- `zenRuleId` if the app created/activated a rule
- `capturedAtEpochMs`

**IncomingCallEvent**

- normalized number
- raw number (may be empty)
- timestamp
- match result (`Miss`, `DisabledPriority`, `EnabledPriority(contact)`)

**CapabilityReport** (FR6 / dashboard)

- `notificationPolicyAccess`
- `readContacts`
- `readPhoneState`
- `readCallLog`
- `useFullScreenIntent` (API 34+)
- `postNotifications` (API 33+)
- `volumeFixed`
- `currentRingerMode`
- `currentInterruptionFilter`
- `sdkInt`
- notes: list of **documented limitations** for this device/API

---

## 4. Domain use cases (implementer must keep these small)

| Use case | Responsibility |
|----------|----------------|
| `ObservePriorityContacts` | Flow of contacts |
| `AddContactFromPicker` / `AddManualNumber` | Validate, normalize, persist |
| `ToggleContact` / `RemoveContact` | |
| `ObserveSettings` / `UpdateSettings` | Validate ranges |
| `ObserveAuditLog` | Latest 500 |
| `EvaluateIncomingCall` | Normalize → match enabled contacts |
| `ApplyPriorityRing` | Orchestrate DND attempt + ringer/volume; never throw out of process |
| `RestoreAudioAndDnd` | Idempotent restore from snapshot |
| `RecordCallForEscalation` | Sliding windows |
| `ShouldEscalate` | Configurable thresholds |
| `LogAudit` | Respect logging flag; always log errors |
| `BuildCapabilityReport` | Compose permission + device checks |
| `SimulatePriorityCall` | Runs same use cases with a fake `IncomingCallEvent` |

Orchestration of a **real** call lives in `IncomingCallCoordinator` (domain or application service in `data` that calls use cases). It must:

1. Ignore empty numbers (log `NUMBER_UNAVAILABLE`).
2. If no match, do nothing to audio/DND.
3. If match: snapshot → apply → schedule timeout restore.
4. On `IDLE`/`OFFHOOK` after ringing: restore.
5. If apply fails: log + surface via a persistent “last failure” on Dashboard.

**Concurrency:** a mutex (`Mutex` in the coordinator) so overlapping calls cannot nest snapshots incorrectly.

---

## 5. Incoming call detection (FR2) — required design

### 5.1 Primary: manifest `BroadcastReceiver`

Listen for `android.intent.action.PHONE_STATE`.

Permissions:

- `READ_PHONE_STATE` (runtime)
- `READ_CALL_LOG` (runtime) — **required** to receive `EXTRA_INCOMING_NUMBER` (Android 9+). Without it, the extra is missing/empty. This is official platform behavior, not a workaround.

Receiver rules:

- Ignore broadcasts **without** `EXTRA_INCOMING_NUMBER` when `READ_CALL_LOG` is granted (the system may send two broadcasts; one blank).
- Map `EXTRA_STATE` to ringing / offhook / idle.
- Debounce duplicate ringing events for the same number within ~1s.

### 5.2 Secondary: `TelephonyCallback.CallStateListener` (API 31+)

Register from a **started foreground service** *or* while the app process is alive (e.g. after user opens the app / after first permission grant via `IncomingCallMonitor` in `ProcessLifecycle`).

Purpose: reliable **state** (`RINGING` → `IDLE`) to drive **restore**, even when number extras are flaky.

This callback **does not include the caller number** (AOSP: number omitted on purpose).

### 5.3 Number normalization

Use `PhoneNumberUtils` (Android) in `data`, wrap in domain `PhoneNumberNormalizer`:

1. Strip separators.
2. Prefer country ISO from `TelephonyManager.networkCountryIso` / SIM ISO; fallback to device locale.
3. Compare using `PhoneNumberUtils.compare` **and** equality of E.164 if both parse.
4. Never crash on invalid input; treat as no-match + audit.

Matching is **against enabled contacts only**.

### 5.4 Process death

A manifest receiver can start a **short-lived foreground service** (`specialUse` / `phoneCall` type only if Play policy allows; otherwise `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` with Play declaration, or `dataSync` is **wrong**). Reviewer recommendation:

- `foregroundServiceType="shortService"` (API 34+) for apply/restore work lasting seconds, **or**
- `FOREGROUND_SERVICE_TYPE_PHONE_CALL` is **not** appropriate unless the app is a calling app.

**Pragmatic supported approach:** `android:foregroundServiceType="specialUse"` with a Play Console declaration, **or** do apply/restore **inside the receiver** on a `goAsync()` + coroutine with a hard timeout, plus `WorkManager` one-shot for **restore timeout** only.

Implementer must pick **one** and document it in `Permissions.md`. Hidden APIs to keep the process alive are forbidden.

---

## 6. DND (FR3) — required design

### 6.1 Permission UX

1. Manifest: `ACCESS_NOTIFICATION_POLICY`.
2. If `!isNotificationPolicyAccessGranted()`, Dashboard + Permissions screen explain **why** (temporary filter/policy changes) and deep-link `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`.
3. Reduced mode: still detect calls, still try **vibrate → normal** if `setRingerMode` succeeds **without** needing to change DND. If the OS no-ops or throws, log `DND_OR_POLICY_BLOCKED`.

### 6.2 Apply strategy (best allowed, not guaranteed)

**Target SDK note:** If the app targets **35+**, do **not** design the product around “turn DND off globally.” Instead:

1. Read `getCurrentInterruptionFilter()`.
2. If already `INTERRUPTION_FILTER_ALL`, skip DND mutation.
3. Else attempt:
   - **API < 35 / target < 35:** snapshot filter + policy; `setInterruptionFilter(INTERRUPTION_FILTER_ALL)` **or** `INTERRUPTION_FILTER_PRIORITY` with `PRIORITY_CATEGORY_CALLS` if turning DND fully off is too aggressive. Reviewer preference: **prefer PRIORITY + calls allowed** over disabling DND entirely, then document that starred/system call senders still apply.
   - **API 35+:** create/update an **explicit** `AutomaticZenRule` with `INTERRUPTION_FILTER_ALL` **or** a `ZenPolicy` that allows calls, then enable it. Expect **no effect** if the user’s manual DND is stricter.
4. Log `DND_BYPASS_ATTEMPTED` with before/after filter values.
5. If after == before and filter is still blocking, log `DND_BYPASS_INEFFECTIVE` and show in UI.

**Forbidden:** claiming success without reading the filter back.

### 6.3 Restore

Restore snapshot filter/policy/rule enabled flag. If restore fails, log error and keep a Dashboard banner until the user dismisses or restore succeeds.

---

## 7. Vibrate and volume (FR4)

Sequence after snapshot:

1. If `audioManager.isVolumeFixed()` → log `VOLUME_FIXED`; skip volume; still try ringer mode.
2. If ringer == `RINGER_MODE_VIBRATE` → `setRingerMode(RINGER_MODE_NORMAL)`.
3. If ringer == `RINGER_MODE_SILENT` → **do not silently pretend success.** Attempt `setRingerMode(NORMAL)` **only** if notification-policy access is granted (required for DND-related ringer transitions). If still silent afterward, log `SILENT_NOT_OVERRIDDEN` and tell the user Silent/DND is user-controlled.
4. `setStreamVolume(STREAM_RING, percentToIndex, 0)` (avoid UI flags that spam toasts).
5. Verify with `getRingerMode()` / `getStreamVolume()`. Mismatch → `RINGER_CHANGE_FAILED` / `VOLUME_CHANGE_FAILED`.

OEM layers (Samsung, Xiaomi, etc.) may ignore third-party ringer changes; treat as **detected failure**, not a cue to use undocumented APIs.

---

## 8. Escalation (FR5)

Store recent **matched** call timestamps per normalized number (Room table `escalation_events` or in-memory + DataStore; Room is better across process death).

Escalate if:

- count ≥ `escalateAfterCalls` in `escalateWindowMinutes`, **OR**
- count ≥ `escalateAltAfterCalls` in `escalateAltWindowMinutes`

Actions:

1. Set `STREAM_RING` to **max** (still respect `isVolumeFixed()`).
2. Post a high-priority notification with `setFullScreenIntent` to `PriorityAlertActivity` (`showWhenLocked`, `turnScreenOn`, `excludeFromRecents`).
3. Request `POST_NOTIFICATIONS` (API 33+) and `USE_FULL_SCREEN_INTENT`; on API 34+ deep-link to full-screen notification settings if denied.

The alert UI is **informational** (“Priority call from X”). It **must not** try to answer/reject the cellular call via hidden telephony APIs. Optional: `tel:` / open default dialer is unnecessary during ringing.

---

## 9. Test mode (FR6)

A debug/settings screen that:

- Builds `CapabilityReport` from real APIs.
- Shows current ringer + interruption filter (live `Flow` via callback/broadcast).
- “Simulate priority call” runs `EvaluateIncomingCall` + `ApplyPriorityRing` with a selected contact or typed number **without** a real `PHONE_STATE`.
- “Simulate escalation” injects N synthetic timestamps then runs escalate path.
- **Must** restore via the same restore path (or a visible “Restore now” button).

Never grant fake permission checkmarks.

---

## 10. Persistence (Room)

Database version 1, export schema to `app/schemas/`.

**Entities**

- `PriorityContactEntity`
- `AuditLogEntryEntity`
- `AppSettingsEntity`
- `EscalationEventEntity` (recommended; not in original list but required for FR5 across death)

**DAO rules**

- Contacts: unique index on normalized number.
- Audit: after insert, `DELETE` oldest where count > 500 (single transaction).
- Settings: `get()` always returns row id=1 (prepopulate via callback).

**Mappers** in `data`; domain never imports Room.

---

## 11. DI (Hilt)

| Module | Binds |
|--------|--------|
| `DatabaseModule` | Room DB, DAOs |
| `RepositoryModule` | `@Binds` domain repos |
| `PlatformModule` | `@ApplicationContext`, `AudioManager`, `NotificationManager`, `TelephonyManager` wrappers |
| `DispatcherModule` | `@Io`, `@Default`, `@Main` |

Use `@Singleton` for coordinators that hold snapshots.

---

## 12. UI (Compose + Material 3)

**Navigation:** single `MainActivity` + Navigation Compose. Separate `PriorityAlertActivity` for full-screen (exported=false).

Screens:

1. **Dashboard** — capability chips, last event, “priority ringing armed” if permissions sufficient, last restore failure.
2. **Priority Contacts** — list, enable switch, swipe/delete.
3. **Add Contact** — pick via `ActivityResultContracts.PickContact` **or** manual number field + validation.
4. **Permissions** — FR8 + `READ_CALL_LOG`, notifications, full-screen intent.
5. **Audit Log** — reverse chronological, filter by type optional.
6. **Settings** — sliders/fields with validation; copy that Silent is not guaranteed.
7. **Test Mode** — as FR6.

**Error handling in UI:** every `CapabilityStatus.Denied` / `Restricted` has explanation + action. Snackbar for transient errors; persistent banner for “restore failed” / “DND ineffective.”

**Theming:** Material 3 dynamic color optional; keep contrast high (family/accessibility).

---

## 13. Logging

- Timber: `DebugTree` in debug; in release, a tree that writes **errors** to audit if `loggingEnabled` or always-on error path.
- No PII in logcat beyond truncated numbers.
- FR7 events: `PRIORITY_CALL_DETECTED`, `DND_BYPASS_ATTEMPTED`, `RINGER_MODE_CHANGED`, `VOLUME_CHANGED`, `RESTORATION_COMPLETED`, `ERROR`, plus reviewer extras: `NUMBER_UNAVAILABLE`, `DND_BYPASS_INEFFECTIVE`, `SILENT_NOT_OVERRIDDEN`, `VOLUME_FIXED`.

---

## 14. Testing strategy (deliverable expectation)

**Unit (JVM):**

- Phone normalization and match (table-driven).
- Escalation windows.
- Settings validation.
- Restore idempotency (fake clock + fake audio/DND ports).
- Audit cap 500 (in-memory fake DAO).

**Define ports** `AudioPort`, `DndPort`, `Clock` in domain so unit tests never need Robolectric for policy.

**Instrumentation:**

- Room migrations/schema.
- Hilt test for repository.
- Compose UI tests for Permissions empty/granted states (fake provider).

**Do not** write tests that require real incoming PSTN calls in CI.

---

## 15. Documentation set (when implementing)

| File | Content |
|------|---------|
| `README.md` | Setup, flavors, how to grant DND access, honest limitations |
| `Architecture.md` | This file |
| `Permissions.md` | Every permission, settings intent, Play policy notes |
| `DeviceCompatibility.md` | API 30–36 matrix, API 35 zen rules, OEM caveats, volume-fixed, no silent guarantee |

---

## 16. Security and privacy

- Contacts and call numbers stay on-device (no network in v1 unless later specified).
- Exported components: none except launcher; receivers not exported unless required for `PHONE_STATE` (use `android:exported="true"` only if the system requires it for that action, with permission `READ_PHONE_STATE` on the receiver).
- Backup: consider excluding audit/contacts from auto-backup or encrypt; default: allow backup of settings only if product agrees.

---

## 17. What “done” means for a reviewer

Implementation is acceptable only if:

1. No hidden/restricted APIs, no AccessibilityService used to click DND, no reflection.
2. Every apply path **verifies** post-conditions and logs failures.
3. UI never says “always rings in DND/Silent.”
4. Missing `READ_CALL_LOG` is treated as **cannot identify caller**, not as a bug to hack around.
5. Restore is guaranteed by call-state **and** timeout.
6. Tests cover matching, escalation, and restore without a physical phone.

---

## 18. Out of scope

- Default phone/dialer replacement
- Answering/rejecting calls
- Root/Magisk modules
- OEM-specific “interrupt people” APIs
- iOS
- Cloud sync

---

# Addendum A — Implementer decisions

> Appended by the implementer. §1–18 above remain the governing contract; this
> section resolves the three points §5.4, §6.2 and §14 explicitly leave to the
> implementer, and records one place where the implementation is **stricter**
> than the contract. Nothing above is retracted.

## A.1 Decision: `targetSdk = 35`, with both DND branches implemented

§6.2 branches on target SDK and requires the implementer to pick. Decision:

- `compileSdk = 35`, `targetSdk = 35`, `minSdk = 30` (Android 11).

**Corrected.** This section originally chose `targetSdk = 34`, to retain the
legacy global-filter path on the grounds that 35+ materially weakens the one
thing the app exists to do. That was written before `ImplementationPlan.md` was
read, and it contradicts it: that document specifies "target/compile **35 or
36** with API 35 DND behavior explicitly handled", and its *Risks to schedule*
section already records the consequence and accepts it —

> **API 35 DND** may make FR3 mostly "attempt + explain" — product must accept
> that.

The reviewer weighed the same trade and took the other side. That is a product
call, so the contract wins and the target is 35.

**What that costs, stated plainly.** At 35+, `setInterruptionFilter` and
`setNotificationPolicy` no longer change global DND; they act on an *implicit*
`AutomaticZenRule`, and rules combine most-restrictive-wins. A Do Not Disturb
mode the user set themselves therefore takes precedence and **cannot be
overridden by this app**. FR3 becomes: attempt, verify by reading back, and
explain honestly when it did not work. The read-back in §6.2 is what makes that
honest rather than a silent failure, and it matters *more* on this branch, not
less.

**Both branches ship regardless.** `DndPort` has two
implementations selected at runtime from `Build.VERSION.SDK_INT` and
`applicationInfo.targetSdkVersion`:

| Branch | Condition | Mechanism |
|---|---|---|
| `LegacyFilterDndStrategy` | target < 35 | snapshot filter + policy → `setInterruptionFilter` |
| `ZenRuleDndStrategy` | target ≥ 35 | explicit `AutomaticZenRule` + `ZenPolicy` allowing calls, enable/disable |

Because the branch is selected at runtime from `applicationInfo.targetSdkVersion`,
moving between them is a one-line change in `app/build.gradle.kts`, not a
redesign — which is how the correction above was absorbed without touching any
other file. The legacy branch remains live for devices below API 35.

Both branches are subject to the §6.2 read-back rule: after applying, re-read
`getCurrentInterruptionFilter()`; if calls still cannot ring, log
`DND_BYPASS_INEFFECTIVE`.

Note the read-back tests *effectiveness*, not the filter value. `PRIORITY` — the
state the default `PRIORITY_ALLOW_CALLS` strategy deliberately aims for — lets
calls through only when the notification policy permits calls, so judging it a
failure on principle would report the default strategy's own intended success as
ineffective on every call. `InterruptionFilter.maySuppressCalls` therefore
answers only "should I attempt a bypass?"; `AndroidDndPort.callsCanRingUnder()`
answers "did it work?", where the policy is readable.

## A.2 Decision: `goAsync()` receiver + WorkManager, no foreground service

§5.4 requires picking one approach. Decision: **`goAsync()` with a hard timeout,
plus a WorkManager one-shot for the restore timeout only.**

- `PhoneStateReceiver.goAsync()` → coroutine on an app scope with a hard
  `withTimeout` well inside the broadcast window. The audio/DND mutations are a
  handful of synchronous `AudioManager` / `NotificationManager` calls and fit
  comfortably.
- Restore timeout is a `WorkManager` one-shot enqueued at apply time.
- **No** `foregroundServiceType`, **no** Play Console special-use declaration,
  **no** `FOREGROUND_SERVICE_TYPE_PHONE_CALL` (§5.4 is explicit this app is not a
  calling app).

`TelephonyCallback.CallStateListener` (§5.2) is registered only while the process
is already alive, for state-driven restore. It is never relied on to keep the
process alive.

## A.3 Stricter than contract: restore is write-ahead, not in-memory

§3 specifies `CallSnapshot` as "in-memory, not Room", and §4 adds a coordinator
`Mutex`. §17.5 separately requires restore to be "guaranteed by call-state **and**
timeout."

**These are inconsistent.** If the process dies after mutation, an in-memory
snapshot is gone and the WorkManager timeout fires with nothing to restore from —
leaving the phone off DND at raised volume indefinitely. That failure is worse
than the app not working, and it is the single most likely serious defect here.

The implementation therefore satisfies §17.5 by persisting the snapshot:

1. Capture ringer mode, `STREAM_RING` volume, interruption filter, policy.
2. **Persist as a single-row `PendingRestoreEntity` (`id = 1`) before any
   mutation.** Insert-if-absent — a second overlapping call must never overwrite
   the original pre-mutation state with already-mutated values. This subsumes the
   §4 `Mutex`, which is retained for ordering within a live process.
3. Enqueue the watchdog.
4. Only then mutate.

Three independent restore triggers, all routed through one idempotent
`RestoreAudioAndDnd`:

| Trigger | Fires when | Covers |
|---|---|---|
| Call state → `OFFHOOK` or `IDLE` | answered, or ended | normal path |
| WorkManager watchdog at `autoRestoreTimeoutSeconds` | no state change arrived | callback never came |
| Cold-start reconciliation, **only while idle** | app/receiver start | process was killed |

Three corrections to this design, each fixing a way the device could be left in
the wrong state:

**`OFFHOOK` restores too.** Restoring only on `IDLE` looked safer — why drop ring
volume mid-conversation? — but it was wrong twice over. Once a call is answered
the ringtone has stopped, so the raised volume and relaxed DND have already done
their entire job; and the ring stream is not the in-call voice stream, so
restoring it is inaudible to the conversation. Worse, *not* restoring left the
watchdog armed during the call, so any call longer than
`autoRestoreTimeoutSeconds` (90s by default) got the mid-conversation restore
anyway — at an arbitrary moment rather than a chosen one. Restoring at the
moment of answering is the earliest harmless point, and it retires the watchdog
before it can fire.

**Cold-start reconciliation must not restore during a live call.** The dangerous
sequence: the process is killed mid-ring → the next `PHONE_STATE` broadcast
restarts it → `Application.onCreate` runs → an unconditional restore puts the
phone back to vibrate and re-arms DND **while the priority call is still
ringing**, silencing the exact call the app exists to make audible. The same race
lets a cold-start restore interleave with the apply for a second call. So
reconciliation reads `TelephonyPort.currentCallState()` first and defers unless
idle. Nothing is lost: the snapshot stays on disk, the WorkManager watchdog
survived the process death, and the call-state trigger fires when the call is
actually over.

**No in-memory "did we apply anything?" flag guards restore.** Such a flag reads
`false` after a process restart even though a snapshot is pending on disk — and
would suppress precisely the restore that matters most. `RestoreAudioAndDnd` is
idempotent and cheap when nothing is pending, so it is simply always called.

**Apply and restore share one lock.** `RestoreAudioAndDnd` has its own mutex, but
that only serialises restores against each other — it does nothing to stop a
restore interleaving with an *apply*. The failure that allows: the user answers
fast, so `OFFHOOK` arrives while the `RINGING` apply is still inside its
`goAsync` window; restore reads the snapshot, puts the device back, clears the
row and cancels the watchdog, all while apply is still raising the volume. The
device ends up modified with no pending snapshot and no watchdog — the stranded
state this subsystem exists to prevent, reached by the machinery meant to
prevent it. All entry points therefore take the coordinator's mutex, and lock
ordering is always coordinator → restore.

That includes the two callers outside the call path — the WorkManager watchdog
and Test Mode's "Restore now" — which reach restore through
`IncomingCallCoordinator.restoreNow()`. The invariant is deliberately "**every**
restore holds the coordinator mutex", not "every restore except two". The
watchdog happens to be protected by timing, since it fires on the same deadline
the expiry gate uses; but that is an argument from scheduling, and scheduling
arguments stop being true when someone changes a timeout. Test Mode has no such
protection at all: *Simulate* and *Restore now* tapped together is the one race
in the app a user can trigger by hand.

**Cold-start reconciliation trusts the snapshot's own deadline, not telephony.**
`currentCallState()` fails open to `IDLE` — on a missing permission, on a read
error, and inherently on dual-SIM hardware, where `getCallState()` /
`callStateForSubscription` read the *default subscription* and can report idle
while the other SIM is ringing. Failing open is the right default (never
restoring is the worse failure), but it means the call-state check alone cannot
protect a live call. So reconciliation additionally requires the pending
snapshot to be **past its own `expiresAtEpochMs`**. During a live call the
snapshot is by definition younger than its deadline, whatever telephony claims.
This matches the trigger's actual purpose: it recovers *stale* snapshots, and a
snapshot that has not reached its own auto-restore deadline is not stale — it
belongs to a call still being handled.

**Call state is consumed with `collect`, never `collectLatest`.** `collectLatest`
cancels the previous collector body when a new value arrives, and here that body
is a restore: a `RINGING → OFFHOOK → IDLE` sequence could cancel one part-way,
after the ringer was put back but before the DND filter, leaving the device
half-restored with its snapshot already cleared.

Running restore three times is harmless; running it zero times is the only
unacceptable outcome. `CallSnapshot` remains the in-memory domain model of §3 —
this adds durable backing, it does not replace the type.

## A.4 Port naming (per §14)

§14 requires ports in domain so unit tests avoid Robolectric. Names used:

| Port | Responsibility |
|---|---|
| `AudioPort` | ringer mode, `STREAM_RING` volume, `isVolumeFixed()` |
| `DndPort` | interruption filter, policy access, apply/restore (A.1 branches) |
| `TelephonyPort` | call state registration, network/SIM ISO for normalization |
| `AlertPort` | full-screen intent + heads-up fallback |
| `SchedulerPort` | watchdog enqueue/cancel |
| `Clock` | injectable time source for escalation windows and tests |

Every mutating port method returns `Outcome<T>` (`Success` / `Failure(reason,
cause)`) rather than throwing or returning `Boolean`. `SecurityException` is
caught at the port boundary and converted to a typed `FailureReason`, which is
what the audit log and the UI both render. **A permission denial is data, not an
exception** — this is what makes §17.2 (“every apply path verifies
post-conditions and logs failures”) mechanical rather than a matter of
discipline.

## A.5 `READ_CALL_LOG` makes the app inert, not degraded

Per §5.1 and §17.4: without `READ_CALL_LOG` the `PHONE_STATE` broadcast carries
no `EXTRA_INCOMING_NUMBER`, so no caller can be identified and **no priority
ringing can ever occur**. The Permissions screen and Dashboard state this as
*inert*, not *reduced* — the distinction matters because a "reduced
functionality" message would imply the app still sometimes works. It does not.

## A.6 Documented limitation: detection timing

Recorded here because §5 does not state it and the §11/“document the limitation”
obligation applies.

`PHONE_STATE` is delivered **as ringing begins**, not before it. Raising ring
volume at that moment is audibly less effective than raising it pre-ring, since
the opening seconds of the ringtone are the ones that wake someone. The app
cannot close this gap with the primary path.

The only official API that fires *before* ringing is `CallScreeningService`
(§1 note), which is not the default here because `ROLE_CALL_SCREENING` is
exclusive and displaces the device's Caller ID & spam app. Detection is
therefore kept behind the `IncomingCallMonitor` seam (§5) so a screening-role
source can be added as an **opt-in** later without restructuring — per §1,
"unless product later accepts that UX". Not built now.

This limitation is carried into `DeviceCompatibility.md`.
