package com.elham.priorityringer.fake

import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.AuditLogEntry
import com.elham.priorityringer.domain.model.CallSnapshot
import com.elham.priorityringer.domain.model.CallState
import com.elham.priorityringer.domain.model.Capability
import com.elham.priorityringer.domain.model.CapabilityReport
import com.elham.priorityringer.domain.model.CapabilityStatus
import com.elham.priorityringer.domain.model.DndBypassStrategy
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.model.VolumeSnapshot
import com.elham.priorityringer.domain.port.AlertPort
import com.elham.priorityringer.domain.port.AudioPort
import com.elham.priorityringer.domain.port.CapabilityPort
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.port.DndPort
import com.elham.priorityringer.domain.port.RingtonePlayerPort
import com.elham.priorityringer.domain.port.SchedulerPort
import com.elham.priorityringer.domain.port.TelephonyPort
import com.elham.priorityringer.domain.repository.AuditRepository
import com.elham.priorityringer.domain.repository.ContactRepository
import com.elham.priorityringer.domain.repository.EscalationRepository
import com.elham.priorityringer.domain.repository.RestoreRepository
import com.elham.priorityringer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Hand-written fakes for every port and repository (Architecture.md § 14, § A.4).
 *
 * These are fakes, not mocks, on purpose. The behaviour under test is *ordering*
 * and *partial failure* — "was the snapshot persisted before the first device
 * mutation", "did a denied DND permission stop the volume change". A mock
 * verifies calls; a fake with real state lets the test read the device back and
 * see what it actually looks like afterwards, which is the thing the production
 * code itself is required to do (§ 6.2 forbids claiming success without a
 * read-back).
 *
 * Every fake is *controllable*: any outcome the real platform can produce —
 * `SILENT_NOT_OVERRIDDEN`, `VERIFICATION_FAILED`, `DND_BYPASS_INEFFECTIVE`,
 * fixed volume, denied policy access — can be forced from a test with one
 * assignment.
 */

// ---------------------------------------------------------------------------
// Call ordering
// ---------------------------------------------------------------------------

/**
 * Shared, ordered log of what the fakes were asked to do.
 *
 * Exists for exactly one requirement: Architecture.md § A.3's write-ahead
 * ordering — `capture → PERSIST → schedule watchdog → mutate`. That is an
 * ordering property, and ordering cannot be asserted on final state.
 *
 * Entries are prefixed by kind so a test can ask "did *any* device mutation
 * happen" ([deviceMutations]) without enumerating the ports. Repository writes
 * are [PERSIST], not [MUTATE]: `ApplyPriorityRingUseCase` records escalation
 * timestamps before it saves the snapshot, and that is not a device change.
 */
class CallRecorder {
    private val entries = mutableListOf<String>()

    /** Everything recorded so far, in order. */
    val calls: List<String> get() = entries.toList()

    /** Only changes to the actual device: audio, DND, alert. */
    val deviceMutations: List<String> get() = entries.filter { it.startsWith(MUTATE) }

    val persists: List<String> get() = entries.filter { it.startsWith(PERSIST) }

    fun record(name: String) {
        entries += name
    }

    fun reset() = entries.clear()

    fun indexOf(name: String): Int = entries.indexOf(name)

    operator fun contains(name: String): Boolean = entries.contains(name)

    /** `-1` when nothing has touched the device yet. */
    val firstDeviceMutationIndex: Int
        get() = entries.indexOfFirst { it.startsWith(MUTATE) }

    companion object {
        const val MUTATE = "mutate:"
        const val PERSIST = "persist:"
        const val SCHEDULE = "schedule:"

        const val SAVE_SNAPSHOT = "persist:restoreRepository.saveIfAbsent"
        const val CLEAR_SNAPSHOT = "persist:restoreRepository.clear"

        const val SET_RINGER = "mutate:audio.setRingerMode"
        const val SET_VOLUME_PERCENT = "mutate:audio.setRingVolumePercent"
        const val SET_VOLUME_RAW = "mutate:audio.setRingVolumeRaw"
        const val APPLY_BYPASS = "mutate:dnd.applyBypass"
        const val RESTORE_DND = "mutate:dnd.restore"
        const val SHOW_ALERT = "mutate:alert.showPriorityAlert"
        const val DISMISS_ALERT = "mutate:alert.dismissAlert"
        /**
         * The alarm-stream alert gets its own prefix, deliberately.
         *
         * [MUTATE] means "a setting belonging to the user was changed", and
         * that is what the restore tests assert the absence of. Playing and
         * stopping a sound this app started changes none of the user's
         * settings and leaves nothing to put back — a stop with nothing
         * playing is a no-op. Filing it under `mutate:` would make
         * "nothing on the device was touched" fail for an action that touched
         * nothing.
         */
        const val SOUND = "sound:"

        const val START_ALARM_ALERT = "sound:ringtonePlayer.startAlarmStreamAlert"
        const val STOP_ALARM_ALERT = "sound:ringtonePlayer.stopAlarmStreamAlert"

        const val SCHEDULE_WATCHDOG = "schedule:scheduler.scheduleRestoreWatchdog"
        const val CANCEL_WATCHDOG = "schedule:scheduler.cancelRestoreWatchdog"
    }
}

