package com.soloit.doubleentry.desktop.platform

import com.soloit.doubleentry.sync.Pkce
import com.soloit.doubleentry.sync.SignInQuestion
import com.soloit.doubleentry.sync.SyncException
import com.soloit.doubleentry.sync.SyncManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import kotlin.time.Duration.Companion.minutes

/**
 * Sign in with Google in the system browser. Supabase runs the Google sign-in and returns to a
 * port this app opens on 127.0.0.1 with a one-time code, which is traded for a session with the
 * PKCE verifier only this process knows.
 */
class BrowserSignIn(private val sync: SyncManager, private val openBrowser: (URI) -> Unit = ::browse) {
    suspend fun signIn(): SignInQuestion? {
        val verifier = Pkce.verifier()
        val result = CompletableDeferred<Result<String>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(CALLBACK_PATH) { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8) else null
            }.toMap()
            val code = query["code"]
            val page = if (code != null) PAGE_DONE else PAGE_FAILED
            val bytes = page.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            result.complete(
                if (code != null) {
                    Result.success(code)
                } else {
                    Result.failure(SyncException(query["error_description"] ?: "We couldn't sign you in with Google. Please try again."))
                },
            )
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}$CALLBACK_PATH"
            openBrowser(URI(sync.googleSignInUrl(redirect, Pkce.challenge(verifier))))
            val code = try {
                withTimeout(10.minutes) { result.await() }.getOrThrow()
            } catch (e: TimeoutCancellationException) {
                throw SyncException("Signing in took too long. Please try again.")
            }
            return sync.signInWithGoogleCode(code, verifier)
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        const val CALLBACK_PATH = "/auth/callback"

        fun browse(uri: URI) {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw SyncException("No web browser could be opened to sign in.")
            }
            Desktop.getDesktop().browse(uri)
        }

        fun page(title: String, text: String) = """
            <!doctype html><html><head><meta charset="utf-8"><title>DoubleEntry</title>
            <style>body{font-family:"Segoe UI",system-ui,sans-serif;display:grid;place-items:center;height:90vh;margin:0;color:#1b1b1b}
            @media(prefers-color-scheme:dark){body{background:#202020;color:#fff}}h1{font-weight:600;font-size:24px}</style></head>
            <body><div><h1>$title</h1><p>$text</p></div></body></html>
        """.trimIndent()

        val PAGE_DONE = page("You're signed in", "You can close this tab and return to DoubleEntry.")
        val PAGE_FAILED = page("Sign-in didn't finish", "Return to DoubleEntry and try again.")
    }
}
