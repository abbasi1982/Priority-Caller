# Priority Caller

An Android app that tries to make calls from people you choose ring loudly, even
when the phone is on vibrate or in Do Not Disturb.

Package: `com.elham.priorityringer` · Gradle root project: `PriorityCaller`
`minSdk 30` (Android 11) · `compileSdk 35` · `targetSdk 35`

**A note on the name.** The app's user-visible name (`app_name`) is **Priority
Caller** — that is what appears on the home screen. Every internal identifier —
the package, the `applicationId`, the `PriorityRinger*` class names, the
`priority_ringer.db` database — uses **priority ringer** instead. The mismatch is
deliberate and not a bug: "ringer" describes what the code does, "caller"
describes what the user cares about. Do not "fix" one to match the other;
changing the `applicationId` would make the app a different app to Android, and
changing the database name would silently break the backup exclusion rules.

It uses only official, documented Android APIs. There is no root requirement, no
ADB step, no hidden or reflected API, no accessibility service used as a ringer
substitute, and no manufacturer-private SDK. That constraint is the reason the
app cannot promise as much as you might want it to, and this document is written
to be clear about exactly where the line falls.

---

## Build verification status

**Read this before anything else.**

There is no JDK, no Gradle and no Android SDK in the environment where this code
was written. **Nothing in this repository has ever been compiled, and no test has
ever been run.** Not once, not partially, not in an IDE.

Concretely, that means:

- **Expect compile errors on the first build.** The first `./gradlew assembleDebug`
  should be treated as the beginning of the build process, not as a check that
  everything already works. Unresolved references, Hilt/KSP graph errors and
  missing-resource errors are all plausible and none of them would have been
  caught yet.
- **Dependency versions are unverified.** Everything in `gradle/libs.versions.toml`
  was written from knowledge, not resolved against a live Maven repository. Some
  versions may not exist, and the AGP / Kotlin / KSP / Compose-compiler /
  Hilt versions must be mutually compatible — expect to bump some of them. That
  file carries the same warning at the top.