// ---------------------------------------------------------------------------
// Ports
// ---------------------------------------------------------------------------

/** Settable time source. Architecture.md § 14 requires this for window tests. */
class FakeClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowEpochMs(): Long = now

    fun advanceMinutes(minutes: Long) {
        now += minutes * 60_000L
    }

    fun advanceSeconds(seconds: Long) {
        now += seconds * 1_000L
    }
}

/**
 * In-memory `AudioManager`.
 *
 * Successful mutations really do change [ringerMode] / [currentVolumeIndex], so
 * a test can distinguish "the snapshot we persisted" from "the state we caused"
 * — which is the whole point of the overlapping-call rule in § A.3.
 */
class FakeAudioPort(private val recorder: CallRecorder = CallRecorder()) : AudioPort {

    var ringerMode: RingerMode = RingerMode.NORMAL
    var maxVolumeIndex: Int = 15

    /**
     * Keep this distinct from any volume a test puts in a snapshot. Several
     * assertions ("the original pre-mutation volume was retained", "the volume
     * really was restored") are only meaningful while the two differ.
     */
    var currentVolumeIndex: Int = 7

    /** § 7.1 — some routes report a fixed volume and cannot be changed at all. */
    var volumeFixed: Boolean = false

    /**
     * The device's vibrate-when-ringing preference, which decides whether a
     * ring index of 0 lands in VIBRATE or SILENT. Defaults to the AOSP default
     * (on), which is the configuration the Silent-to-Vibrate bug appeared in.
     */
    var vibrateWhenRinging: Boolean = true

    /**
     * When set, [setRingerMode] returns this instead of succeeding and the
     * device state is left alone. Use it to force `SILENT_NOT_OVERRIDDEN`,
     * `VERIFICATION_FAILED`, `SECURITY_EXCEPTION`, …
     */
    var setRingerModeResult: Outcome<RingerMode>? = null
    var setRingVolumePercentResult: Outcome<VolumeSnapshot>? = null
    var setRingVolumeRawResult: Outcome<VolumeSnapshot>? = null

    val ringerModeRequests = mutableListOf<RingerMode>()
    val volumePercentRequests = mutableListOf<Int>()
    val volumeRawRequests = mutableListOf<Int>()

    override fun currentRingerMode(): RingerMode = ringerMode

    override fun currentRingVolume(): VolumeSnapshot =
        VolumeSnapshot(current = currentVolumeIndex, max = maxVolumeIndex)

    override fun isVolumeFixed(): Boolean = volumeFixed

    override fun setRingerMode(mode: RingerMode): Outcome<RingerMode> {
        recorder.record(CallRecorder.SET_RINGER)
        ringerModeRequests += mode
        setRingerModeResult?.let { return it }
        ringerMode = mode
        return Outcome.Success(mode)
    }

    override fun setRingVolumePercent(percent: Int): Outcome<VolumeSnapshot> {
        recorder.record(CallRecorder.SET_VOLUME_PERCENT)
        volumePercentRequests += percent
        setRingVolumePercentResult?.let { return it }
        currentVolumeIndex = (percent * maxVolumeIndex) / 100
        // Same coupling as the raw path. Both end in setStreamVolume on the
        // real device, so a fake that coupled only one of them would be
        // modelling the API surface rather than the platform.
        applyRingerModeCoupling(currentVolumeIndex)
        return Outcome.Success(currentRingVolume())
    }

