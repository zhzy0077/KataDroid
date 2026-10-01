package com.example.katadroid

import android.app.LocaleManager
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Exercise a chosen translation without changing the device's system language. */
class AppLocaleRule(private val language: String) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val manager = instrumentation.targetContext.getSystemService(LocaleManager::class.java)
            val previous = manager.applicationLocales
            instrumentation.runOnMainSync { manager.applicationLocales = LocaleList.forLanguageTags(language) }
            try { base.evaluate() }
            finally { instrumentation.runOnMainSync { manager.applicationLocales = previous } }
        }
    }
}
