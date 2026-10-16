package app.urv.manager.domain.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUrlResolverTest {
    private val resolver = DownloadUrlResolver(listOf("arm64-v8a", "armeabi-v7a"))

    @Test
    fun apiSearchUrlIncludesPackageVersionAndAbi() {
        assertEquals(
            "https://api.morphe.software/v2/web-search/com.example.app~1.2.3~arm64-v8a",
            resolver.apiSearchUrl("com.example.app", "1.2.3")
        )
    }

    @Test
    fun apiSearchUrlUsesAnyWhenTargetVersionIsMissing() {
        assertEquals(
            "https://api.morphe.software/v2/web-search/com.example.app~any~arm64-v8a",
            resolver.apiSearchUrl("com.example.app", null)
        )
    }

    @Test
    fun apiSearchUrlEncodesPathSeparatorsInPackageAndVersion() {
        val url = resolver.apiSearchUrl("com.example/app", "1/2 beta")
        assertTrue(url.contains("com.example%2Fapp~1%2F2%20beta~arm64-v8a"))
    }

    @Test
    fun fallbackSearchIncludesRequestedVersionAndSupportedRepositories() {
        val url = resolver.webSearchUrl("com.example.app", "1.2.3")
        assertTrue(url.startsWith("https://www.google.com/search?q="))
        assertTrue(url.contains("site%3Aapkmirror.com"))
        assertTrue(url.contains("site%3Auptodown.com"))
        assertTrue(url.contains("site%3Aapkpure.com"))
        assertTrue(url.contains("site%3Aapkcombo.com"))
        assertTrue(url.contains("1.2.3"))
    }
}