    override fun setRingVolumeRaw(index: Int): Outcome<VolumeSnapshot> {
        recorder.record(CallRecorder.SET_VOLUME_RAW)
        volumeRawRequests += index
        setRingVolumeRawResult?.let { return it }
        currentVolumeIndex = index
        applyRingerModeCoupling(index)
        return Outcome.Success(currentRingVolume())
    }

    /**
     * On Android the ring volume and the ringer mode are one setting, not two.
     * `AudioService.onSetStreamVolume` treats the ring index as the
     * silent/vibrate control: writing 0 drops the device into VIBRATE (or
     * SILENT, per the user's vibrate-when-ringing preference), and writing
     * above 0 lifts it out of silent into NORMAL.
     *
     * Modelled here because a fake that treats them as independent cannot fail
     * the way a phone does. It did not, and a real device came back from a call
     * on Vibrate after being left on Silent while every test stayed green.
     *
     * A fake is allowed to be simpler than the platform. It is not allowed to
     * be more forgiving than the platform on the exact axis under test.
     */
    private fun applyRingerModeCoupling(index: Int) {
        ringerMode = when {
            index <= 0 && vibrateWhenRinging -> RingerMode.VIBRATE
            index <= 0 -> RingerMode.SILENT
            else -> RingerMode.NORMAL
        }
    }
}

/** In-memory `NotificationManager` DND surface (§ 6, § A.1). */
class FakeDndPort(private val recorder: CallRecorder = CallRecorder()) : DndPort {

    var policyAccess: Boolean = true
    var filter: InterruptionFilter = InterruptionFilter.ALL
    var zenRuleId: String? = null

    /** Filter the device lands in when a bypass succeeds. */
    var bypassResultFilter: InterruptionFilter = InterruptionFilter.ALL

    /** Force a result — e.g. `DND_BYPASS_INEFFECTIVE` (§ 6.2, Android 15+). */
    var applyBypassResult: Outcome<InterruptionFilter>? = null
    var restoreResult: Outcome<Unit>? = null

    val applyBypassRequests = mutableListOf<DndBypassStrategy>()
    val restoreRequests = mutableListOf<Pair<InterruptionFilter, String?>>()

    override fun hasPolicyAccess(): Boolean = policyAccess

    override fun currentFilter(): InterruptionFilter = filter

    override fun applyBypass(strategy: DndBypassStrategy): Outcome<InterruptionFilter> {
        recorder.record(CallRecorder.APPLY_BYPASS)
        applyBypassRequests += strategy
        applyBypassResult?.let { return it }
        filter = bypassResultFilter
        return Outcome.Success(filter)
    }

    override fun restore(filter: InterruptionFilter, zenRuleId: String?): Outcome<Unit> {
        recorder.record(CallRecorder.RESTORE_DND)
        restoreRequests += filter to zenRuleId
        restoreResult?.let { return it }
        this.filter = filter
        this.zenRuleId = zenRuleId
        return Outcome.ok()
    }

    override fun activeZenRuleId(): String? = zenRuleId
}

/** Full-screen escalation alert (FR5). */
class FakeAlertPort(private val recorder: CallRecorder = CallRecorder()) : AlertPort {

    var showResult: Outcome<AlertPort.AlertMode> =
        Outcome.Success(AlertPort.AlertMode.FULL_SCREEN)

    val shownFor = mutableListOf<PriorityContact>()
    var dismissCount: Int = 0

    override fun showPriorityAlert(contact: PriorityContact): Outcome<AlertPort.AlertMode> {
        recorder.record(CallRecorder.SHOW_ALERT)
        shownFor += contact
        return showResult
    }

    override fun dismissAlert() {
        recorder.record(CallRecorder.DISMISS_ALERT)
        dismissCount++
    }
}

/**
 * The alarm-stream fallback (FR4 fallback).
 *
 * [startCount] and [stopCount] rather than a single boolean: the tests that
 * matter are about *how often* and *in what order*, not merely whether. An
 * alert started twice for one call is two ringtones on top of each other, and
 * an alert never stopped is a phone that will not go quiet.
 */
