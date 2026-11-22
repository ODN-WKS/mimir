package io.github.odnwks.mimir.standalone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** R30: pictures for the PC are made small and carry no location or camera details. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R30ShrinkTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun photoWithLocation(w: Int, h: Int): File {
        val f = tmp.newFile("big.jpg")
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xff3366cc.toInt()) }
            .compress(Bitmap.CompressFormat.JPEG, 90, f.outputStream())
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "39/1,43/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,50/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            saveAttributes()
        }
        // The test picture really has what must go.
        assertNotNull(ExifInterface(f.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        return f
    }

    @Test fun aBigPhotoForThePcIsAtMost1568OnItsLongSideAndKeepsNoLocation() {
        val f = photoWithLocation(3200, 2400)
        val got = Pictures.photo(ApplicationProvider.getApplicationContext(), Uri.fromFile(f), "Photo", Pictures.PC_SIDE)!!
        val b = BitmapFactory.decodeByteArray(got.jpeg, 0, got.jpeg.size)
        assertEquals(1568, maxOf(b.width, b.height))
        assertTrue(b.width > 1000)
        val out = File(tmp.root, "out.jpg").apply { writeBytes(got.jpeg) }
        val exif = ExifInterface(out.absolutePath)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
    }

    @Test fun thePhonesOwnModelStillGetsTheSmallerSize() {
        val f = photoWithLocation(3200, 2400)
        val got = Pictures.photo(ApplicationProvider.getApplicationContext(), Uri.fromFile(f), "Photo")!!
        val b = BitmapFactory.decodeByteArray(got.jpeg, 0, got.jpeg.size)
        assertEquals(Pictures.MAX_SIDE, maxOf(b.width, b.height))
    }
}
