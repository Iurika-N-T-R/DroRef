package org.droref

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only access to board images for other apps, so an image dragged out of DroRef can be dropped into them.
 * content://<package>.files/<scene folder>/<image file>?name=<file name to show>
 * Only files inside the scenes folder are served, and only to apps the drag granted access to.
 */
class FilesProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val root = File(context!!.filesDir, "scenes").canonicalFile
        val f = File(root, uri.path.orEmpty().trimStart('/')).canonicalFile
        return f.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    override fun getType(uri: Uri) = file(uri)?.let { imageMime(it) ?: "video/mp4" }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri) ?: throw FileNotFoundException(uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> uri.getQueryParameter("name") ?: f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}

/** The picture under the finger while dragging an image out. */
class ThumbShadow(private val bmp: Bitmap, maxSide: Int) : View.DragShadowBuilder() {
    private val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
    private val w = maxOf(1, (bmp.width * k).toInt())
    private val h = maxOf(1, (bmp.height * k).toInt())

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(w, h)
        outShadowTouchPoint.set(w / 2, h / 2)
    }

    override fun onDrawShadow(canvas: Canvas) = canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), null)
}
