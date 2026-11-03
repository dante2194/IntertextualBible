package com.intertextual.bible

import android.app.Activity
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * Serves the offline pack straight out of its zip file.
 * First launch: copy assets/pack.zip -> private storage (once).
 * After that: every request is answered by reading just that one entry.
 */
class MainActivity : Activity() {

    companion object {
        private const val PACK = "pack.zip"
        private const val HOST = "bible.local"
    }

    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var bar: ProgressBar

    @Volatile private var zip: ZipFile? = null
    @Volatile private var prefix: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#202020"))

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return if (request.url.host == HOST) serve(request.url.path ?: "/") else null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (request.url.host == HOST) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    // no browser available; ignore
                }
                return true
            }
        }
        web.visibility = View.INVISIBLE
        root.addView(web, FrameLayout.LayoutParams(match, match))

        overlay = LinearLayout(this)
        overlay.orientation = LinearLayout.VERTICAL
        overlay.gravity = Gravity.CENTER
        overlay.setPadding(64, 64, 64, 64)
        overlay.setBackgroundColor(Color.parseColor("#202020"))

        val title = TextView(this)
        title.text = "Intertextual Bible"
        title.setTextColor(Color.WHITE)
        title.textSize = 24f
        title.gravity = Gravity.CENTER

        statusText = TextView(this)
        statusText.text = "Preparing…"
        statusText.setTextColor(Color.parseColor("#BBBBBB"))
        statusText.textSize = 14f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, 32, 0, 32)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.isIndeterminate = true

        overlay.addView(title, LinearLayout.LayoutParams(match, wrap))
        overlay.addView(statusText, LinearLayout.LayoutParams(match, wrap))
        overlay.addView(bar, LinearLayout.LayoutParams(match, wrap))
        root.addView(overlay, FrameLayout.LayoutParams(match, match))

        setContentView(root)
        prepare()
    }

    private fun prepare() {
        thread {
            try {
                val dest = File(filesDir, PACK)
                val total: Long = try {
                    assets.openFd(PACK).use { it.length }
                } catch (e: Exception) {
                    -1L
                }

                if (!dest.exists() || (total > 0 && dest.length() != total)) {
                    show("First launch: unpacking Bible data (one time only)…")
                    val tmp = File(filesDir, "$PACK.part")
                    assets.open(PACK, AssetManager.ACCESS_STREAMING).use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            var n: Int
                            var last = -1
                            while (input.read(buf).also { n = it } > 0) {
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = (done * 1000 / total).toInt()
                                    if (p != last) {
                                        last = p
                                        progress(p)
                                    }
                                }
                            }
                        }
                    }
                    if (dest.exists()) dest.delete()
                    if (!tmp.renameTo(dest)) throw IOException("Could not finalize pack file")
                }

                val z = ZipFile(dest)
                val index = findIndex(z)
                if (index == null) {
                    val tops = LinkedHashSet<String>()
                    val en = z.entries()
                    while (en.hasMoreElements() && tops.size < 15) {
                        tops.add(en.nextElement().name.substringBefore('/'))
                    }
                    fail("No index.html found in the pack.\n\nTop-level entries:\n" + tops.joinToString("\n"))
                    return@thread
                }
                prefix = index.substring(0, index.length - "index.html".length)
                zip = z

                runOnUiThread {
                    overlay.visibility = View.GONE
                    web.visibility = View.VISIBLE
                    web.loadUrl("https://$HOST/")
                }
            } catch (e: Exception) {
                fail("Could not prepare the Bible data:\n${e.message}\n\n(Is there enough free storage? The pack is copied once.)")
            }
        }
    }

    private fun show(msg: String) = runOnUiThread { statusText.text = msg }

    private fun progress(p: Int) = runOnUiThread {
        bar.isIndeterminate = false
        bar.progress = p
    }

    private fun fail(msg: String) = runOnUiThread {
        bar.visibility = View.GONE
        statusText.text = msg
    }

    private fun findIndex(z: ZipFile): String? {
        var best: String? = null
        var bestDepth = Int.MAX_VALUE
        val en = z.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (e.isDirectory) continue
            val n = e.name
            if (n.startsWith("__MACOSX")) continue
            if (n.equals("index.html", ignoreCase = true) || n.endsWith("/index.html", ignoreCase = true)) {
                val depth = n.count { it == '/' }
                if (depth < bestDepth) {
                    best = n
                    bestDepth = depth
                }
            }
        }
        return best
    }

    private fun serve(rawPath: String): WebResourceResponse {
        val z = zip ?: return notFound()
        var p = rawPath.trimStart('/')
        if (p.isEmpty() || p.endsWith("/")) p += "index.html"

        val entry = z.getEntry(prefix + p)
            ?: z.getEntry(p)
            ?: z.getEntry("$prefix$p/index.html")
            ?: return notFound()
        if (entry.isDirectory) return notFound()

        return try {
            val mime = mimeOf(entry.name)
            val enc = if (mime.startsWith("text/") || mime.endsWith("javascript") ||
                mime.endsWith("json") || mime.endsWith("xml")
            ) "UTF-8" else null
            WebResourceResponse(
                mime, enc, 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"),
                z.getInputStream(entry)
            )
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "txt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        web.destroy()
        try { zip?.close() } catch (e: Exception) { }
    }
}
