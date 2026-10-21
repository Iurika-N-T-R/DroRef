package org.droref

import android.net.Uri
import android.provider.OpenableColumns
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/** Images dragged out of DroRef: readable by the receiving app, and nothing outside the scenes folder is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilesProviderTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val authority = "${ctx.packageName}.files"
    private val provider = Robolectric.setupContentProvider(FilesProvider::class.java, authority)

    @Test
    fun servesBoardImages() {
        val f = File(ctx.filesDir, "scenes/abc/images/img1").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = Uri.parse("content://$authority/abc/images/img1?name=sky.jpg")
        provider.query(uri, null, null, null, null)!!.use {
            it.moveToFirst()
            assertEquals("sky.jpg", it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(3L, it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val bytes = provider.openFile(uri, "r").let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
        assertArrayEquals(f.readBytes(), bytes)
    }

    @Test
    fun refusesEverythingElse() {
        File(ctx.filesDir.parentFile, "shared_prefs/droref.xml").apply { parentFile!!.mkdirs(); writeText("secret") }
        File(ctx.filesDir, "scenes").mkdirs()
        for (path in listOf("../../shared_prefs/droref.xml", "..%2F..%2Fshared_prefs%2Fdroref.xml", "", "abc")) {
            val uri = Uri.parse("content://$authority/$path")
            assertNull(path, provider.query(uri, null, null, null, null))
            try {
                provider.openFile(uri, "r")
                fail("opened $path")
            } catch (e: FileNotFoundException) {
                // expected
            }
        }
    }
}
