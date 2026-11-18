package com.orbising.orbis.plugintest

import android.app.Activity
import android.os.Bundle

/** Foreground entry used to exercise activation of a stopped synthetic plugin. */
class FixtureActivationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
