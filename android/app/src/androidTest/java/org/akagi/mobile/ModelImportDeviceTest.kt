package org.akagi.mobile

import android.content.Context
import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.akagi.mobile.engine.ModelRepository
import org.akagi.mobile.engine.MortalSession
import org.akagi.mobile.ui.UiModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real converted checkpoints, ORT inference and persistent independent selections. */
@RunWith(AndroidJUnit4::class)
class ModelImportDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun importingEitherModeChangesOnlyThatModesScoresAndPersistsAcrossRecreation() {
        val directory = File(context.cacheDir, "model-test-${UUID.randomUUID()}")
        try {
            val repository = ModelRepository(context, directory)
            MortalSession(context, repository).use { session ->
                val baseline4 = session.offlineReplay(4).recommended.score
                val baseline3 = session.offlineReplay(3).recommended.score
                import(repository, 4)
                assertTrue(repository.choice(4).custom)
                assertFalse(repository.choice(3).custom)
                assertEquals(baseline4 + 1f, session.offlineReplay(4).recommended.score, .0005f)
                assertEquals(baseline3, session.offlineReplay(3).recommended.score, .0005f)
                import(repository, 3)
                assertTrue(repository.choice(4).custom)
                assertTrue(repository.choice(3).custom)
                assertEquals(baseline3 + 1f, session.offlineReplay(3).recommended.score, .0005f)
                val check = session.modelCheck()
                assertTrue(check.detail, check.ok)
                val reopened = ModelRepository(context, directory)
                assertEquals("Replacement 4p", reopened.choice(4).name)
                assertEquals("Replacement 3p", reopened.choice(3).name)
                MortalSession(context, reopened).use { restored ->
                    assertEquals(baseline4 + 1f, restored.offlineReplay(4).recommended.score, .0005f)
                    assertEquals(baseline3 + 1f, restored.offlineReplay(3).recommended.score, .0005f)
                }
                repository.useBundled(4)
                session.modelChanged(4)
                repository.prune(4)
                assertFalse(repository.choice(4).custom)
                assertTrue(repository.choice(3).custom)
                assertEquals(baseline4, session.offlineReplay(4).recommended.score, .0005f)
                assertEquals(baseline3 + 1f, session.offlineReplay(3).recommended.score, .0005f)
                repository.useBundled(3)
                session.modelChanged(3)
                assertEquals(baseline3, session.offlineReplay(3).recommended.score, .0005f)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidImportsKeepBothExistingModelsAndRemoveIncompleteFiles() {
        val directory = File(context.cacheDir, "model-test-${UUID.randomUUID()}")
        try {
            val repository = ModelRepository(context, directory)
            import(repository, 4)
            import(repository, 3)
            val four = repository.choice(4)
            val three = repository.choice(3)
            val wrongMode = bundle(3)
            val original = bundle(4)
            val badHash = rewrite(original, "manifest.json") { bytes ->
                JSONObject(String(bytes)).put("sha256", "0".repeat(64)).toString().toByteArray()
            }
            val invalidGraph = rewrite(original, "model.onnx") { bytes -> bytes.copyOf().also { it[0] = 0 } }
            val wrongScores = rewrite(original, "reference.json") { bytes ->
                JSONObject(String(bytes)).also { reference ->
                    val case = reference.getJSONArray("cases").getJSONObject(0)
                    val scores = case.getJSONArray("q_values")
                    val index = case.getInt("argmax")
                    scores.put(index, scores.getDouble(index) + 5.0)
                }.toString().toByteArray()
            }.let { bytes ->
                val hash = ModelRepository.sha256(entry(bytes, "reference.json"))
                rewrite(bytes, "manifest.json") { JSONObject(String(it)).put("reference_sha256", hash).toString().toByteArray() }
            }
            val traversal = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("../escape.onnx"))
                    zip.write(byteArrayOf(1))
                    zip.closeEntry()
                }
            }.toByteArray()
            for (candidate in listOf(wrongMode, badHash, invalidGraph, wrongScores, traversal, original.copyOf(100))) {
                val failure = runCatching { repository.prepareImport(4, ByteArrayInputStream(candidate)).use { repository.apply(it) } }.exceptionOrNull()
                assertTrue("Invalid import was accepted", failure != null)
                assertEquals(four, repository.choice(4))
                assertEquals(three, repository.choice(3))
                assertEquals(1, File(directory, "4p").listFiles()!!.count { it.isDirectory })
                assertEquals(1, File(directory, "3p").listFiles()!!.count { it.isDirectory })
            }
            // Cancellation after a fully checked import still leaves both selections alone.
            repository.prepareImport(4, ByteArrayInputStream(original)).close()
            assertEquals(four, repository.choice(4))
            assertEquals(three, repository.choice(3))
            assertEquals(1, File(directory, "4p").listFiles()!!.count { it.isDirectory })
        } finally { directory.deleteRecursively() }
    }

    @Test fun changingThreePlayerModelLeavesAFourPlayerDecisionIntactAndFourPlayerReloadUsesIt() {
        val directory = File(context.cacheDir, "model-test-${UUID.randomUUID()}")
        try {
            val repository = ModelRepository(context, directory)
            MortalSession(context, repository).use { session ->
                context.assets.open("models/smoke_4p.jsonl").bufferedReader().useLines { lines ->
                    lines.filter(String::isNotBlank).forEach { session.acceptMjai(it) }
                }
                val original = session.recomputePending()!!
                import(repository, 3)
                assertFalse(session.modelChanged(3))
                assertEquals(original.recommended.score, session.recomputePending()!!.recommended.score, .0005f)
                import(repository, 4)
                assertTrue(session.modelChanged(4))
                val replaced = session.recomputePending()!!
                assertEquals(original.legalMask, replaced.legalMask)
                assertEquals(original.recommended.eventJson, replaced.recommended.eventJson)
                assertEquals(original.recommended.score + 1f, replaced.recommended.score, .0005f)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun coordinatorImportsOneModeAtATimeAndPersistsItsSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        val copy4 = File(context.cacheDir, "import-4p-${UUID.randomUUID()}.akagimodel")
        val copy3 = File(context.cacheDir, "import-3p-${UUID.randomUUID()}.akagimodel")
        copy4.writeBytes(bundle(4))
        copy3.writeBytes(bundle(3))
        lateinit var analysis: AnalysisViewModel
        instrumentation.runOnMainSync {
            analysis = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AnalysisViewModel::class.java]
        }
        try {
            instrumentation.runOnMainSync { analysis.importModel(4, Uri.fromFile(copy4)) }
            waitForModel(analysis, 4) { it.custom && !it.busy }
            assertEquals("Replacement 4p", analysis.state.value.fourPlayerModel.name)
            assertFalse(analysis.state.value.threePlayerModel.custom)
            instrumentation.runOnMainSync { analysis.importModel(3, Uri.fromFile(copy3)) }
            waitForModel(analysis, 3) { it.custom && !it.busy }
            assertEquals("Replacement 4p", analysis.state.value.fourPlayerModel.name)
            assertEquals("Replacement 3p", analysis.state.value.threePlayerModel.name)
            instrumentation.runOnMainSync { analysis.importModel(4, Uri.fromFile(copy3)) }
            waitForModel(analysis, 4) { !it.busy && it.message?.startsWith("Model was not changed.") == true }
            assertEquals("Replacement 4p", analysis.state.value.fourPlayerModel.name)
            assertEquals("Replacement 3p", analysis.state.value.threePlayerModel.name)
            assertEquals("Replacement 4p", ModelRepository(context).choice(4).name)
            assertEquals("Replacement 3p", ModelRepository(context).choice(3).name)
            instrumentation.runOnMainSync { analysis.useBundledModel(4) }
            waitForModel(analysis, 4) { !it.custom && !it.busy }
            assertEquals("Replacement 3p", analysis.state.value.threePlayerModel.name)
        } finally {
            try {
                for (players in listOf(4, 3)) {
                    waitForModel(analysis, players) { !it.busy }
                    instrumentation.runOnMainSync { analysis.useBundledModel(players) }
                    waitForModel(analysis, players) { !it.custom && !it.busy }
                }
            } finally {
                instrumentation.runOnMainSync { store.clear() }
                copy4.delete()
                copy3.delete()
            }
        }
    }

    private fun waitForModel(analysis: AnalysisViewModel, players: Int, condition: (UiModel) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            val value = if (players == 4) analysis.state.value.fourPlayerModel else analysis.state.value.threePlayerModel
            if (condition(value)) return
            Thread.sleep(50)
        }
        error("Model change timed out: ${analysis.state.value}")
    }

    private fun import(repository: ModelRepository, players: Int) {
        repository.prepareImport(players, ByteArrayInputStream(bundle(players))).use { repository.apply(it) }
    }

    private fun bundle(players: Int): ByteArray = context.assets.open("model-import/replacement-${players}p.akagimodel").use { it.readBytes() }

    private fun entry(bytes: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: error("Missing fixture entry")
                if (item.name == name) return zip.readBytes()
            }
        }
    }

    private fun rewrite(bytes: ByteArray, name: String, transform: (ByteArray) -> ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { dest ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
                while (true) {
                    val item = source.nextEntry ?: break
                    dest.putNextEntry(ZipEntry(item.name))
                    val content = source.readBytes()
                    dest.write(if (item.name == name) transform(content) else content)
                    dest.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
