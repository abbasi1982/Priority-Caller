package com.elham.priorityringer

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that substitutes [HiltTestApplication] for the real
 * `@HiltAndroidApp` application (Architecture.md § 11, § 14).
 *
 * Referenced by `app/build.gradle.kts` as
 * `testInstrumentationRunner = "com.elham.priorityringer.HiltTestRunner"`, so
 * the fully-qualified name below must not change without changing that line
 * too.
 *
 * Note that the DAO tests in `data/local` deliberately do **not** use Hilt —
 * they build an in-memory database directly, so they stay independent of the
 * DI graph and keep working while `DatabaseModule` is still in flux. This
 * runner exists for the Hilt repository and Compose tests that come later.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
