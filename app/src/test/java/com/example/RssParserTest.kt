package com.example

import com.example.data.remote.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RssParserTest {

    @Test
    fun testParseRss2Feed() {
        val sampleRss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
                <channel>
                    <title>RetroArch News</title>
                    <description>Updates on RetroArch and Libretro</description>
                    <link>https://www.libretro.com</link>
                    <item>
                        <title>RetroArch 1.19 Released!</title>
                        <link>https://www.libretro.com/index.php/retroarch-1-19/</link>
                        <guid>https://www.libretro.com/?p=12345</guid>
                        <description><![CDATA[<p>We are proud to announce the release of RetroArch 1.19 with Winlator and PS2 core improvements.</p>]]></description>
                        <pubDate>Mon, 09 Nov 2026 12:00:00 +0000</pubDate>
                    </item>
                </channel>
            </rss>
        """.trimIndent()

        val parsed = RssParser.parse(sampleRss)
        assertEquals("RetroArch News", parsed.title)
        assertEquals(1, parsed.items.size)

        val item = parsed.items[0]
        assertEquals("RetroArch 1.19 Released!", item.title)
        assertEquals("https://www.libretro.com/index.php/retroarch-1-19/", item.link)
        assertTrue(item.description.contains("RetroArch 1.19"))
    }

    @Test
    fun testParseAtomFeed() {
        val sampleAtom = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
                <title>Winlator Releases</title>
                <entry>
                    <id>tag:github.com,2008:Repository/12345/v8.0</id>
                    <title>Winlator 8.0 Release</title>
                    <link rel="alternate" type="text/html" href="https://github.com/brunodev85/winlator/releases/tag/v8.0"/>
                    <summary>Major performance improvements for Windows emulation on Android Snapdragon.</summary>
                    <updated>2026-11-08T10:00:00Z</updated>
                </entry>
            </feed>
        """.trimIndent()

        val parsed = RssParser.parse(sampleAtom)
        assertEquals(1, parsed.items.size)

        val item = parsed.items[0]
        assertEquals("Winlator 8.0 Release", item.title)
        assertEquals("https://github.com/brunodev85/winlator/releases/tag/v8.0", item.link)
        assertTrue(item.description.contains("Windows emulation on Android"))
    }

    @Test
    fun testCleanHtml() {
        val html = "<p>This is <b>bold</b> &amp; <i>italic</i> with <a href='http://x.com'>links</a> &nbsp;!</p>"
        val cleaned = RssParser.cleanHtml(html)
        assertEquals("This is bold & italic with links !", cleaned)
    }
}