class FakeRingtonePlayerPort(
    private val recorder: CallRecorder = CallRecorder(),
) : RingtonePlayerPort {

    var audibility: RingtonePlayerPort.AlarmAudibility =
        RingtonePlayerPort.AlarmAudibility.AUDIBLE

    var startResult: Outcome<RingtonePlayerPort.AlertDelivery> =
        Outcome.Success(RingtonePlayerPort.AlertDelivery.IN_PROCESS)

    var startCount: Int = 0
    var stopCount: Int = 0

    /** True while an alert would be sounding, so a test can assert silence. */
    var playing: Boolean = false
        private set

    override fun alarmAudibility(): RingtonePlayerPort.AlarmAudibility = audibility

    override fun startAlarmStreamAlert(): Outcome<RingtonePlayerPort.AlertDelivery> {
        recorder.record(CallRecorder.START_ALARM_ALERT)
        startCount++
        if (startResult.isSuccess) playing = true
        return startResult
    }

    override fun stopAlarmStreamAlert() {
        recorder.record(CallRecorder.STOP_ALARM_ALERT)
        stopCount++
        playing = false
    }
}

/** WorkManager stand-in (§ A.2, § A.3 trigger 2). */
class FakeSchedulerPort(private val recorder: CallRecorder = CallRecorder()) : SchedulerPort {

    val scheduledSeconds = mutableListOf<Int>()
    var cancelCount: Int = 0

    val scheduleCount: Int get() = scheduledSeconds.size

    override fun scheduleRestoreWatchdog(afterSeconds: Int) {
        recorder.record(CallRecorder.SCHEDULE_WATCHDOG)
        scheduledSeconds += afterSeconds
    }

    override fun cancelRestoreWatchdog() {
        recorder.record(CallRecorder.CANCEL_WATCHDOG)
        cancelCount++
    }
}

/**
 * Telephony.
 *
 * [platformMatch] defaults to *never matching*: it is the last `||` branch in
 * `EvaluateIncomingCallUseCase`, and a permissive default would silently make
 * every miss into a match and hollow out the matching tests.
 */
class FakeTelephonyPort(
    var countryIso: String? = "US",
) : TelephonyPort {

    private val _callState = MutableSharedFlow<CallState>(replay = 1, extraBufferCapacity = 8)

    var platformMatch: (String, String) -> Boolean = { _, _ -> false }

    val platformMatchQueries = mutableListOf<Pair<String, String>>()

    /**
     * What a synchronous probe reports. Distinct from the [callState] flow: the
     * cold-start reconciliation path reads this *without* any emission having
     * happened, which is exactly the situation a just-restarted process is in.
     */
    var currentState: CallState = CallState.IDLE

    override fun callState(): Flow<CallState> = _callState.asSharedFlow()

    override fun currentCallState(): CallState = currentState

    override fun defaultCountryIso(): String? = countryIso

    override fun platformNumbersMatch(a: String, b: String): Boolean {
        platformMatchQueries += a to b
        return platformMatch(a, b)
    }

    suspend fun emitCallState(state: CallState) {
        _callState.emit(state)
    }
}

/** Capability probe (FR6, FR8). Everything granted unless a test says otherwise. */
class FakeCapabilityPort(
    var nextReport: CapabilityReport = allGrantedReport(),
) : CapabilityPort {

    override fun report(): CapabilityReport = nextReport

    companion object {
        fun allGrantedReport(
            state: CapabilityStatus.State = CapabilityStatus.State.GRANTED,
            sdkInt: Int = 34,
            targetSdk: Int = 34,
            ringerMode: RingerMode = RingerMode.NORMAL,
            filter: InterruptionFilter = InterruptionFilter.ALL,
            volumeFixed: Boolean = false,
        ): CapabilityReport = CapabilityReport(
            statuses = Capability.entries.map { capability ->
                CapabilityStatus(
                    capability = capability,
                    state = state,
                    consequence = "test",
                )
            },
            sdkInt = sdkInt,
            targetSdk = targetSdk,
            currentRingerMode = ringerMode,
            currentInterruptionFilter = filter,
            isVolumeFixed = volumeFixed,
            currentRingVolume = VolumeSnapshot(current = 7, max = 15),
        )
    }
}

// ---------------------------------------------------------------------------
// Repositories
// ---------------------------------------------------------------------------

class FakeContactRepository(initial: List<PriorityContact> = emptyList()) : ContactRepository {

    private val state = MutableStateFlow(initial)
    private var nextId: Long = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    val contacts: List<PriorityContact> get() = state.value

    override fun observeAll(): Flow<List<PriorityContact>> = state.asStateFlow()

    override fun observeEnabled(): Flow<List<PriorityContact>> =
        state.map { list -> list.filter { it.enabled } }

