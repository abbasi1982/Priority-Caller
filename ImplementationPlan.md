# Implementation Plan (Reviewer)

This is a **gated plan** for a future developer. Do not start coding a feature until the **API check** for that feature is ticked in the PR description.

Reviewer stance: prefer **honest degradation** over clever bypasses.

Suggested app id: `com.elham.priorityringer`  
Min SDK 30 (Android 11), target/compile **35 or 36** with API 35 DND behavior explicitly handled.

---

## Phase 0 — Project skeleton (no telephony yet)

**API check:** none.

1. Android Studio / Gradle Kotlin DSL app module + `:app`.
2. Plugins: Android, Kotlin, Compose, KSP, Hilt, ktlint optional.
3. Dependencies: Compose BOM, Material3, Navigation, Lifecycle, Hilt, Room, Timber, coroutines.
4. `applicationId`, `minSdk 30`, `compileSdk` current stable.
5. Empty `MainActivity` + Hilt `Application`.
6. Export Room schema path.
7. Placeholder tests (`ExampleUnitTest` replaced in Phase 2).

**Exit:** app installs, Timber logs in debug.

---

## Phase 1 — Data layer + settings + audit (FR1 store, FR7, Settings persistence)

**API check:** Room only.

1. Entities: `PriorityContact`, `AuditLogEntry`, `AppSettings`, `EscalationEvent`.
2. DAOs + `AppDatabase` + prepopulate settings row.
3. Audit insert + prune to **500**.
4. Repositories + mappers.
5. Domain models and settings validation (percent 0–100, windows ≥ 1, timeout ≥ 15s recommended).

**Tests:** DAO prune, settings defaults, contact unique number.

**Exit:** instrumented Room tests green.

---

## Phase 2 — Domain policy (FR2 match, FR5 math) without Android

**API check:** `PhoneNumberUtils` behavior documented; wrap behind interface for JVM tests using a fake normalizer **and** an Android instrumented test for `PhoneNumberUtils.compare`.

1. `PhoneNumberNormalizer`.
2. `MatchPriorityContactUseCase`.
3. `EscalationPolicy` with both windows.
4. Ports: `AudioPort`, `DndPort`, `Clock`, `AuditPort`.
5. `ApplyPriorityRingUseCase` + `RestoreUseCase` against fakes.
6. Snapshot mutex tests (second apply while first active does not clobber original snapshot).

**Exit:** unit tests table-driven (US/international numbers, disabled contacts, empty number).

---

## Phase 3 — Permissions screen (FR8) + Dashboard capability

**API check:**

| Permission | Manifest | Runtime | Settings intent |
|------------|----------|---------|-----------------|
| READ_CONTACTS | yes | yes | app details if permanently denied |
| READ_PHONE_STATE | yes | yes | same |
| READ_CALL_LOG | yes | yes | **required for caller id** |
| POST_NOTIFICATIONS | API 33+ | yes | |
| ACCESS_NOTIFICATION_POLICY | yes | **special** | `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` |
| USE_FULL_SCREEN_INTENT | yes | API 34+ special | `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` |
| FOREGROUND_SERVICE / types | if used | | |

1. `PermissionStatusProvider` reading real APIs.
2. Permissions Compose screen: status + one-tap navigation.
3. Dashboard: “armed” only if phone state **and** call log granted; DND shown as **optional enhancement**.
4. Copy: Call Log is needed to know **who** is calling.

**Tests:** fake provider → UI states.

**Exit:** reviewer reads copy; no “fully protected” language.

---

## Phase 4 — Contacts UI (FR1)

**API check:** `ContactsContract` via `PickContact`; query `DISPLAY_NAME` + `HAS_PHONE_NUMBER` / phone column. No hidden contacts APIs.

1. Contacts list + enable + delete.
2. Add: picker **and** manual E.164-ish field.
3. Handle contacts with multiple numbers (picker should let user choose **one** number, or a follow-up dialog).

**Exit:** CRUD works offline; empty number rejected.

---

## Phase 5 — Audio + DND adapters (FR3, FR4)

**API check (must paste links in PR):**

