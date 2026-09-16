package com.elham.priorityringer.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.elham.priorityringer.R
import com.elham.priorityringer.presentation.theme.PriorityRingerTheme

/**
 * Full-screen escalation alert (Architecture.md § 8, FR5).
 *
 * Launched by the `AlertPort` implementation in `data` through a high-priority
 * notification's `setFullScreenIntent`. It is **informational only**: § 8 and
 * § 18 both forbid answering or rejecting the cellular call, and there is no
 * supported API to do so from a non-dialer app. The system InCallUI remains the
 * place the call is actually handled — this screen only makes sure the user
 * notices it.
 *
 * Not exported. Everything it renders arrives as intent extras so the activity
 * needs no repository access and can show instantly on a locked screen.
 */
class PriorityAlertActivity : ComponentActivity() {

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = finish()
    }

    /** Snapshot-backed so a second escalation recomposes with the new caller. */
    private var currentIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set in code as well as in the manifest: the manifest attributes are
        // owned by another module, and a missing attribute here would silently
        // turn the escalation alert into a notification nobody sees.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(ACTION_DISMISS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        currentIntent = intent

        setContent {
            PriorityRingerTheme {
                // Read through the observable field, not the captured `intent`:
                // the manifest declares singleInstance, so a second escalation
                // reuses this instance and arrives via onNewIntent. A stale
                // caller name on a priority alert is exactly the failure this
                // screen exists to prevent.
                val active = currentIntent
                PriorityAlertContent(
                    contactName = active?.getStringExtra(EXTRA_CONTACT_NAME)
                        ?.takeIf { it.isNotBlank() },
                    redactedNumber = active?.getStringExtra(EXTRA_CONTACT_NUMBER)
                        ?.takeIf { it.isNotBlank() },
                    onDismiss = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntent = intent
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dismissReceiver) }
        super.onDestroy()
    }

    companion object {
        /** Display name of the matched contact. Pinned by `AndroidAlertPort`. */
        const val EXTRA_CONTACT_NAME = "extra_contact_name"

        /**
         * Already-redacted number (`PriorityContact.redactedNumber`, e.g.
         * "•••4567"). The full number is deliberately never put in an intent —
         * § 13 keeps whole numbers out of anything that can end up in a log.
         */
        const val EXTRA_CONTACT_NUMBER = "extra_contact_number"

        /**
         * Broadcast (not exported) that closes an open alert. `AlertPort`'s
         * `dismissAlert()` should send this alongside cancelling the
         * notification, so a restore triggered by call-end also takes the
         * full-screen UI down.
         */
        const val ACTION_DISMISS = "com.elham.priorityringer.action.DISMISS_PRIORITY_ALERT"

        /** Convenience factory; `AndroidAlertPort` may build the intent itself. */
        fun newIntent(
            context: Context,
            contactName: String,
            redactedNumber: String,
        ): Intent = Intent(context, PriorityAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_CONTACT_NAME, contactName)
            .putExtra(EXTRA_CONTACT_NUMBER, redactedNumber)
    }
}

@Composable
private fun PriorityAlertContent(
    contactName: String?,
    redactedNumber: String?,
    onDismiss: () -> Unit,
) {
    val palette = PriorityRingerTheme.status

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.inertContainer,
        contentColor = palette.onInertContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.NotificationsActive,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = palette.onInertContainer,
            )
            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(
                    R.string.alert_title,
                    contactName ?: stringResource(R.string.alert_unknown_contact),
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            if (redactedNumber != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = redactedNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.alert_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.inert,
                    contentColor = palette.onInert,
                ),
            ) {
                Text(stringResource(R.string.alert_dismiss))
            }
        }
    }
}

@Preview
@Composable
private fun PriorityAlertPreview() {
    PriorityRingerTheme {
        PriorityAlertContent(contactName = "Mum", redactedNumber = "•••0123", onDismiss = {})
    }
}
