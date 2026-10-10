package com.sohailsquad.animalhealthbridge.ml.llm

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RestoreLocalModelDeviceTest {
    @Test
    fun restoreFromDeviceBackup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restoreLocalModel") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val source = "/data/local/tmp/geniex/qwen3_1_7b"
        fun shellText(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }

        val names = shellText("ls -1 $source").lines()
        assertTrue(names.containsAll(listOf("config.json", "tokenizer.json")))
        assertTrue(names.all { it.matches(Regex("[a-zA-Z0-9._-]+")) && it != "." && it != ".." })
        val external = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val destination = File(external, "geniex/qwen3_1_7b")
        assertTrue(destination.mkdirs() || destination.isDirectory)
        for (name in names) {
            val expectedSize = shellText("stat -c %s $source/$name").toLong()
            val target = File(destination, name)
            if (target.exists()) {
                assertEquals("Refusing to overwrite incomplete $name", expectedSize, target.length())
                assertTrue("Unreadable restored file $name", target.isFile && target.canRead())
                continue
            }
            assertTrue("Cannot create app-owned $name", target.createNewFile())
            shellText("cp $source/$name ${target.absolutePath}")
            val copied = target.length()
            assertEquals(name, expectedSize, copied)
            assertTrue("Unreadable restored file $name", target.isFile && target.canRead())
            Log.i("AhbModelTest", "Restored $name ($copied bytes) into app-owned storage")
        }
    }
}
