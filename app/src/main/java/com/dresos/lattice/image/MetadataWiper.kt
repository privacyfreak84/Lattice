package com.dresos.lattice.image

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Ported from DresSecureComms (Apache-2.0 — see NOTICE.md) with the package updated; logic unchanged.
 *
 * Strips location and identifying EXIF metadata from a JPEG before it's sent — a photo's EXIF can carry
 * GPS coordinates precise enough to deanonymize a sender, plus device make/model and edit-history
 * fields nobody intends to hand over just by sharing a picture. [wipeToCache] copies the source into the
 * cache dir (so the original in the user's gallery/media store is never touched) and clears the listed
 * tags in place there.
 */
object MetadataWiper {
    private val TAGS =
        arrayOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
        )

    fun wipeToCache(
        context: Context,
        source: Uri,
    ): File {
        val out = File(context.cacheDir, "clean_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Cannot open image" }
            out.outputStream().use { input.copyTo(it) }
        }
        val exif = ExifInterface(out.absolutePath)
        for (tag in TAGS) exif.setAttribute(tag, null)
        exif.saveAttributes()
        return out
    }
}
