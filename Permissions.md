# Permissions

Every permission and capability Priority Caller depends on: what it is, why the
app needs it, exactly what breaks without it, how it is granted, and the precise
Settings intent the app uses to take you there.

The "what breaks" wording in each section is taken from
`AndroidCapabilityPort.kt`, which is the same text the Permissions screen shows
you on the device. Those strings are quoted verbatim so the documentation and the
app cannot drift apart.

---

## Summary

| Permission / capability | Manifest | Required? | Granted by | Missing ⇒ |
|---|---|---|---|---|
| `READ_PHONE_STATE` | yes | **yes** | Runtime dialog | `INERT` — app never learns a call is arriving |
| `READ_CALL_LOG` | yes | **yes** | Runtime dialog | `INERT` — caller can never be identified |
| `READ_CONTACTS` | yes | no | Runtime dialog | Only full-list browsing is unavailable |
| `ACCESS_NOTIFICATION_POLICY` | yes | no | Settings special access | `DEGRADED` — no DND change; no ringer/volume change while DND is on |
| `POST_NOTIFICATIONS` | yes | no | Runtime dialog (API 33+) | No repeat-caller alert; ringing unaffected |
| `USE_FULL_SCREEN_INTENT` | yes | no | Install-time, **plus** a user allowance on API 34+ | Alert degrades to a heads-up notification |
| `VIBRATE` | yes | no | Install-time, automatic | Alert channel cannot vibrate |
| Volume adjustable | n/a — device capability | n/a | Not grantable | Volume cannot be raised on this device |
| `INTERNET` | **deliberately absent** | — | — | — |

"Required" here means `Capability.isRequired` in the code, which is what drives
`CapabilityReport.readiness`. Note that `ARMED` requires only Phone, Call log and
notification-policy access — a device can be `ARMED` while the escalation alert
is unavailable.

---

## `READ_PHONE_STATE`

**What it is.** A runtime permission in the Phone group. It lets an app receive
`android.intent.action.PHONE_STATE` broadcasts and register a
`TelephonyCallback.CallStateListener`.

**Why this app needs it.** It is the app's entire notification that a call is
happening. `PhoneStateReceiver` is a manifest-registered receiver for
`PHONE_STATE`; without this permission the system does not deliver those
broadcasts. It is also what lets `AndroidTelephonyPort` observe the transition to
`IDLE`, which is the normal trigger for restoring the phone's settings after the
call.

**What breaks without it.**

> Without this, the app is never told that a call is arriving, so it can do
> nothing at all.

Additionally, `AndroidTelephonyPort.callState()` returns an empty flow when this
permission is missing, so the call-state restore trigger is unavailable. That
degrades the *timing* of restore, not its guarantee — the WorkManager watchdog
and cold-start reconciliation still cover it.

**How it is granted.** Runtime permission dialog
(`ActivityResultContracts.RequestPermission`). If the system has stopped showing
the dialog (permanently denied), the app falls back to the app details screen.

**Deep link.** No dedicated Settings action; fallback is
`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with a `package:` Uri.

---

## `READ_CALL_LOG`

**What it is.** A runtime permission in the Call Log group — a separate group
from Phone since Android 9, and one that Google treats as restricted.

**Why this app needs it.** This is the non-obvious one, and it is the most
important permission in the app. Since Android 9, the system only populates
`TelephonyManager.EXTRA_INCOMING_NUMBER` on the `PHONE_STATE` broadcast if the
receiving app holds `READ_CALL_LOG`. Without it the broadcast still arrives — it
just carries no number.

This is documented platform behaviour, not a workaround. There is no other way to
obtain the caller's number from the broadcast path.

The app does not read your call history. It reads the number attached to the
broadcast that is happening right now, compares it against your configured list,
and keeps only a redacted last-four in the audit log.

**What breaks without it.**

> Without this, Android hides the caller's number from the app. No caller can be
> recognised, so priority ringing will never happen — the app does nothing at all
> until this is granted.

The wording says *inert*, not "reduced functionality", and that distinction is
deliberate (`Architecture.md` § A.5). "Reduced" would imply the app still
sometimes works. It does not. Every incoming call produces
`CallMatchResult.NumberUnavailable` and a `NUMBER_UNAVAILABLE` audit entry, and
nothing else ever happens. The Dashboard shows `INERT`.

**How it is granted.** Runtime permission dialog. It is worth warning the person
whose phone it is, in advance, that this prompt will say "Call logs" and will
look alarming — it is unavoidable and the app cannot function without it.

**Deep link.** No dedicated Settings action; fallback is
`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

### Google Play policy note

`READ_CALL_LOG` is in Google Play's **restricted permissions** set. An app
submitted to Play that requests it must have a core, user-facing feature that
genuinely requires call log access, must file a Permissions Declaration in Play
Console describing that use case, and must have it **approved**. Approval is not
routine, and rejections for this permission group are common — a "ring louder for
family" use case is not one of Play's listed exempted use cases, so it would be
an argued exception rather than a checkbox.

**None of that applies to sideloading.** Installing this APK directly onto a
family member's phone involves no Play review and no declaration, and that is the
expected distribution route for this app.

