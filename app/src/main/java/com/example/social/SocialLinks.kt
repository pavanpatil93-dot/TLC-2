package com.example.social

import android.content.Context
import java.net.URI
import java.util.Locale

enum class SocialPlatform(val label: String, val domains: Set<String>) {
    INSTAGRAM("Instagram", setOf("instagram.com")),
    FACEBOOK("Facebook", setOf("facebook.com", "fb.watch")),
    LINKEDIN("LinkedIn", setOf("linkedin.com"))
}

object SocialLinks {
    fun validUrl(platform: SocialPlatform, text: String): Boolean {
        if (text.isBlank()) return true
        return runCatching {
            val uri = URI(text.trim())
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            uri.scheme.equals("https", true) && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                platform.domains.any { host == it || host.endsWith(".$it") } &&
                !uri.path.isNullOrBlank() && uri.path != "/"
        }.getOrDefault(false)
    }
    fun load(context: Context, scope: String): Map<SocialPlatform, String> {
        val prefs = context.getSharedPreferences("social_links_v1", Context.MODE_PRIVATE)
        return SocialPlatform.values().associateWith { prefs.getString("$scope:${it.name}", "").orEmpty() }
    }
    fun save(context: Context, scope: String, links: Map<SocialPlatform, String>): Boolean {
        require(scope.isNotBlank())
        require(links.all { validUrl(it.key, it.value) }) { "Use the correct platform's full HTTPS page or post URL." }
        val editor = context.getSharedPreferences("social_links_v1", Context.MODE_PRIVATE).edit()
        links.forEach { (platform, url) -> editor.putString("$scope:${platform.name}", url.trim()) }
        return editor.commit()
    }
}

