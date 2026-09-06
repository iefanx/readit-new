package com.iefan.readout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.iefan.readout.tts.DocumentParser
import com.iefan.readout.tts.ReadoutTtsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationRegressionTest {
    @Test fun oldDocumentResponseIsDiscarded() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<String?>()
        val engine = ReadoutTtsEngine(ApplicationProvider.getApplicationContext<Context>()) { _, _ ->
            withContext(NonCancellable) { response.await() }
        }
        try {
            engine.loadDocument(1, DocumentParser.parse("First book."), "First")
            engine.setTranslationTargetLang("es")
            runCurrent()
            engine.loadDocument(2, DocumentParser.parse("Second book."), "Second")
            response.complete("Libro primero.")
            runCurrent()
            assertTrue(engine.translatedSentences.value.isEmpty())
        } finally { engine.shutdown(); Dispatchers.resetMain() }
    }

    @Test fun failedTranslationCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var answer: String? = null
        val engine = ReadoutTtsEngine(ApplicationProvider.getApplicationContext<Context>()) { _, _ -> answer }
        try {
            engine.loadDocument(1, DocumentParser.parse("Hello world."), "Test")
            engine.setTranslationTargetLang("es")
            runCurrent()
            assertTrue(engine.translationErrors.value.containsKey(0))
            assertTrue(engine.translatedSentences.value.isEmpty())
            answer = "Hola mundo."
            engine.retryTranslation(0)
            runCurrent()
            assertEquals("Hola mundo.", engine.translatedSentences.value[0])
            assertTrue(engine.translationErrors.value.isEmpty())
        } finally { engine.shutdown(); Dispatchers.resetMain() }
    }

    @Test fun languageSwitchRejectsOldLanguage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val delayed = CompletableDeferred<String?>()
        val engine = ReadoutTtsEngine(ApplicationProvider.getApplicationContext<Context>()) { _, lang ->
            if (lang == "es") withContext(NonCancellable) { delayed.await() } else "नमस्ते।"
        }
        try {
            engine.loadDocument(1, DocumentParser.parse("Hello."), "Test")
            engine.setTranslationTargetLang("es")
            runCurrent()
            engine.setTranslationTargetLang("hi")
            runCurrent()
            delayed.complete("Hola.")
            runCurrent()
            assertEquals("नमस्ते।", engine.translatedSentences.value[0])
        } finally { engine.shutdown(); Dispatchers.resetMain() }
    }
}