    override suspend fun getAll(): List<PriorityContact> = state.value

    override suspend fun getEnabled(): List<PriorityContact> = state.value.filter { it.enabled }

    override suspend fun getById(id: Long): PriorityContact? =
        state.value.firstOrNull { it.id == id }

    /** Mirrors the unique index in § 10: a colliding `matchKey` yields `null`. */
    override suspend fun add(contact: PriorityContact): Long? {
        if (state.value.any { it.matchKey == contact.matchKey }) return null
        val id = nextId++
        state.value = state.value + contact.copy(id = id)
        return id
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        state.value = state.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun remove(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }

    fun seed(vararg contacts: PriorityContact) {
        state.value = contacts.toList()
    }
}

/**
 * Audit log.
 *
 * Deliberately records **everything**, with no `loggingEnabled` filtering. The
 * filtering is the real repository's job and re-implementing it here would only
 * make assertions depend on a setting no test under this package is about.
 */
class FakeAuditRepository : AuditRepository {

    /** Chronological — oldest first. */
    private val state = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    private var nextId: Long = 1L

    /** When true, [log] throws. Proves the coordinator's `runCatching` guard. */
    var failOnLog: Boolean = false

    val entries: List<AuditLogEntry> get() = state.value

    val types: List<AuditEventType> get() = state.value.map { it.type }

    override fun observeRecent(limit: Int): Flow<List<AuditLogEntry>> =
        state.map { list -> list.asReversed().take(limit) }

    /**
     * Mirrors `AuditRepositoryImpl`, which selects on
     * `AuditEventType.raisesPersistentBanner` rather than on ERROR severity.
     * The distinction is load-bearing: `DND_BYPASS_INEFFECTIVE` and
     * `SILENT_NOT_OVERRIDDEN` are only WARNING, yet they must raise the
     * Dashboard banner. A fake filtering on severity would pass tests that the
     * real repository fails.
     *
     * It also mirrors the resolve rule: the newest entry of *either* kind
     * decides, so a successful alarm-stream fallback clears the banner a
     * failed ringer change raised moments earlier. A fake that only looked for
     * raising entries would leave the banner up and never notice.
     */
    override fun observeLatestFailure(): Flow<AuditLogEntry?> =
        state.map { list ->
            list.lastOrNull {
                it.type.raisesPersistentBanner || it.type.resolvesPersistentBanner
            }?.takeIf { it.type.raisesPersistentBanner }
        }

    override suspend fun log(
        type: AuditEventType,
        message: String,
        relatedContactId: Long?,
        recoverable: Boolean,
    ) {
        if (failOnLog) error("audit log unavailable")
        state.value = state.value + AuditLogEntry(
            id = nextId++,
            timestampEpochMs = nextId,
            type = type,
            message = message,
            relatedContactId = relatedContactId,
            recoverable = recoverable,
        )
    }

    override suspend fun clear() {
        state.value = emptyList()
    }

    fun hasType(type: AuditEventType): Boolean = types.contains(type)

    fun countOf(type: AuditEventType): Int = types.count { it == type }

    /** Earliest entry of [type], or `null`. */
    fun firstOf(type: AuditEventType): AuditLogEntry? = entries.firstOrNull { it.type == type }

