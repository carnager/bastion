package io.github.carnager.bastion

import io.github.carnager.bastion.session.Fuzzy
import io.github.carnager.bastion.session.Osc7Sniffer
import io.github.carnager.bastion.session.shellQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {
    @Test fun charactersMustAppearInOrder() {
        assertTrue(Fuzzy.match("ngx", "etc/nginx/nginx.conf") != null)
        assertNull(Fuzzy.match("xgn", "etc/nginx"))
    }

    @Test fun smartCase() {
        assertTrue(Fuzzy.match("readme", "README.md") != null)
        assertNull(Fuzzy.match("Readme", "readme.md"))
    }

    @Test fun basenameAndBoundaryMatchesRankFirst() {
        val items = listOf(
            "src/main/res/values/colors.xml",
            "docs/conf.md",
            "etc/nginx/nginx.conf",
            "lib/config/constants.kt",
        )
        val ranked = Fuzzy.filter("nginxconf", items).map { it.text }
        assertEquals("etc/nginx/nginx.conf", ranked.first())
        assertEquals("docs/conf.md", Fuzzy.filter("conf", items).first().text)
    }

    @Test fun positionsPointAtMatchedCharacters() {
        val m = Fuzzy.match("gst", "git status")!!
        assertEquals("gst", m.positions.map { "git status"[it] }.joinToString(""))
    }

    @Test fun osc7IsParsedAcrossChunks() {
        val sniffer = Osc7Sniffer()
        val out = "hello\u001b]7;file://proteus/home/carnager/My%20Dir\u0007more".toByteArray()
        sniffer.feed(out.copyOfRange(0, 12), 12)
        sniffer.feed(out.copyOfRange(12, out.size), out.size - 12)
        assertEquals("/home/carnager/My Dir", sniffer.cwd)
        val st = "\u001b]7;file://h/srv/www\u001b\\".toByteArray()
        sniffer.feed(st, st.size)
        assertEquals("/srv/www", sniffer.cwd)
    }

    @Test fun pathFromPrompt() {
        assertEquals("~/src/bastion", Osc7Sniffer.fromPrompt(listOf("carnager@proteus ~/src/bastion", "❯ ")))
        assertEquals("/etc", Osc7Sniffer.fromPrompt(listOf("root@box:/etc# ")))
        assertEquals("~", Osc7Sniffer.fromPrompt(listOf("carnager@proteus ~", "❯ ")))
        assertNull(Osc7Sniffer.fromPrompt(listOf(">>> ")))
    }

    @Test fun quoting() {
        assertEquals("src/main.kt", shellQuote("src/main.kt"))
        assertEquals("'My Dir/a b'", shellQuote("My Dir/a b"))
        assertEquals("~/'My Dir'", shellQuote("~/My Dir"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }
}
