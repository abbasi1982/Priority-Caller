package com.elham.priorityringer.data.platform

import com.elham.priorityringer.domain.port.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wall-clock time.
 *
 * Wall clock rather than elapsed-realtime because escalation windows and audit
 * timestamps are both shown to the user, who reasons in "he rang twice in the
 * last five minutes". The cost is that a clock change can distort a window;
 * `EscalationPolicy` guards the dangerous direction by ignoring future-dated
 * timestamps, so a backwards jump cannot manufacture an escalation.
 */
@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
