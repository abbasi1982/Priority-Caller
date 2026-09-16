package com.elham.priorityringer.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.elham.priorityringer.presentation.navigation.AppRoot
import com.elham.priorityringer.presentation.theme.PriorityRingerTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's only launcher Activity (Architecture.md § 12, § 16).
 *
 * `PriorityAlertActivity` is separate and **not exported**; this one is the sole
 * exported component.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            PriorityRingerTheme {
                AppRoot()
            }
        }
    }
}