`targetSdk` is not a complication here: the app already targets 35, so a Play
submission would not need to raise it and the weaker Android 15 DND behaviour is
already what ships (see `README.md` and `DeviceCompatibility.md`).
`READ_CALL_LOG` is the one genuinely blocking requirement for Play, and it is
blocking regardless of anything else in this document.

---

## `READ_CONTACTS`

**What it is.** A runtime permission in the Contacts group.

**Why this app needs it.** Only for browsing the device's full contact list
in-app when adding a priority contact. The system contact picker
(`ActivityResultContracts.PickContact`) hands back a single chosen contact
*without* requiring this permission, and manual number entry needs nothing at
all.

**What breaks without it.**

> Optional. Without it you can still add numbers by hand or with the contact
> picker; only browsing your full contact list is unavailable.

Nothing about call detection, matching or ringing depends on it. Matching is done
against the normalised numbers stored in the app's own database, not against the
contacts provider.

**How it is granted.** Runtime permission dialog.

**Deep link.** No dedicated Settings action; fallback is
`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

---

## `ACCESS_NOTIFICATION_POLICY`

**What it is.** A **special access** permission, not a runtime one. Declaring it
in the manifest grants nothing; the user must switch the app on in a dedicated
Settings screen. In code the state is read via
`NotificationManager.isNotificationPolicyAccessGranted`.

**Why this app needs it.** It gates three separate things, and the third one
surprises people:

1. **Changing Do Not Disturb.** `setInterruptionFilter`,
   `setNotificationPolicy`, and the `AutomaticZenRule` APIs all require it.
2. **Changing the ringer mode or ring volume while DND is active.**
   `AudioManager.setRingerMode` and `setStreamVolume` throw `SecurityException`
   for transitions that cross a DND boundary without this access. The app catches
   those at the port boundary and converts them to
   `FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED`.
3. **The alert channel's `setBypassDnd(true)`**, which lets the repeat-caller
   notification appear while DND is on.

**What breaks without it.**

> Without this, the app cannot change Do Not Disturb, and cannot change the
> ringer or volume while Do Not Disturb is on. Priority calls will still be
> detected but may stay silent.

The Dashboard shows `DEGRADED`. The app still detects and matches calls, and
still attempts vibrate → normal, which can succeed when DND is not active. When
DND *is* active, the apply path short-circuits before touching anything and logs
`PERMISSION_DENIED`.

**How it is granted.** Settings → special access screen. The name varies by
manufacturer: "Do Not Disturb access", "Notification policy access", or "Do Not
Disturb permission". Find the app in the list and enable its switch.

**Deep link.**

```kotlin
Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
```

(No Uri — this action opens the system-wide list of apps, not a per-app page.)

---

## `POST_NOTIFICATIONS`

**What it is.** A runtime permission introduced in Android 13 (API 33). On API 30
–32 it does not exist; notifications are allowed by default and the app reports
the capability as `NOT_REQUIRED`, which is not a problem state.

**Why this app needs it.** The repeat-caller escalation alert is delivered as a
high-importance notification carrying a full-screen intent. Posting it requires
this permission on API 33+. `AndroidAlertPort` checks it before posting and
returns `FailureReason.RUNTIME_PERMISSION_DENIED` rather than attempting a post
that would silently do nothing.

**What breaks without it.**

> Without this, the repeat-caller alert cannot be shown. Ringer and volume
> changes still work.

Escalation still raises volume to maximum; only the visual alert is lost.

**How it is granted.** Runtime permission dialog on API 33+. Nothing to do on
older versions.

**Deep link.**

```kotlin
Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
```

---

## `USE_FULL_SCREEN_INTENT`

**What it is.** A normal (install-time) permission through Android 13 — declaring
it in the manifest was enough. **Android 14 (API 34) changed that**: the platform
stopped auto-granting it to apps that are not calling or alarm apps, and added a
per-app user allowance plus an API to query it,
`NotificationManager.canUseFullScreenIntent()`.

**Why this app needs it.** A full-screen intent is what lets the escalation alert
take over the screen, turn it on and show over the lock screen, rather than
appearing as a banner that a sleeping person will not see. `PriorityAlertActivity`
is declared `showWhenLocked` and `turnScreenOn` for exactly this case.

**How the app handles the Android 14 gate.** Rather than reasoning about whether
this app qualifies for the calling-app exemption, `AndroidAlertPort` simply asks
the platform:

```kotlin
if (Build.VERSION.SDK_INT >= 34) notificationManager.canUseFullScreenIntent() else true
```

If the answer is no, it builds the same notification **without**
`setFullScreenIntent` and reports the mode as
`AlertPort.AlertMode.HEADS_UP_FALLBACK`, which is recorded in the audit log as
`FULL_SCREEN_ALERT_FALLBACK`. That way the audit log always says which of the two
the user actually got.

**What breaks without it.**

> Without this, a repeat caller shows a normal heads-up notification instead of
> taking over the screen. Everything else still works.

On API 34+ the Permissions screen adds the detail line: *"Android 14+ requires you
to allow full-screen notifications for this app."*

**How it is granted.** Nothing to do below API 34. On API 34+ it is a Settings
toggle, typically under the app's notification settings as "Allow full-screen
notifications" or similar.

**Deep link.** API 34+ only; below 34 the app offers no button because there is
nothing to open.

```kotlin
Intent(
    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
    Uri.fromParts("package", context.packageName, null),
)
```

---

## `VIBRATE`

**What it is.** A normal (install-time) permission. Granted automatically at
install; there is no dialog and no Settings toggle.

**Why this app needs it.** The escalation notification channel is created with
`enableVibration(true)` and `setSound(null, null)` — the notification itself is
deliberately silent, because the app is already raising the *ring* stream and a
notification sound would double up over the ringtone. Vibration is what is left
to draw attention.

**What breaks without it.** Nothing the user can cause. It is not user-revocable,
so it is not modelled in `Capability.kt` and does not appear on the Permissions
screen.

**How it is granted.** Automatically, at install.

**Deep link.** None; not applicable.

---

## Volume adjustable (a device capability, not a permission)

**What it is.** Not a permission at all. `AudioManager.isVolumeFixed()` reports
whether the device or the current audio route allows stream volume to be changed
at all. Some devices and some output routes report `true`, meaning volume is
fixed by the hardware or the route.

It is included here because it is modelled alongside the permissions as
`Capability.VOLUME_ADJUSTABLE` and appears on the same screen.

**Why it matters.** `ApplyPriorityRingUseCase` checks it *before* attempting any
volume change, so that the audit entry reads "this device cannot do it" rather
than emitting a misleading generic failure. When it is fixed, the app logs
`VOLUME_FIXED` and moves on; the ringer-mode change is still attempted.

**What it costs you.**

> This device reports a fixed output volume, so ring volume cannot be raised. The
> ringer mode can still be switched out of vibrate.

**How it is "granted".** It is not. This state is reported as `RESTRICTED` rather
than `DENIED`, and `CapabilityStatus.isActionable` is false for it, so the
Permissions screen shows the explanation and **no button** — there would be
nothing for a button to do.

**Deep link.** None.

---

## Not requested: `INTERNET`

The manifest contains no `INTERNET` permission. This is called out explicitly in
the manifest with a comment, because its absence is what makes the privacy claim
verifiable rather than merely promised: an app without `INTERNET` cannot open a
socket, so contacts, numbers and the audit log physically cannot be transmitted
off the device by this app.

Android's own backup transport is a separate matter from anything the app does,
and it is handled: the Room database holding contacts and the audit log is
excluded from both cloud backup and device transfer by `@xml/backup_rules` and
`@xml/data_extraction_rules`. See the privacy section of `README.md`, including
the residual risk that the exclusion is matched by hardcoded file path.

## Not requested: `ROLE_CALL_SCREENING` / `CallScreeningService`

The one official API that would let the app act *before* the phone starts
ringing. It is not used, and not requested, because `ROLE_CALL_SCREENING` is
exclusive: granting it to this app removes it from whichever app currently
provides Caller ID & spam protection on that phone. Displacing that by default on
a family member's device is not a trade the app makes for you.

It remains a possible future opt-in. See `Architecture.md` § A.6 and the
detection-timing section of `DeviceCompatibility.md`.

---

## How the app takes you to each screen

All of the above is wired in `presentation/common/SettingsLinks.kt`.

| Capability | Runtime permission | Settings intent |
|---|---|---|
| `READ_PHONE_STATE` | `Manifest.permission.READ_PHONE_STATE` | — (dialog; fallback app details) |
| `READ_CALL_LOG` | `Manifest.permission.READ_CALL_LOG` | — (dialog; fallback app details) |
| `READ_CONTACTS` | `Manifest.permission.READ_CONTACTS` | — (dialog; fallback app details) |
| `NOTIFICATION_POLICY_ACCESS` | — | `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` |
| `POST_NOTIFICATIONS` | `Manifest.permission.POST_NOTIFICATIONS` (API 33+, else none) | `Settings.ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE` |
| `FULL_SCREEN_INTENT` | — | `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` + `package:` Uri (API 34+ only; `null` below) |
| `VOLUME_ADJUSTABLE` | — | — (not grantable) |
| *fallback for any of the above* | — | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` + `package:` Uri |

Runtime permissions go through a permission-request launcher first; the app
details screen is the fallback once the system has stopped showing the dialog.

**If a button appears to do nothing:** `SettingsLinks.launch()` returns `false`
when no activity on the device handles the intent, and the app shows a message
instead of failing silently. Some manufacturer builds genuinely omit these
screens. The workaround is manual navigation — **Settings → Apps → Priority
Caller**, or the device's search box with terms like "Do Not Disturb access" or
"full-screen notifications".

---

## Permission state is never cached

`AndroidCapabilityPort.report()` probes every permission and device capability
fresh on each call and caches nothing. These grants can be revoked from Settings
while the app is running, and a stale ✅ on the one screen whose purpose is
telling you whether the app works would be worse than showing no screen at all.