    fun lastOf(type: AuditEventType): AuditLogEntry? = entries.lastOrNull { it.type == type }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {

    private val state = MutableStateFlow(initial)

    /** When true, [get] throws — used to drive an exception into the apply path. */
    var failOnGet: Boolean = false

    override fun observe(): Flow<AppSettings> = state.asStateFlow()

    override suspend fun get(): AppSettings {
        if (failOnGet) error("settings unavailable")
        return state.value
    }

    /** Clamps, exactly as the real repository is contracted to (§ 4). */
    override suspend fun update(settings: AppSettings) {
        state.value = settings.validated()
    }

    /**
     * Single-field write, mirroring the real repository: a device observation
     * must not round-trip the user's settings.
     */
    override suspend fun recordAudibleRingIndex(index: Int) {
        state.value = state.value.copy(lastAudibleRingIndex = index)
        recordedAudibleIndices += index
    }

    /** Every index recorded, in order, so tests can assert what was learned. */
    val recordedAudibleIndices = mutableListOf<Int>()

    /** Sets without clamping, so a test can install deliberately odd values. */
    fun set(settings: AppSettings) {
        state.value = settings
    }
}

/**
 * Durable pre-mutation snapshot (§ A.3).
 *
 * [saveIfAbsent] keeps the insert-if-absent contract: a second overlapping call
 * must not overwrite the genuine pre-mutation state with values the app itself
 * just wrote.
 */
class FakeRestoreRepository(private val recorder: CallRecorder = CallRecorder()) :
    RestoreRepository {

    private val state = MutableStateFlow<CallSnapshot?>(null)

    var saveAttempts: Int = 0
    var clearCount: Int = 0

    val pending: CallSnapshot? get() = state.value

    override suspend fun saveIfAbsent(snapshot: CallSnapshot): Boolean {
        recorder.record(CallRecorder.SAVE_SNAPSHOT)
        saveAttempts++
        if (state.value != null) return false
        state.value = snapshot
        return true
    }

    override suspend fun getPending(): CallSnapshot? = state.value

    override fun observePending(): Flow<CallSnapshot?> = state.asStateFlow()

    override suspend fun clear() {
        recorder.record(CallRecorder.CLEAR_SNAPSHOT)
        clearCount++
        state.value = null
    }

    /**
     * Put a snapshot on "disk" without going through [saveIfAbsent].
     * Models a previous process killed mid-call. Null clears.
     */
    fun seed(snapshot: CallSnapshot?) {
        state.value = snapshot
    }
}

class FakeEscalationRepository : EscalationRepository {

    private val byKey = mutableMapOf<String, MutableList<Long>>()

    /** Drive an exception into `ApplyPriorityRingUseCase` (it reads first). */
    var failOnTimestampsSince: Boolean = false

    val recorded = mutableListOf<Pair<String, Long>>()
    val pruneRequests = mutableListOf<Long>()

    override suspend fun record(matchKey: String, timestampEpochMs: Long) {
        recorded += matchKey to timestampEpochMs
        byKey.getOrPut(matchKey) { mutableListOf() } += timestampEpochMs
    }

    override suspend fun timestampsSince(matchKey: String, sinceEpochMs: Long): List<Long> {
        if (failOnTimestampsSince) error("escalation store unavailable")
        return byKey[matchKey].orEmpty().filter { timestamp -> timestamp >= sinceEpochMs }
    }

    override suspend fun pruneBefore(epochMs: Long) {
        pruneRequests += epochMs
        byKey.values.forEach { list -> list.removeAll { timestamp -> timestamp < epochMs } }
    }

    override suspend fun clear() {
        byKey.clear()
    }

    fun seed(matchKey: String, vararg timestamps: Long) {
        byKey.getOrPut(matchKey) { mutableListOf() } += timestamps.toList()
    }

    fun timestampsFor(matchKey: String): List<Long> = byKey[matchKey].orEmpty().toList()
}

// ---------------------------------------------------------------------------
// Test data builders
// ---------------------------------------------------------------------------

/**
 * A contact whose [PriorityContact.matchKey] is derived the same way the real
 * add-path derives it: the trailing 7 digits (`SUFFIX_MATCH_DIGITS`).
 */
fun testContact(
    id: Long = 1L,
    displayName: String = "Mum",
    originalInput: String = "+15551234567",
    matchKey: String = originalInput.filter { it.isDigit() }.takeLast(7),
    e164: String? = originalInput.takeIf { it.startsWith("+") },
    enabled: Boolean = true,
    createdAtEpochMs: Long = 0L,
): PriorityContact = PriorityContact(
    id = id,
    displayName = displayName,
    originalInput = originalInput,
    matchKey = matchKey,
    e164 = e164,
    enabled = enabled,
    createdAtEpochMs = createdAtEpochMs,
)

fun testSnapshot(
    ringerMode: RingerMode = RingerMode.VIBRATE,
    ringVolume: VolumeSnapshot = VolumeSnapshot(current = 4, max = 15),
    interruptionFilter: InterruptionFilter = InterruptionFilter.PRIORITY,
    zenRuleId: String? = null,
    capturedAtEpochMs: Long = 1_700_000_000_000L,
    expiresAtEpochMs: Long = capturedAtEpochMs + 90_000L,
): CallSnapshot = CallSnapshot(
    ringerMode = ringerMode,
    ringVolume = ringVolume,
    interruptionFilter = interruptionFilter,
    zenRuleId = zenRuleId,
    capturedAtEpochMs = capturedAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
)
