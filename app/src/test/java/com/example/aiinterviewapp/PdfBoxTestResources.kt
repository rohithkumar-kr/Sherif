package com.example.aiinterviewapp

import android.content.Context
import android.content.res.AssetManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.InputStream

/**
 * PDFBox-Android reads its font metrics through `PDFBoxResourceLoader`, which
 * insists on a real Android `AssetManager`. The AAR's `assets/` tree is unpacked
 * onto the unit-test classpath by the `extractPdfboxTestAssets` Gradle task, so
 * a stub `AssetManager` reading through the class loader is enough to exercise
 * the genuine extraction code path on the JVM.
 */
object PdfBoxTestResources {

    private val root: String = "com/tom_roush/"

    @Volatile
    private var installed = false

    /**
     * Absolute path of the tree unpacked by the `extractPdfboxTestAssets`
     * Gradle task, passed in as a system property.
     */
    private val unpackedRoot: String? = System.getProperty("pdfbox.testAssets")

    /** Must run before any `PDType1Font` / `PDDocument` usage. */
    @Synchronized
    fun install() {
        if (installed) return

        val unpacked = unpackedRoot?.let { java.io.File(it) }
        val assets = mock<AssetManager>()
        whenever(assets.open(org.mockito.kotlin.any<String>())).thenAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            val stream: InputStream? = when {
                unpacked != null -> java.io.File(unpacked, path).takeIf { it.isFile }?.inputStream()
                else -> null
            } ?: PdfBoxTestResources::class.java.classLoader.getResourceAsStream(path)
            stream ?: throw java.io.IOException("asset not found: $path")
        }

        val context = mock<Context>()
        whenever(context.getApplicationContext()).doReturn(context)
        whenever(context.assets).doReturn(assets)

        PDFBoxResourceLoader.init(context)
        installed = true
    }

    /** Visible for assertions about what was actually unpacked. */
    fun resourceExists(path: String): Boolean =
        path.startsWith(root) &&
            PdfBoxTestResources::class.java.classLoader.getResource(path) != null
}
