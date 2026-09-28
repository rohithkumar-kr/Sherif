package com.example.aiinterviewapp.data.ocr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.aiinterviewapp.domain.model.ResumeDocumentType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a picked [Uri] to a supported [ResumeDocumentType].
 *
 * MIME type is preferred, but the file extension is used as a fallback because
 * some document providers return `application/octet-stream` or an empty type
 * for files that are in fact a supported resume.
 */
@Singleton
class ResumeDocumentResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun resolve(uri: Uri): ResumeDocumentType =
        ResumeDocumentType.resolve(mimeTypeOf(uri), displayNameOf(uri))

    private fun mimeTypeOf(uri: Uri): String? = runCatching {
        context.contentResolver.getType(uri)
    }.getOrNull()

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