- [AudioManager.setRingerMode](https://developer.android.com/reference/android/media/AudioManager#setRingerMode(int)) — DND access required when change would toggle DND (N+); no-op if `isVolumeFixed()`.
- [NotificationManager.setInterruptionFilter](https://developer.android.com/reference/android/app/NotificationManager#setInterruptionFilter(int)) — target 35+ → implicit `AutomaticZenRule`, **not** global DND.
- [AutomaticZenRule](https://developer.android.com/reference/android/app/AutomaticZenRule)

Implementation order:

1. `AudioManagerFacade`: get/set ringer, get/set `STREAM_RING`, `isVolumeFixed`, verify-after-write.
2. `DndController`:
   - SDK / target-aware branch.
   - Snapshot + apply + restore.
   - Always log before/after filter.
3. Wire `ApplyPriorityRingUseCase` to real ports.
4. Safety `WorkManager` or coroutine timeout restore (`autoRestoreTimeoutSeconds`).

**Manual test matrix (developer device):**

| Start state | Policy access | Expected |
|-------------|----------------|----------|
| Vibrate, DND off | no | Loud ring (if OEM allows) |
| Vibrate, DND off | yes | Loud ring |
| Vibrate, DND on | no | Explain; likely still vibrate |
| Vibrate, DND on | yes | Attempt; **verify** audible; if not, `DND_BYPASS_INEFFECTIVE` |
| Silent | yes/no | Must show limitation if still silent |
| Volume fixed (tablet?) | any | Volume unchanged, logged |

**Forbidden if tests fail:** reflection, `WRITE_SECURE_SETTINGS`, accessibility.

---

## Phase 6 — Incoming call path (FR2)

**API check:**

- `ACTION_PHONE_STATE_CHANGED` extras: `EXTRA_STATE`, `EXTRA_INCOMING_NUMBER` only with `READ_CALL_LOG`.
- `TelephonyCallback.CallStateListener` — state only, API 31+.
- Do **not** use deprecated `PhoneStateListener` as the long-term number source on API 31+ (deprecated; number path is the broadcast).

1. Manifest receiver + permission on receiver.
2. `IncomingCallCoordinator` `@Singleton`.
3. Register `TelephonyCallback` from Application / monitor when process starts (optional restore reliability).
4. Foreground/short service **only if** `goAsync` is insufficient; document Play `specialUse`.
5. Audit events on detect/apply/restore.

**Manual:** call from a second phone to an enabled contact in Vibrate.

**Exit:** unmatched numbers never change ringer.

---

## Phase 7 — Escalation + full-screen (FR5)

**API check:** Notification full-screen intents; API 34 `canUseFullScreenIntent()`.

1. Persist escalation events on **matched** ringing only.
2. Escalation use case.
3. `PriorityAlertActivity` + notification channel `IMPORTANCE_HIGH`.
4. Permissions screen entry for full-screen.

**Exit:** test mode can trigger full-screen without a real call.

---

## Phase 8 — Test Mode screen (FR6)

1. Live ringer + filter.
2. Simulate apply + restore.
3. Simulate escalation.
4. Dump `CapabilityReport` (read-only).

**Exit:** simulate never leaves the phone stuck in Normal; restore button always visible after simulate.

---

## Phase 9 — Remaining UI polish

1. Settings screen (all knobs).
2. Audit log screen.
3. Navigation graph complete.
4. Empty/error states.

---

## Phase 10 — Docs + tests wrap-up

1. `README.md`, `Permissions.md`, `DeviceCompatibility.md` (Architecture.md already exists).
2. `DeviceCompatibility.md` **must** include:
   - Android 15+ zen rule / most-restrictive-wins
   - Silent ≠ Vibrate
   - OEM variance
   - Dual-SIM: default subscription vs extra SIM (test both; document if number missing on secondary SIM)
3. Unit + instrumentation as in Architecture §14.
4. ProGuard/R8 keep rules for Hilt only; no hiding of APIs.

---

## Suggested implementation order (dependency)

```
Phase 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10
                 ↑         ↑
           UI can ship     Real calls last
           with fakes
```

Do **not** implement FR6 simulate-apply before Phase 5 ports exist.

---

## Reviewer checklist (every PR)

- [ ] Official API cited
- [ ] Fallback + audit event if API no-ops
- [ ] UI copy does not over-promise
- [ ] Snapshot + restore + timeout
- [ ] Tests for the policy change
- [ ] No new permission without Permissions screen row

---

## Risks to schedule

1. **API 35 DND** may make FR3 mostly “attempt + explain” — product must accept that.
2. **READ_CALL_LOG** Play declaration (sensitive permission) — prepare a privacy policy and use-case text **before** Play upload; not needed for local sideload.
3. **OEM ringer** may ignore `setRingerMode` — no engineering fix within constraints.
4. **Process death mid-call** — timeout restore is mandatory.

---

## What the reviewer will reject

- AccessibilityService to toggle DND
- `dumpsys` / ADB from the app
- `@hide` / reflection on `AudioService`
- `CallScreeningService` silently requesting default spam-app role without UX
- “Always rings in DND” in README or UI
- Skipping `READ_CALL_LOG` and scraping numbers another way

---

## Next step

Developer starts **Phase 0** only after acknowledging this plan and Architecture.md. Reviewer does not write production Kotlin in this pass.
