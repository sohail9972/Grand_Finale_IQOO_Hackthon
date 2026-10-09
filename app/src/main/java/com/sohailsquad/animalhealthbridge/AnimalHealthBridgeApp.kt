package com.sohailsquad.animalhealthbridge

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.repository.AnimalRepository
import com.sohailsquad.animalhealthbridge.ml.llm.LlmEngine
import com.sohailsquad.animalhealthbridge.ml.translation.Translator

/**
 * Application subclass and the app's manual dependency graph.
 *
 * Manual DI (rather than Hilt) is a deliberate choice from the tech stack: with
 * a 35-hour budget, a graph a judge can read top-to-bottom is worth more than
 * an annotation processor's worth of build time.
 *
 * Everything is lazy. Nothing is constructed until a screen asks for it, which
 * matters now that the graph includes multi-hundred-megabyte model engines:
 * opening the app must not load Gemma just to show the language picker.
 */
class AnimalHealthBridgeApp : Application() {

    /**
     * The Room database.
     *
     * `by lazy` + [AppDatabase.get] means the first screen that needs data pays
     * the construction cost, and the singleton guarantees one shared connection.
     */
    val database: AppDatabase by lazy { AppDatabase.get(this) }

    /** The single animal repository for the process. */
    val animalRepository: AnimalRepository by lazy {
        AnimalRepository(database.animalDao())
    }

    /**
     * Hindi to English translation.
     *
     * Lazy and process-wide: the ONNX sessions are expensive to build and are
     * thread-safe to reuse, so they are shared rather than rebuilt per screen.
     */
    val translator: Translator by lazy { Translator(this) }

    /**
     * Case structuring.
     *
     * Also lazy and process-wide. Gemma loads on first use, which is why the
     * case-review screen shows a "Structuring..." state rather than blocking
     * app start.
     */
    val llmEngine: LlmEngine by lazy { LlmEngine(this) }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Animal Health Bridge starting - offline-first, on-device only")
    }

    private companion object {
        const val TAG = "AnimalHealthBridge"
    }
}

/**
 * Finds the [AnimalHealthBridgeApp] behind any context.
 *
 * Compose hands us the Activity context, and an Activity's `baseContext` is a
 * framework `ContextImpl` rather than the Application - so walking the wrapper
 * chain alone never reaches the Application and would throw. Asking for
 * [Context.getApplicationContext] first is the reliable route, with the wrapper
 * walk kept as a fallback for unusual wrapped contexts.
 *
 * The cast is safe because the manifest declares this class as the application
 * (`android:name=".AnimalHealthBridgeApp"`), but a misconfigured manifest still
 * reports clearly rather than surfacing a ClassCastException from a screen.
 */
fun Context.app(): AnimalHealthBridgeApp {
    (applicationContext as? AnimalHealthBridgeApp)?.let { return it }

    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is AnimalHealthBridgeApp) return context
        context = context.baseContext
    }

    error("AnimalHealthBridgeApp is not installed; check AndroidManifest android:name")
}