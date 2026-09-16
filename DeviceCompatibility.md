# Device Compatibility

What Priority Caller can and cannot do across Android versions, permission
states, and manufacturer software. `minSdk 30` (Android 11), `compileSdk 35`,
`targetSdk 35`.

The short version: the app's behaviour is determined by three independent things
— the Android version, what the user has granted, and what the specific
manufacturer's software allows — and only the first two are predictable. The last
section of this document explains why Test Mode on the actual phone is the only
authoritative answer.

---

## Android version matrix

| API | Android | Detection | DND handling | Ringer / volume | Alarm-stream fallback | Escalation alert |
|---|---|---|---|---|---|---|
| 30 | 11 (min) | `PHONE_STATE` broadcast; deprecated `PhoneStateListener` for call state | Legacy global `setInterruptionFilter` | `setRingerMode` + `setStreamVolume` | `USAGE_ALARM` `MediaPlayer` if ringer path is verified inaudible; not a FGS | Full-screen intent granted at install; no `POST_NOTIFICATIONS` needed |
| 31–32 | 12 / 12L | `PHONE_STATE`; `TelephonyCallback.CallStateListener` (no number, by design) | Legacy global filter | unchanged | unchanged | unchanged |
| 33 | 13 | unchanged | Legacy global filter | unchanged | unchanged | **`POST_NOTIFICATIONS` runtime permission now required** |
| 34 | 14 | unchanged | Legacy global filter | unchanged | unchanged | **Full-screen intent gated behind `canUseFullScreenIntent()`**; falls back to heads-up |
| 35 | 15 | unchanged | **As shipped (`targetSdk 35`): explicit `AutomaticZenRule`, combined most-restrictive-wins.** A stricter user-set DND wins and cannot be overridden | unchanged | Same last-resort path. Total Silence / alarm-disallowed policy still mute `STREAM_ALARM` | unchanged |
| 36 | 16 | **Never compiled against or tested.** `compileSdk` is 35. Code falls into the `>= 35` branches. Nothing is version-specific to 36, and no claim is made about it. | as 35 | as 35 | as 35 | as 35 |

`AndroidTelephonyPort` branches at API 31: `registerTelephonyCallback` on 31+,
the deprecated `PhoneStateListener.listen` on API 30 only. Either way the
callback carries **no caller number** — AOSP omits it deliberately — so it can
drive restore but can never identify a caller. Identification always comes from
the broadcast, which needs `READ_CALL_LOG`.

---

## The Android 15 / targetSdk 35 zen-rule change

This is the single most consequential platform change for this app, and it is
gated on **what the app targets**, not on what the device runs.

Before Android 15, `setInterruptionFilter` and `setNotificationPolicy` changed
the device's global DND state. From Android 15, for apps targeting API 35+, those
same calls no longer touch global DND — they drive an *implicit*
`AutomaticZenRule` owned by the calling app, and all active zen rules are
combined **most-restrictive-wins**.

The practical consequence: **if the user has set a stricter Do Not Disturb
themselves, it wins, and no app can override it.** That is not a limitation of
this app's implementation. It is the designed behaviour of the platform, and
there is no supported way around it.

### What this app actually does about it

`AndroidDndPort` picks its strategy at runtime from two conditions:

```kotlin
Build.VERSION.SDK_INT >= 35 && context.applicationInfo.targetSdkVersion >= 35
```

| Both true | Branch | Mechanism |
|---|---|---|
| no | `applyViaGlobalFilter` | snapshot filter → `setInterruptionFilter` (legacy global path) |
| yes | `applyViaZenRule` | explicit `AutomaticZenRule` with a `ZenPolicy` allowing calls and repeat callers, activated via `setAutomaticZenRuleState` |

**As shipped, `targetSdk = 35`, so on an Android 15 device the app takes the zen-rule
path.** That is recorded in `Architecture.md` § A.1 and commented in
`app/build.gradle.kts`, and it follows `ImplementationPlan.md`, which specifies
"target/compile 35 or 36 with API 35 DND behavior explicitly handled".

