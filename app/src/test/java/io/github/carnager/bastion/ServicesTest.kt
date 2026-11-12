package io.github.carnager.bastion

import io.github.carnager.bastion.session.Catalog
import io.github.carnager.bastion.session.Services
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesTest {
    @Test fun showIsParsed() {
        val info = Services.parseShow(
            "nginx",
            """
            Id=nginx.service
            Description=A high performance web server
            LoadState=loaded
            ActiveState=active
            SubState=running
            UnitFileState=enabled
            MainPID=412
            ActiveEnterTimestamp=Wed 2026-11-11 09:14:02 CET
            MemoryCurrent=18874368
            CPUUsageNSec=[not set]
            TasksCurrent=18446744073709551615
            FragmentPath=/usr/lib/systemd/system/nginx.service
            """.trimIndent(),
        )
        assertTrue(info.running)
        assertEquals("412", info.mainPid)
        assertEquals(18874368L, info.memory)
        assertNull(info.cpuNanos)
        assertNull(info.tasks)
        assertEquals("enabled", info.enabled)
    }

    @Test fun journalJsonIsParsedWithNotices() {
        val out = """
            {"__REALTIME_TIMESTAMP":"1762850042000000","PRIORITY":"3","MESSAGE":"bind() failed"}
            {"__REALTIME_TIMESTAMP":"1762850043000000","PRIORITY":"6","MESSAGE":[104,105]}
            Hint: You are currently not seeing messages from other users and the system.
        """.trimIndent()
        val (lines, notice) = Services.parseJournal(out)
        assertEquals(listOf(3, 6), lines.map { it.priority })
        assertEquals("hi", lines[1].message)
        assertTrue(notice!!.startsWith("Hint:"))
    }

    @Test fun commandsQuoteTheUnit() {
        assertTrue(Services.showCommand("my unit").endsWith("-- 'my unit'"))
        assertTrue(Services.journalCommand("a;b").contains("-u 'a;b'"))
    }

    @Test fun unitsSortRunningThenFailed() {
        val sorted = Services.sortUnits(
            listOf(
                Catalog.Choice("b", "b", "inactive · dead"),
                Catalog.Choice("z", "z", "active · running"),
                Catalog.Choice("f", "f", "failed · failed"),
                Catalog.Choice("a", "a", "active · running"),
                Catalog.Choice("e", "e", "active · exited"),
            ),
        )
        assertEquals(listOf("a", "z", "f", "e", "b"), sorted.map { it.value })
    }
}