- **The Gradle wrapper is not usable.** `gradle/wrapper/gradle-wrapper.properties`
  exists, but `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` do
  **not**. You must generate them before you can use `./gradlew` at all. See
  [Build and install](#build-and-install) below.
- **`app/schemas/` does not exist yet, and cannot until the first successful
  build.** Room generates the exported schema JSON as a build output. Until
  `assembleDebug` succeeds at least once, `MigrationTest` has nothing to read and
  cannot pass — that is expected, not a defect in the test.
- **Test sources were reconciled against the production code by hand, not by a
  compiler.** DAO method and column names in the instrumented tests were matched
  against the real Room layer by reading it. That is a careful human check, not a
  verified one, so expect some tests not to compile on the first attempt.

The source tree itself is otherwise complete: 63 main Kotlin files, 36 of them
presentation, 11 resource XML files, 8 JVM unit test files and 8 instrumented
test files. Complete is not the same as correct — see the first bullet.

Nothing below this section should be read as "verified working". It describes
what the code is written to do.

---

## What the app does

When a call arrives, the app is notified by Android via the `PHONE_STATE`
broadcast, reads the caller's number, and compares it against a list of priority
contacts you configured. If — and only if — the number matches an **enabled**
priority contact, it:

1. Captures the phone's current ringer mode, ring volume and Do Not Disturb
   filter, and **writes that snapshot to disk before changing anything**.
2. Schedules a watchdog that will restore those settings even if the app is
   killed.
3. Attempts to relax Do Not Disturb (by default, by allowing calls through
   rather than switching DND off entirely).
4. Attempts to switch the ringer out of vibrate or silent.
5. Attempts to raise the ring volume to your configured level (80% by default).
6. If this is a repeat call from the same person inside your configured window,
   raises volume to maximum and shows a full-screen alert.

After each of those attempts it **reads the value back from the system and
compares it** to what it asked for. If the device did not actually change, that
is recorded as a detected failure in the audit log and shown in the UI. It is
never reported as success.

The original settings are restored as soon as the call is **answered or ends** —
once the ringtone has stopped, the raised volume and relaxed DND have done their
job. Restore has three independent triggers (the call-state change, a watchdog
timeout, and a reconciliation pass on app start), because running restore more
than once is harmless and running it zero times is not.

The reconciliation pass deliberately does **nothing** while a call is in
progress. If the app is killed mid-ring, Android restarts it for the very call
that is still ringing — and restoring at that moment would put the phone back to
vibrate and silence the call the app is there to make audible.

If the incoming number is **not** a priority contact, the app touches nothing at
all — no audio change, no DND change, and no audit entry (logging every ordinary
call would amount to keeping a copy of your call history).

## What the app does not do

This list is not modesty. Each item is a real limit of the platform or a
deliberate scope decision.

- **It cannot guarantee that Silent mode is overridden.** Silent
  (`RINGER_MODE_SILENT`) is not the same thing as Vibrate. The app attempts the
  transition to Normal, then re-reads `getRingerMode()`. If the device is still
  silent, it records `SILENT_NOT_OVERRIDDEN` and tells you so. Silent is
  controlled by you and by the device manufacturer, and an app cannot reliably
  override it.
- **It cannot guarantee that calls ring through Do Not Disturb.** For apps
  targeting API 35+, Android 15 routes these APIs through zen rules that combine
  **most-restrictive-wins**: a stricter Do Not Disturb that the user set
  themselves takes precedence, and no app can override it. The app detects this
  case (`DND_BYPASS_INEFFECTIVE`) and reports it rather than claiming success.
- **It cannot raise the volume on every device.** Some audio routes and devices
  report `AudioManager.isVolumeFixed() == true`. On those, no volume change is
  possible at all. The ringer mode can still be switched out of vibrate.
- **It cannot work around OEM ringer restrictions.** Samsung, Xiaomi and other
  vendor layers sometimes accept a ringer or volume change and then quietly
  ignore it. The read-back detects that; the app then reports it. It does not go
  looking for an undocumented way around it.
- **It cannot raise the volume before the phone starts ringing.** See
  [Detection timing](#detection-timing-a-real-limitation) below.
- **Without `READ_CALL_LOG` (or `READ_PHONE_STATE`) it does nothing at all.** Not
  less — nothing. See the next section.
- **It does not answer, reject, screen or block calls**, does not replace the
  dialer or the in-call screen, and does not sync anything anywhere.

### The app is inert without Phone and Call log permission

Since Android 9, the `PHONE_STATE` broadcast only carries
`EXTRA_INCOMING_NUMBER` if the receiving app holds `READ_CALL_LOG`. Without it,
the app is still told a call is ringing but **is never told who is calling**. No
caller can be matched, so priority ringing can never trigger — for any contact,
ever.

`READ_PHONE_STATE` is equally decisive: without it the app is not told that a
call is arriving at all.

The code models this explicitly. `CapabilityReport.readiness` has three states:

| Readiness | Meaning |
|---|---|
| `ARMED` | Phone, Call log and Do Not Disturb access all granted. Priority ringing will be attempted in full. |
| `DEGRADED` | Calls are detected and matched, but Do Not Disturb access is missing, so DND cannot be touched — and ringer/volume changes will also fail while DND is active. |
| `INERT` | `READ_PHONE_STATE` or `READ_CALL_LOG` is missing. No caller can be identified. **Nothing will ever happen.** |

Note that `ARMED` does **not** require notification or full-screen-intent
permission. A device can be `ARMED` — ringing will be attempted — while the
repeat-caller alert is unavailable.

### Detection timing (a real limitation)

`PHONE_STATE` is delivered **as ringing begins**, not before it. The app raises
the volume in the opening moment of the ringtone rather than ahead of it. The
first second or so of the ringtone — the part that actually wakes someone — may
still play at the phone's previous volume.

The only official Android API that fires *before* ringing is
`CallScreeningService`. It is deliberately **not** used here: holding it requires
being granted `ROLE_CALL_SCREENING`, which is exclusive — taking it would
displace whatever app currently provides Caller ID & spam protection on the
phone. That is a bad trade to impose on a family member's device by default.

Detection is kept behind a seam (`IncomingCallMonitor` / `TelephonyPort`) so a
screening-role source could be added later as an explicit opt-in, without
restructuring the app. It is not built today.

This is recorded in `Architecture.md` § A.6 and repeated in
[`DeviceCompatibility.md`](DeviceCompatibility.md).

---

## Build and install

Prerequisites: JDK 17, Android SDK with platform 35 installed, and either
Android Studio or a Gradle installation.

**The Gradle wrapper is incomplete.** `gradle/wrapper/gradle-wrapper.properties`
is present, but the `gradlew` / `gradlew.bat` scripts and
`gradle/wrapper/gradle-wrapper.jar` are not, so `./gradlew` will not run.
Generate the missing pieces first, using one of:

```bash
# Option A — with a system Gradle installed
gradle wrapper

# Option B — open the project folder in Android Studio, which will
# generate the wrapper and sync automatically.
```

Then:

```bash
./gradlew assembleDebug                       # build the debug APK
./gradlew installDebug                        # build + install onto a connected device
```

The APK lands in `app/build/outputs/apk/debug/`.

Expect the first run of `assembleDebug` to fail. See
[Build verification status](#build-verification-status). Work through the errors
it reports; do not assume the tree is sound.

Java/Kotlin target is 17 (`sourceCompatibility`, `targetCompatibility`,
`jvmTarget`).

### A note on `targetSdk = 35`

This is deliberate, and `app/build.gradle.kts` carries a comment saying so.

Targeting 35+ changes what `setInterruptionFilter` and `setNotificationPolicy`
mean: they stop changing global DND and instead drive an *implicit*
`AutomaticZenRule`, with rules combined most-restrictive-wins. **A Do Not
Disturb mode you set yourself therefore wins, and this app cannot override it.**

That is a real cost, and it is accepted on purpose. `ImplementationPlan.md`
specifies "target/compile 35 or 36 with API 35 DND behavior explicitly handled",
and records the consequence in its own risk list: *"API 35 DND may make FR3
mostly 'attempt + explain' — product must accept that."*

So FR3 is exactly that — attempt, verify by reading the filter back, and say
plainly when it did not work. The read-back matters *more* on this branch, not
less: it is the difference between the app telling you Do Not Disturb defeated
it and the app quietly doing nothing.

Both branches are implemented — `AndroidDndPort` selects between them at runtime
from `Build.VERSION.SDK_INT` and the app's own `targetSdkVersion`. The legacy
global-filter path stays live for devices below API 35, and moving between
branches is a one-line change rather than a redesign.

See also the `READ_CALL_LOG` policy note in [`Permissions.md`](Permissions.md),
which is the genuinely blocking issue for Play distribution.

### Installing on the phone that will use it

Sideloading is the expected distribution route. Either connect the phone over USB
and run `./gradlew installDebug`, or copy the built APK to the phone and open it,
allowing installation from that source when prompted.

---

## First-run setup

This app is usually configured by one person **on someone else's phone** — a
parent's, typically. The permissions it needs are scattered across several
different Settings screens, some of which are not in the app's own permission
list, and the screens have different names on different manufacturers'
software. Budget ten minutes and do it in this order.

Everything below is also reachable in-app from the **Permissions** tab, which
shows the live status of each item and a button that opens the right Settings
page directly. If a button appears to do nothing, that manufacturer's build does
not have that Settings screen; the app will say so, and you can fall back to
**Settings → Apps → Priority Caller**.

**1. Install the app** and open it.

**2. Grant Phone and Call log.** These appear as ordinary permission pop-ups.
Tap **Allow** for both.

- *Phone* lets the app know a call is arriving.
- *Call log* is what lets Android tell the app **who** is calling. It sounds
  alarming and unrelated; it is not optional. Without it the app does nothing
  whatsoever. The app does not read your call history, does not upload anything,
  and cannot — it has no internet permission at all.

The Dashboard will show **INERT** until both are granted.

**3. Grant Do Not Disturb access.** This one is *not* a pop-up. It lives in a
separate "special access" area of Settings, and the app will open it for you.
Look for the app in the list and turn its switch on. The screen is variously
called:

- "Do Not Disturb access"
- "Notification policy access"
- "Do Not Disturb permission"

Without this, calls are still detected, but Do Not Disturb cannot be relaxed —
**and the ringer and volume cannot be changed while Do Not Disturb is on
either.** The Dashboard will show **DEGRADED**.

**4. Allow notifications** (Android 13 and newer). Another pop-up. This is only
needed for the repeat-caller alert; ringing works without it.

**5. Allow full-screen notifications** (Android 14 and newer). Also a separate
Settings screen, which the app will open. This is what lets a repeat call take
over the screen and wake it, rather than showing a banner. Without it you get a
banner instead; everything else still works.

**6. Add the priority contacts.** Go to the **Contacts** tab, tap add, and either
pick from the phone's contacts or type a number. Each contact has an on/off
switch, so you can leave someone configured but temporarily inactive. (Granting
*Contacts* permission is optional — the system contact picker works without it,
and you can always type a number by hand.)

**7. Set the ring volume** in **Settings**. The default is 80% of maximum. The
minimum you can set is 10%, deliberately: a "priority ringer" configured to be
silent would look armed while doing nothing.

**8. Test it.** Go to **Settings → Test Mode** and run a simulation — see the
next section. Then, if you can, have someone actually call the phone from a
configured number while it is on vibrate. That is the only true test.

**9. Check the Dashboard says ARMED.** If it says `DEGRADED` or `INERT`, go back
to the Permissions tab; it will name what is missing and what that costs you.

---

## The seven screens

Navigation is five bottom-bar tabs plus two screens entered from context (Add
Contact from Contacts, Test Mode from Settings).

| Screen | What it is for |
|---|---|
| **Dashboard** | Overall readiness (`ARMED` / `DEGRADED` / `INERT`), the live capability chips, the most recent priority call and what the app managed to do about it, and a persistent banner if a restore failed or a DND bypass was ineffective. |
| **Priority Contacts** | The configured list, each with an enable/disable switch, and removal. Numbers are shown redacted to the last four digits. |
| **Add Contact** | Add via the system contact picker, or by typing a number. Numbers are normalised on entry and stored under a unique match key. |
| **Permissions** | Every permission and capability, its live state, a plain-language statement of exactly what stops working without it, and a button that deep-links to the right Settings page. States that the device itself forbids (such as fixed volume) show an explanation and no button, because there is nothing to tap. |
| **Audit Log** | Reverse-chronological record of what actually happened, capped at 500 entries. This is where a detected failure is visible — e.g. `SILENT_NOT_OVERRIDDEN`, `DND_BYPASS_INEFFECTIVE`, `VOLUME_FIXED`, `RINGER_CHANGE_FAILED`, `RESTORATION_FAILED`. |
| **Settings** | Ring volume percent (10–100, default 80), repeat-call escalation thresholds (default: 2 calls in 5 minutes, or 3 calls in 10 minutes), auto-restore timeout (default 90s, clamped 30–600), logging on/off, and the DND strategy (allow calls through — the default — versus switching DND off for the call). |
| **Test Mode** | The live capability report, current ringer mode and interruption filter, and simulation. |

Turning logging off still persists **errors**. Someone who has disabled logging
still needs to be able to find out why the app failed.

---

## Verifying it on a real device

Test Mode is the honest way to find out what this app can do on *your* hardware,
and it is the only answer that matters — the behaviours above vary by
manufacturer and no document can predict them for a specific phone.

**Run a simulation.** Test Mode's "simulate priority call" runs the *same*
code path a real `PHONE_STATE` broadcast takes: the same coordinator, the same
use cases, the same real ports. It does **not** fake permission results — a
denied permission produces a real denial in the simulation, exactly as it would
during a real call. If no number is given it uses the first enabled contact, and
if there are no contacts it refuses rather than silently doing nothing.

A good verification pass:

1. Put the phone on **vibrate**. Run a simulation. The audit log should show
   `RINGER_MODE_CHANGED` and `VOLUME_CHANGED`. Confirm the phone is audible.
2. Put the phone on **silent**. Run a simulation. Read the result honestly — if
   you get `SILENT_NOT_OVERRIDDEN`, this device will not be rescued from silent
   mode by this app, and no setting will change that.
3. Turn **Do Not Disturb** on. Run a simulation. Look for
   `DND_BYPASS_ATTEMPTED` and check whether it is followed by
   `DND_BYPASS_INEFFECTIVE`.
4. Run a simulation twice (or more) within the escalation window to exercise the
   repeat-caller path: `ESCALATION_TRIGGERED`, then either
   `FULL_SCREEN_ALERT_SHOWN` or `FULL_SCREEN_ALERT_FALLBACK`.
5. Confirm the phone's ringer mode and volume are **back where they were** after
   each run — look for `RESTORATION_COMPLETED`. Test Mode restores through the
   same path a real call does.
6. Finally, have someone call from a configured number. Simulation exercises
   everything downstream of detection; only a real call exercises detection
   itself.

`SIMULATION_RUN` entries in the audit log are labelled as simulated, and the
priority-call entry is marked `[simulated]`, so a test run can never be mistaken
for a real one later.

---

## Architecture in brief

Clean architecture, dependencies pointing inward. Android framework types are
confined to `data/platform` and are never imported by `domain`, which is plain
JVM-testable Kotlin.

```
presentation/  Compose + Material 3, ViewModels, Navigation, Activities
domain/        Use cases, models, ports, policy — no android.* imports
data/          Room, platform adapters, repositories, Hilt modules
```

Three design decisions carry most of the weight:

- **Ports return `Outcome`, never booleans and never exceptions.** Every mutating
  port method returns `Outcome.Success` or `Outcome.Failure(reason, detail,
  cause)`. `SecurityException` is caught at the port boundary and converted into
  a typed `FailureReason`. A permission denial is *data*, not an exception —
  which is what makes "verify every apply path" mechanical instead of a matter of
  developer discipline.
- **Every mutation is read back.** `AudioManager.setRingerMode` and
  `setStreamVolume` return `void` and do not report refusal; on some OEM skins
  they return normally having changed nothing. So every mutation is
  pre-check → attempt → re-read → compare, and a mismatch becomes
  `VERIFICATION_FAILED` (or the more specific `SILENT_NOT_OVERRIDDEN`). `DndPort`
  does the same with `getCurrentInterruptionFilter()`.
- **Restore is write-ahead.** The pre-mutation snapshot is persisted to Room
  *before* anything is changed, with insert-if-absent semantics so an overlapping
  second call cannot overwrite the genuine original state with already-mutated
  values. Three independent triggers — call-state `IDLE`, a WorkManager watchdog,
  and cold-start reconciliation — all route through one idempotent restore. This
  is stricter than the original architecture contract, which specified an
  in-memory snapshot; the reasoning is in `Architecture.md` § A.3.

Call handling runs inside `BroadcastReceiver.goAsync()` with a hard 5-second
timeout, not in a foreground service. There is no `foregroundServiceType` this
app could honestly justify — it is not a calling app.

The full contract, including the reviewer's verdict on each requirement and the
implementer's recorded decisions in **Addendum A**, is in
[`Architecture.md`](Architecture.md). Read it before changing anything in
`data/platform`.

---

## Privacy

- **There is no `INTERNET` permission in the manifest.** Not restricted, not
  unused — absent. The app therefore cannot transmit anything anywhere, and that
  is verifiable by reading `AndroidManifest.xml` rather than something you have
  to take on trust.
- **No analytics, no crash reporting, no cloud sync, no accounts.** The
  dependency list contains no networking or telemetry library.
- **The audit log is local.** It is a Room table on the device, capped at 500
  entries, clearable from the Audit Log screen.
- **Numbers are redacted in the log.** Audit messages and log output show only
  the last four digits of a number (`•••1234`).
- **Ordinary calls are not recorded.** A non-matching call produces no audit
  entry at all, deliberately, so the log never becomes a copy of the call
  history.

- **The database is excluded from Android's own backup.** The manifest sets
  `android:allowBackup="true"`, but points at `@xml/backup_rules` (API 30) and
  `@xml/data_extraction_rules` (API 31+), and both exclude `priority_ringer.db`
  together with its `-wal`, `-shm` and `-journal` sidecars. The extraction rules
  cover **both** transports — `cloud-backup` *and* `device-transfer` — so a
  direct phone-to-phone transfer does not copy the data either.

  The exclusion is necessarily database-wide, because settings live in the same
  Room database as the contacts and the audit log and there is no file
  granularity that would separate them. Losing a backed-up volume percentage is
  the deliberate price of not having a family's priority-caller list sitting in
  cloud storage.

**One residual risk.** The exclusion is by **file path**, hardcoded as
`priority_ringer.db` in both XML files, and it must match
`PriorityRingerDatabase.NAME`. It does today. But if anyone renames the database
without updating both XML files, the exclusion stops applying **silently** —
there is no build error and no runtime warning, just a database that starts
getting backed up. The rules files carry a comment saying so.

Setting `android:allowBackup="false"` in the manifest would make this
structurally safe rather than dependent on two strings staying in step; the
backup rules file itself recommends it as belt-and-braces. The app currently
keeps `allowBackup="true"`. `Architecture.md` § 16 left the decision open, and it
is the one privacy property here that rests on a convention rather than on
something the platform enforces.

---

## Project structure

`app/schemas/` is a build output and is absent until the first successful build —
see [Build verification status](#build-verification-status).

```
PriorityCaller/
├── README.md                     this file
├── Architecture.md               the governing contract (+ Addendum A)
├── ImplementationPlan.md         phased build plan
├── Permissions.md                every permission, why, and how to grant it
├── DeviceCompatibility.md        API 30–36 matrix and OEM caveats
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml        version catalogue (UNVERIFIED — see above)
│   └── wrapper/                  .properties only; the JAR and scripts are missing
└── app/
    ├── build.gradle.kts          compileSdk 35, targetSdk 35, minSdk 30
    ├── schemas/                  exported Room schemas (generated by the build)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/              strings, theme, icons, backup + extraction rules
        │   └── java/com/elham/priorityringer/
        │       ├── PriorityRingerApp.kt      @HiltAndroidApp
        │       ├── service/
        │       │   └── PhoneStateReceiver.kt PHONE_STATE, goAsync()
        │       ├── domain/                   no android.* imports
        │       │   ├── model/                Models, Capability, Outcome
        │       │   ├── port/                 Audio/Dnd/Telephony/Alert/Scheduler/Clock
        │       │   ├── repository/           interfaces only
        │       │   ├── phone/                PhoneNumberNormalizer
        │       │   ├── escalation/           EscalationPolicy
        │       │   └── usecase/              IncomingCallCoordinator, ApplyPriorityRing, …
        │       ├── data/
        │       │   ├── local/                Room: entities, DAOs, mappers, database
        │       │   ├── platform/
        │       │   │   ├── audio/            AndroidAudioPort
        │       │   │   ├── dnd/              AndroidDndPort (both A.1 branches)
        │       │   │   ├── telephony/        AndroidTelephonyPort
        │       │   │   ├── alert/            AndroidAlertPort
        │       │   │   ├── capability/       AndroidCapabilityPort
        │       │   │   └── scheduler/        RestoreWatchdog (WorkManager)
        │       │   └── repository/           implementations
        │       ├── di/                       Hilt modules
        │       └── presentation/             Compose UI: 7 screens, navigation, theme
        ├── test/                             JVM unit tests + fakes
        └── androidTest/                      HiltTestRunner, Room migration, DAO tests
```

---

## Testing

**No test in this repository has ever been executed.** There is no JDK, Gradle or
Android SDK in the environment this was authored in, so "the tests pass" is a
claim nobody is in a position to make about this tree. The first `./gradlew test`
and `./gradlew connectedAndroidTest` are discovery runs, not confirmations —
expect some of the tests themselves not to compile on the first attempt.

Both test source sets are now written: **8 JVM unit test files (roughly 160
cases)** and **8 instrumented test files (roughly 41 cases)**. None of them has
ever been executed. There is no `@Ignore`d test in the tree.

Two specific reasons to expect friction on the first run:

- **`MigrationTest` cannot pass until `assembleDebug` has succeeded once.** It
  reads the exported Room schema JSON from `app/schemas/`, which Room generates
  as a build output and which does not exist yet.
- **The instrumented tests' DAO method and column names were reconciled against
  the production Room layer by reading it, not by compiling against it.** That is
  a careful manual check, and manual checks are not compilers.

The build file wires the test infrastructure: `HiltTestRunner` as the
instrumentation runner, the exported Room schema directory added to the
`androidTest` assets so migration tests can find it, JUnit / MockK / Turbine /
coroutines-test for JVM tests, and Hilt testing, Room testing, Espresso and
Compose UI test artifacts for instrumented tests.

Coverage, per `Architecture.md` § 14:

| Layer | What is covered |
|---|---|
| JVM unit (`app/src/test/`) | Phone number normalisation and matching; escalation window arithmetic across both windows; settings validation and clamping; `ApplyPriorityRingUseCase`; `EvaluateIncomingCallUseCase`; `IncomingCallCoordinator`; restore idempotency — all against the fakes in `fake/Fakes.kt` and a fake clock, with no Robolectric and no device. |
| Instrumented (`app/src/androidTest/`) | `MigrationTest` for the Room schema, `HiltTestRunner`, shared `DaoTestSupport`, and five DAO test classes covering contacts, the audit log and its 500-entry cap, settings (including prepopulation via the production `SettingsPrepopulateCallback`), escalation events and the pending-restore row. |

The domain ports (`AudioPort`, `DndPort`, `TelephonyPort`, `AlertPort`,
`SchedulerPort`, `Clock`) exist precisely so the policy logic is testable on the
JVM without Robolectric or a device.

Per the contract, there are deliberately **no** tests that require a real
incoming PSTN call. That is what Test Mode on a real phone is for.

---

## Further reading

- [`Architecture.md`](Architecture.md) — the governing contract and Addendum A
- [`Permissions.md`](Permissions.md) — every permission, why, and how to grant it
- [`DeviceCompatibility.md`](DeviceCompatibility.md) — API 30–36 matrix, OEM caveats
- [`ImplementationPlan.md`](ImplementationPlan.md) — the phased build plan