Be clear about what it costs. Under most-restrictive-wins, **a Do Not Disturb
mode the user set themselves takes precedence over anything this app asks for.**
On those devices FR3 degrades to *attempt and explain*: the app tries, reads the
filter back, and reports `DND_BYPASS_INEFFECTIVE` when calls still cannot ring.
That is not a defect to be worked around — it is the documented platform
behaviour, and the project's own plan accepts it in as many words: *"API 35 DND
may make FR3 mostly 'attempt + explain' — product must accept that."*

The legacy global-filter branch has not been removed. It remains live for devices
below API 35, which is the majority of the `minSdk 30` range this app supports,
and selection is made at runtime rather than at build time.

No read-back improvement can fix the most-restrictive-wins case — it is a
platform decision, not a detection failure. What the read-back *does* buy is that
the user is told, instead of being left to discover it when a call is missed.
Which brings us to the rule both branches obey.

### Both branches read the filter back

After applying, `AndroidDndPort` re-reads `getCurrentInterruptionFilter()` and
asks whether a call could actually ring under the resulting filter — consulting
the notification policy where the filter alone is not decisive (see
["Should I try?" and "did it work?"](#should-i-try-and-did-it-work-are-different-questions)
below). If it could not, the result is `FailureReason.DND_BYPASS_INEFFECTIVE`,
not success, and the audit log records:

> Do Not Disturb is still suppressing calls. On Android 15+ a stricter Do Not
> Disturb set by you or the system takes precedence and cannot be overridden by
> an app.

"Applied without throwing an exception" is not treated as evidence that anything
changed. That rule is the reason this document can be honest about a case the app
cannot win.

### DND strategy setting

The default strategy is `PRIORITY_ALLOW_CALLS` — ask for calls to come through
rather than switching DND off entirely, as the less invasive change to make to
someone else's phone. On the legacy branch there is a caveat: that strategy is
only used when the user's existing notification policy *already* permits calls.
If it does not, using it would mean overwriting the global
`NotificationManager.Policy`, which is not captured in the restore snapshot and
therefore could not be reliably put back. Faced with a choice between an
unrestorable mutation of a global setting and a restorable one, the app falls
back to `INTERRUPTION_FILTER_ALL` for the duration of the call and records the
before/after values in the audit log.

### "Should I try?" and "did it work?" are different questions

The filter value alone cannot tell you whether calls will ring.
`INTERRUPTION_FILTER_PRIORITY` — which is exactly what the default strategy aims
for — lets calls through only if the user's notification *policy* includes the
calls category. The code keeps these two questions apart deliberately:

- **`InterruptionFilter.maySuppressCalls`** (`Models.kt`) is the *conservative*
  "should I attempt a bypass?" test. It counts `ALARMS`, `NONE`, `PRIORITY` and
  `UNKNOWN` as possibly-suppressing, because the enum cannot see the policy and
  the safe assumption is to try rather than to assume a bypass is unnecessary.
  It is explicitly **not** a verdict on success.
- **`AndroidDndPort.callsCanRingUnder(filter)`** is the effectiveness test used
  for the read-back. It reads the actual notification policy, which
  `maySuppressCalls` cannot.

| Filter after the attempt | `callsCanRingUnder` | Reported as |
|---|---|---|
| `ALL` | true | Success |
| `PRIORITY`, policy includes calls | true | Success |
| `PRIORITY`, policy excludes calls | false | `DND_BYPASS_INEFFECTIVE` |
| `ALARMS` / `NONE` | false | `DND_BYPASS_INEFFECTIVE` |
| `UNKNOWN` (filter could not be read) | false | `DND_BYPASS_INEFFECTIVE` |

So the default `PRIORITY_ALLOW_CALLS` strategy reports success when the filter is
`PRIORITY` **and** the policy actually permits calls, and reports
`DND_BYPASS_INEFFECTIVE` only when calls genuinely still cannot ring. Where the
filter cannot be read at all, the app declines to claim success rather than
guessing in its own favour.

Two honest caveats remain:

- **`PRIORITY` with a policy that excludes calls is correctly reported as
  ineffective** — and on the legacy branch the app will not have gone there in
  the first place. `applyViaGlobalFilter` chooses `INTERRUPTION_FILTER_PRIORITY`
  only when `policyAllowsCalls()` is already true; otherwise it falls back to
  `INTERRUPTION_FILTER_ALL` rather than overwriting the user's global
  `NotificationManager.Policy`, which is not captured in `CallSnapshot` and so
  could not be reliably restored afterwards. Trading a slightly more invasive but
  restorable change for an unrestorable one is the deliberate choice.
- **Android 15+ most-restrictive-wins is still genuinely unwinnable.** No
  read-back improvement changes that: for apps targeting 35+, a stricter Do Not
  Disturb the user set themselves takes precedence, the app's explicit zen rule
  loses, and `DND_BYPASS_INEFFECTIVE` is the correct and final answer.

---

## The Android 14 full-screen-intent gate

Through Android 13, `USE_FULL_SCREEN_INTENT` was a normal install-time
permission. Android 14 (API 34) stopped auto-granting it to apps that are not
calling or alarm apps and introduced a per-app user allowance.

The app does not try to argue about whether it qualifies. It calls
`NotificationManager.canUseFullScreenIntent()` and behaves accordingly:

| `canUseFullScreenIntent()` | Behaviour | Audit event |
|---|---|---|
| true (or API < 34) | Notification built **with** `setFullScreenIntent`; `PriorityAlertActivity` takes over the screen, shows over the lock screen and turns the screen on | `FULL_SCREEN_ALERT_SHOWN` |
| false | The same notification built **without** the full-screen intent — a high-importance heads-up banner | `FULL_SCREEN_ALERT_FALLBACK` |

This affects only the escalation alert. Ringer mode and volume changes are
unaffected, and escalation still raises the volume to maximum. Deep link to the
allowance screen: `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` — see
[`Permissions.md`](Permissions.md).

---

## Permission state matrix

Readiness is computed in `CapabilityReport.readiness`.

| `READ_PHONE_STATE` | `READ_CALL_LOG` | Notification policy access | Readiness | What actually happens on an incoming call |
|---|---|---|---|---|
| denied | any | any | `INERT` | The app is never told a call arrived. Nothing happens. |
| granted | denied | any | `INERT` | The broadcast arrives with no number. Every call produces `NUMBER_UNAVAILABLE`. Nothing else ever happens. |
| granted | granted | denied | `DEGRADED` | Calls are detected and matched. DND is never touched (`PERMISSION_DENIED` logged). Vibrate → normal and volume changes are attempted, and will fail with `SecurityException` → `NOTIFICATION_POLICY_ACCESS_DENIED` if DND is active. |
| granted | granted | granted | `ARMED` | The full apply path runs: DND relax, ringer mode, volume, alarm-stream fallback if still inaudible, and on a repeat call the alert. Each step is independently failable and independently reported. |

`ARMED` deliberately does **not** require `POST_NOTIFICATIONS` or the
full-screen-intent allowance. A device can be `ARMED` — ringing will be attempted
in full — while the repeat-caller alert is unavailable or degraded. Missing
`READ_CONTACTS` never affects readiness at all.

---

## Fixed-volume devices

`AudioManager.isVolumeFixed()` returns true when the device or the current audio
route does not permit stream volume changes. It is checked **before** any volume
change is attempted, so the result is a specific, truthful statement rather than
a generic failure:

| Condition | Volume | Ringer mode | Audit |
|---|---|---|---|
| `isVolumeFixed() == true` | Not attempted — impossible on this device | Still attempted | `VOLUME_FIXED` |
| `getStreamMaxVolume(STREAM_RING) <= 0` | Refused | Still attempted | `VOLUME_CHANGE_FAILED` (`VOLUME_FIXED`) |
| normal | Attempted, then read back | Attempted, then read back | `VOLUME_CHANGED`, or `VOLUME_CHANGE_FAILED` on mismatch |

On the Permissions screen this shows as `RESTRICTED`, not `DENIED` — there is no
button, because there is nothing a user could grant.

Two implementation details worth knowing:

- The requested percentage is rounded **up** to a volume index, so a configured
  80% never silently lands on a quieter index (3 of 5 would be 60%).
- The volume is set with flag `0`, deliberately — `FLAG_SHOW_UI` would pop the
  system volume panel over the incoming-call screen.

---

## Silent is not Vibrate, and Silent is not guaranteed

These are genuinely different device states and the app treats them differently.

| Starting ringer mode | Ringer / `STREAM_RING` path | Alarm-stream fallback (only if still inaudible) |
|---|---|---|
| `NORMAL` | No ringer change; volume is still raised | Not used |
| `VIBRATE` | `setRingerMode(NORMAL)`, then read back. The realistic supported path when DND is not blocking | Used only if the phone is still inaudible after verify |
| `SILENT` | `setRingerMode(NORMAL)` is still attempted, then read back. **Not guaranteed.** Failure is `SILENT_NOT_OVERRIDDEN` | **May be heard.** Silent does not mute `STREAM_ALARM` on AOSP. Not a claim that every OEM agrees — see P1 |
| `UNKNOWN` | No ringer change | Same last-resort rule as any other inaudible state |

When the ringer read-back shows the device is still silent, the audit log says:

> Phone is in Silent mode and stayed silent. Silent is controlled by you and the
> device manufacturer; an app cannot reliably override it.

That is still true of the **ringer**. The alarm-stream fallback is a second
attempt on a different stream, not a rewriting of that sentence. The app never
claims Silent can always be overridden by `setRingerMode`.

---

## Alarm-stream fallback vs Do Not Disturb

The fallback is **not** a universal bypass. Platform docs: when a zen policy
disallows alarms, the alarm stream is muted while DND is active.

| Interruption filter / policy | Expected alarm-stream result | Audit |
|---|---|---|
| Filter `ALL` (DND off) | Audible if alarm volume > 0 | Fallback usually not started (ringer path already audible) |
| Filter `ALARMS` | Alarms allowed; stream should play | `ALARM_STREAM_ALERT_STARTED` if the ringer path failed |
| Filter `PRIORITY` **with** `PRIORITY_CATEGORY_ALARMS` | Ordinary DND that still allows alarms; stream should play | same |
| Filter `PRIORITY` **without** alarms | Stream muted | `ALARM_STREAM_ALERT_LIKELY_INAUDIBLE` then still attempted |
| Filter `NONE` (Total Silence) | Stream muted | same |
| Alarm volume 0 / stream muted | Inaudible. This app does **not** raise alarm volume | `ALARM_STREAM_ALERT_LIKELY_INAUDIBLE` (`VOLUME_ZERO`) |

Playback delivery on every API this app supports is `IN_PROCESS` (no foreground
service). The sound may stop early if the process is reclaimed.

---

## P1 / P2 on the target phone

These are hardware measurements, not JVM tests. The intended family phone has
**not** had results written into this table yet. Fill the Outcome column from
that device; do not copy emulator behaviour.

| ID | What it asks | How | Outcome on the target phone |
|---|---|---|---|
| **P1** | Is `STREAM_ALARM` independent of ringer mode? Writing alarm index 0 must not move the ringer (the ring stream *does* couple index 0 to Vibrate). | `AlarmStreamIndependenceTest` (`adb shell cmd notification allow_dnd com.elham.priorityringer` first), plus a real Silent / Vibrate listen | **Not recorded.** Test exists; run it on the family phone before treating P1 as closed |
| **P2** | Does `MediaPlayer` keep playing after `PHONE_STATE` / `goAsync()` returns, without a foreground service? | Real incoming call (or Test Mode) in Silent, listen for the full ring, then answer/decline and confirm it stops | **Not recorded.** No service was added. Until this is measured, delivery stays `IN_PROCESS` and Architecture.md § A.2 stays no-FGS |

A Pixel 6 AVD (API 35) was used for earlier instrumented work; that is **not**
the family phone and is not a substitute for P1/P2.

When P1 fails on a given OEM, the fallback is void there — report it; do not
reach for a hidden API. When P2 fails (sound cuts as soon as the broadcast
ends), the honest product statement is already in the `IN_PROCESS` audit line.
Adding a FGS would be a later, Play-declaration-bearing change; it is out of
scope here.

---

## OEM interference

Samsung One UI, Xiaomi HyperOS/MIUI, and other manufacturer layers add their own
audio and notification policy on top of AOSP. In practice this means a
third-party app's ringer or volume change can be accepted and then quietly
ignored, or reverted moments later. `AudioManager.setRingerMode` and
`setStreamVolume` both return `void` and do not report refusal, so an app that
trusted the absence of an exception would cheerfully tell the user their phone
was about to ring loudly when it was not.

**Every mutation in this app is pre-check → attempt → re-read → compare.** A
mismatch becomes `VERIFICATION_FAILED` (or the more specific
`SILENT_NOT_OVERRIDDEN`) and is surfaced in the audit log and the UI as a
*detected* failure.

That is where it stops. Per the architecture contract, a detected failure is
never a cue to reach for a manufacturer-private SDK, a hidden API, reflection, or
an accessibility service. The app detects OEM interference and reports it; it
does not work around it. If your device's manufacturer software refuses these
changes, this app will tell you so clearly — and that is the entire remedy it
offers.

Related OEM variation you may hit:

- **Settings deep links may be missing.** Some builds omit the screens behind
  `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` or
  `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. `SettingsLinks.launch()` returns
  false and the app tells you rather than appearing broken; navigate manually
  from Settings → Apps.
- **Vendor "Focus", "Bedtime", "Zen" and "Sleep" modes** are not the AOSP DND
  filter and are not exposed by any API this app can read or change. The app has
  no visibility into them. If one of these is active, the read-back may report
  that the DND filter was successfully relaxed while the phone still stays quiet.

---

## Detection timing

This limitation applies to every device and every Android version, and it cannot
be fixed on the current code path.

`PHONE_STATE` is delivered **as ringing begins**, not before it. The app's
volume change therefore lands in the opening moment of the ringtone rather than
ahead of it. The first second or so — the part that actually wakes a sleeping
person — can still play at the phone's previous volume. The app is audibly less
effective than a pre-ring volume change would be.

The `CapabilityReport` carries this as a note on every device:

> Calls are detected when ringing begins, not before it. Volume is raised within
> the first moment of the ringtone rather than ahead of it.

The only official Android API that fires *before* ringing is
`CallScreeningService`, which can see `Call.Details` including the handle on
Android 10+. It is **not** the default here because holding it requires being
granted `ROLE_CALL_SCREENING`, and that role is exclusive: taking it would
displace whatever app currently provides Caller ID & spam protection on the
phone. Imposing that on a family member's device by default is a worse trade than
the timing gap it would close.

Detection is kept behind a seam (`TelephonyPort` / the `IncomingCallMonitor`
design in `Architecture.md` § 5) so a screening-role source could be added later
as an **explicit opt-in**, without restructuring the app. It is not built today.

Recorded in `Architecture.md` § A.6.

---

## Restore behaviour across versions

Restore is version-independent and has three triggers, all routed through one
idempotent operation:

| Trigger | Covers | Requires |
|---|---|---|
| Call state → `IDLE` | The normal case | `READ_PHONE_STATE`; process alive, or the `PHONE_STATE` IDLE broadcast restarts it |
| WorkManager watchdog | The call-end signal never arrived | Nothing beyond WorkManager; survives process death |
| Cold-start reconciliation | The process was killed mid-call | Nothing; runs on app/receiver start |

The pre-mutation snapshot is written to the database **before** anything is
changed, with insert-if-absent semantics, so an overlapping second call cannot
overwrite the genuine original state with values the app itself has already
modified. Running restore three times is harmless; running it zero times is the
only unacceptable outcome. The watchdog default is 90 seconds, configurable
between 30 and 600.

If restore fails, `RESTORATION_FAILED` is logged and the Dashboard keeps a
persistent banner. On the zen-rule branch, restore deactivates the app's own rule
rather than writing a filter value back.

---

## Dual-SIM phones

**Untested, and the one area where this document is least able to predict
behaviour.** No dual-SIM device was available, and nothing here has been run on
hardware at all (see README's build verification status).

What the code does:

- The `PHONE_STATE` broadcast is **not** subscription-scoped. It is delivered for
  calls on either SIM, so a priority contact calling the secondary SIM should
  still be detected.
- `EXTRA_INCOMING_NUMBER` is the same field either way — and the same
  `READ_CALL_LOG` rule applies. There are credible reports of the number extra
  being absent or empty on a secondary subscription on some devices. If that
  happens, the app logs `NUMBER_UNAVAILABLE` and does nothing, which is the same
  honest outcome as any other unidentifiable call.
- Country normalisation uses `TelephonyManager.networkCountryIso`, falling back to
  `simCountryIso` and then the device locale. On a dual-SIM phone these come from
  the **default data subscription**, which may not be the SIM receiving the call.
  A national-format number arriving on the other SIM could therefore be promoted
  to the wrong E.164 country code.

That last point sounds worse than it usually is: `PhoneNumberNormalizer` falls
back to comparing the trailing 7 digits whenever it cannot build E.164 for both
sides, and that comparison is deliberately country-agnostic. A mismatched country
code degrades matching to suffix comparison rather than breaking it. The residual
risk is a **false positive** across countries — two different numbers sharing
their last 7 digits — which is why short codes must match exactly.

**What to actually do:** if the phone has two SIMs, test both in Test Mode, and
place a real call to the secondary SIM before relying on it. If the audit log
shows `NUMBER_UNAVAILABLE` for calls that reach the secondary SIM while the
primary works, that is the known limitation above and there is no app-side fix
within the official APIs.

---

## Test Mode on your device is the authoritative answer

Everything above describes what the platform documents and what this app does
about it. None of it is a prediction about your specific phone.

Ringer and DND behaviour varies by manufacturer, by software version, by regional
build, and sometimes by carrier customisation. A Samsung Galaxy on one One UI
version can behave differently from the same model on the next. Vendor focus
modes are invisible to any API. Whether a given device honours a third-party
ringer change is, in practice, discoverable only by trying it on that device.

**No document can tell you what your phone will do. Test Mode can.**

Test Mode runs the *same* code path a real `PHONE_STATE` broadcast takes — the
same coordinator, the same use cases, the same real ports — and it never fakes a
permission result. A denied permission produces a real denial in simulation
exactly as it would during a real call. The result you see is the result you will
get.

Run it in each state you actually care about:

1. **On vibrate.** Expect `RINGER_MODE_CHANGED` and `VOLUME_CHANGED`. Confirm the
   phone is audible.
2. **On silent.** If you see `SILENT_NOT_OVERRIDDEN`, the **ringer** stayed
   silent. Look next for `ALARM_STREAM_ALERT_STARTED` (or
   `ALARM_STREAM_ALERT_LIKELY_INAUDIBLE`). The backup can still be heard in
   Silent; it is not guaranteed, and Total Silence will still mute it.
3. **With Do Not Disturb on.** Look for `DND_BYPASS_ATTEMPTED`, then check
   whether `DND_BYPASS_INEFFECTIVE` follows it. If the ringer path failed, the
   same alarm-stream events as Silent apply — unless this DND mode is Total
   Silence or disallows alarms.
4. **With any vendor focus/bedtime mode on**, if the phone has one. This is the
   case the app has the least visibility into, so it is the one most worth
   checking by hand.
5. **Twice in quick succession**, to exercise escalation and see whether you get
   `FULL_SCREEN_ALERT_SHOWN` or `FULL_SCREEN_ALERT_FALLBACK`.
6. **Then have someone really call.** Simulation exercises everything downstream
   of detection; only a real incoming call exercises detection itself.

Read the audit log after each run. It is deliberately specific about which step
failed and why, and it is the closest thing to ground truth this app can give
you.
