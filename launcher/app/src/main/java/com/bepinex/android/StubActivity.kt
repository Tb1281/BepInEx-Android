package com.bepinex.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Placeholder Activity declared in AndroidManifest.xml.
 *
 * Used by [com.bepinex.android.fusion.InstrumentationHooks] to pass Android AMS checks
 * for unregistered target game activities, which are dynamically instantiated at runtime.
 */
class StubActivity : Activity() {
    override fun onCreate(bundle: Bundle?) {
        super.onCreate(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
