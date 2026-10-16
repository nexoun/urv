/*
 * Adapted for URV from the version-aware download URL resolution flow in Morphe Manager.
 * Original project: https://github.com/MorpheApp/morphe-manager
 * Copyright 2026 Morphe.
 * Distributed under the GNU General Public License, version 3, consistent with this project.
 */

package app.urv.manager.domain.manager

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Result of looking up a version-aware application download page. */
data class DownloadUrlResolution(
    val url: String,
    /** True when the resolver had to provide a general search rather than a resolved destination. */
    val isFallbackSearch: Boolean
)

/**
 * Resolves a download page for a package and patch-target version.
 *
 * The lookup uses the same public web-search redirect service used by Morphe Manager. If that
 * service is unavailable or does not return a redirect, a restricted search across common APK
 * repositories is returned instead. This class only resolves a URL; it never downloads or installs
 * an APK.
 */
class DownloadUrlResolver(
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
) {
    suspend fun resolve(packageName: String, version: String?): DownloadUrlResolution {
        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) {
            followRedirects(packageName, version)
        }
        return resolved ?: fallback(packageName, version).also {
            Log.w(TAG, "Timed out resolving a download page; using web search")
        }
    }

    /** Creates the version- and ABI-specific lookup URL. Exposed for deterministic testing. */
    fun apiSearchUrl(packageName: String, version: String?): String {
        val abi = supportedAbis.firstOrNull().orEmpty().ifBlank { "unknown" }
        val path = listOf(packageName, version?.takeIf(String::isNotBlank) ?: "any", abi)
            .joinToString("~", transform = ::encodePathSegment)
        return "$API_BASE_URL/v2/web-search/$path"
    }

    /** Returns a useful restricted search when a direct destination cannot be resolved. */
    fun webSearchUrl(packageName: String, version: String?): String {
        val abi = supportedAbis.firstOrNull().orEmpty().ifBlank { "unknown" }
        val architecture = if (packageName == YOUTUBE_MUSIC_PACKAGE) " ($abi)" else "nodpi"
        val versionPart = version?.takeIf(String::isNotBlank)?.let { "\"$it\"" }.orEmpty()
        val query = "\"$packageName\" $versionPart $architecture $SEARCH_SITES"
        return "https://www.google.com/search?q=${urlEncode(query)}&udm=14"
    }

    private suspend fun followRedirects(
        packageName: String,
        version: String?
    ): DownloadUrlResolution {
        val searchUrl = apiSearchUrl(packageName, version)
        var resolved = requestRedirect(searchUrl) ?: run {
            Log.w(TAG, "The download lookup API did not return a redirect")
            return fallback(packageName, version)
        }

        repeat(MAX_REDIRECTS) {
            val next = requestRedirect(resolved)
            if (next == null) {
                val finalUrl = validateFinalUrl(resolved, packageName, version)
                return DownloadUrlResolution(finalUrl, isFallbackSearchUrl(finalUrl))
            }
            resolved = next
        }

        Log.w(TAG, "Download lookup exceeded the redirect limit")
        return fallback(packageName, version)
    }

    /** Performs a single HEAD request without following redirects automatically. */
    private suspend fun requestRedirect(url: String): String? = withContext(Dispatchers.IO) {
        requestRedirectBlocking(url)
    }

    private fun requestRedirectBlocking(url: String): String? {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isNullOrBlank()) {
            return null
        }

        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = REQUEST_TIMEOUT_MILLIS
                readTimeout = REQUEST_TIMEOUT_MILLIS
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
        }.getOrNull() ?: return null

        return try {
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (status !in 300..399 || location.isNullOrBlank()) return null

            val next = runCatching { URL(URL(url), location).toURI() }.getOrNull() ?: return null
            if (!next.scheme.equals("https", ignoreCase = true) || next.host.isNullOrBlank()) {
                Log.w(TAG, "Rejected a non-HTTPS download redirect")
                null
            } else {
                next.toASCIIString()
            }
        } catch (error: Exception) {
            Log.d(TAG, "Could not resolve redirect for $url", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun validateFinalUrl(url: String, packageName: String, version: String?): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return webSearchUrl(packageName, version)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return webSearchUrl(packageName, version)
        }

        // Uptodown can redirect retired build URLs to its latest-version page. Do not present that
        // page as the requested version; return a version-aware search instead.
        val isUptodown = uri.host.lowercase().removePrefix("www.").endsWith("uptodown.com")
        if (isUptodown && !uri.path.orEmpty().trimEnd('/').endsWith("-x")) {
            return webSearchUrl(packageName, version)
        }
        return uri.toASCIIString()
    }

    private fun fallback(packageName: String, version: String?) =
        DownloadUrlResolution(webSearchUrl(packageName, version), isFallbackSearch = true)

    private fun isFallbackSearchUrl(url: String): Boolean = runCatching {
        URI(url).host?.lowercase()?.removePrefix("www.") == "google.com"
    }.getOrDefault(true)

    private fun encodePathSegment(value: String): String =
        urlEncode(value).replace("%7E", "~", ignoreCase = true)

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val TAG = "DownloadUrlResolver"
        const val API_BASE_URL = "https://api.morphe.software"
        const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        const val SEARCH_SITES =
            "(site:apkmirror.com OR site:uptodown.com OR site:apkpure.com OR site:apkcombo.com)"
        const val USER_AGENT = "Universal-ReVanced-Manager"
        const val MAX_REDIRECTS = 5
        const val REQUEST_TIMEOUT_MILLIS = 2_500
        const val RESOLVE_TIMEOUT_MILLIS = 8_000L
    }
}
