package com.example.social

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SocialLinksTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("social_links_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun onlyCorrectHttpsPlatformLinksAccepted() {
        val p = SocialPlatform.INSTAGRAM
        assertTrue(SocialLinks.validUrl(p, "https://www.instagram.com/reel/test-post/"))
        listOf("http://instagram.com/reel/a", "https://instagram.com.evil.test/reel/a",
            "https://instagram.com@evil.test/reel/a", "javascript:alert(1)",
            "https://facebook.com/post/a", "https://instagram.com/").forEach {
            assertFalse(it, SocialLinks.validUrl(p, it))
        }
        assertTrue(SocialLinks.validUrl(SocialPlatform.FACEBOOK, "https://fb.watch/test/"))
        assertTrue(SocialLinks.validUrl(SocialPlatform.LINKEDIN, "https://www.linkedin.com/company/test/"))
    }
    @Test fun brandAndPropertyLinksRemainSeparateAndReload() {
        val a = mapOf(SocialPlatform.INSTAGRAM to "https://www.instagram.com/ascend-example/")
        val b = mapOf(SocialPlatform.INSTAGRAM to "https://www.instagram.com/aurea-example/")
        val c = mapOf(SocialPlatform.INSTAGRAM to "https://www.instagram.com/reel/test/")
        assertTrue(SocialLinks.save(context, "brand:ascend", a))
        assertTrue(SocialLinks.save(context, "brand:aurea", b))
        assertTrue(SocialLinks.save(context, "property:one", c))
        assertEquals(a[SocialPlatform.INSTAGRAM], SocialLinks.load(context, "brand:ascend")[SocialPlatform.INSTAGRAM])
        assertEquals(b[SocialPlatform.INSTAGRAM], SocialLinks.load(context, "brand:aurea")[SocialPlatform.INSTAGRAM])
        assertEquals(c[SocialPlatform.INSTAGRAM], SocialLinks.load(context, "property:one")[SocialPlatform.INSTAGRAM])
        assertTrue(SocialLinks.load(context, "property:two").values.all { it.isBlank() })
    }
    @Test fun invalidEditPreservesExistingLinksAndRemoveIsScoped() {
        val p = SocialPlatform.INSTAGRAM
        SocialLinks.save(context, "property:one", mapOf(p to "https://instagram.com/reel/one/"))
        SocialLinks.save(context, "property:two", mapOf(p to "https://instagram.com/reel/two/"))
        assertTrue(runCatching { SocialLinks.save(context, "property:one", mapOf(p to "https://evil.test/one")) }.isFailure)
        assertEquals("https://instagram.com/reel/one/", SocialLinks.load(context, "property:one")[p])
        SocialLinks.save(context, "property:one", mapOf(p to ""))
        assertEquals("", SocialLinks.load(context, "property:one")[p])
        assertEquals("https://instagram.com/reel/two/", SocialLinks.load(context, "property:two")[p])
    }
}

