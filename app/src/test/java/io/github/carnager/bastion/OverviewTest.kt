package io.github.carnager.bastion

import io.github.carnager.bastion.session.Overviews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewTest {
    @Test fun parsesSampleOutput() {
        val out = """
            ### os
            Arch Linux
            6.17.8-arch1-1
            ### uptime
            273645.12 1093221.55
            ### load
            0.42 0.38 0.30 1/812 2573311
            8
            ### mem
            MemTotal:       16303912 kB
            MemAvailable:    9876543 kB
            SwapTotal:       8388604 kB
            SwapFree:        8388604 kB
            ### disk
            Filesystem     Type  1024-blocks      Used Available Capacity Mounted on
            /dev/nvme0n1p2 btrfs   487314432 302948224 182766208      63% /
            /dev/nvme0n1p1 vfat      1046512    183456    863056      18% /boot
            /dev/sdb1      ext4   3844640564 3651234567 193406000    95% /srv/media library
            ### running
            42
            ### failed
            backup.service loaded failed failed Nightly backup
            ### containers
            web	running	Up 2 hours
            db	exited	Exited (0) 3 days ago
            ### top
             2575589 caddy 3.2 0.4
                   1 systemd 0.0 0.1
        """.trimIndent()
        val o = Overviews.parse(out)
        assertEquals("Arch Linux", o.os)
        assertEquals(273645L, o.uptimeSeconds)
        assertEquals(listOf(0.42, 0.38, 0.30), o.load)
        assertEquals(8, o.cores)
        assertEquals(3, o.disks.size)
        assertEquals("/srv/media library", o.disks[2].mount)
        assertTrue(o.disks[2].fraction > 0.9f)
        assertEquals(42, o.runningServices)
        assertEquals(listOf("backup"), o.failedServices)
        assertEquals(listOf(true, false), o.containers!!.map { it.running })
        assertEquals("caddy", o.topProcesses.first().name)
        assertNotNull(o.memUsedFraction)
    }

    @Test fun scriptRunsUnderShHere() {
        val p = ProcessBuilder("sh", "-c", Overviews.SCRIPT).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val o = Overviews.parse(out)
        assertTrue("os from this machine: $out", o.os != null)
        assertTrue(o.disks.isNotEmpty())
        assertTrue(o.topProcesses.isNotEmpty())
    }

    @Test fun updatesParse() {
        assertEquals(Overviews.Updates("pacman", 12), Overviews.parseUpdates("pacman\n12\n"))
        assertEquals(null, Overviews.parseUpdates("none\n"))
        assertEquals("sudo pacman -Syu", Overviews.parseUpdates("pacman\n0")!!.upgradeCommand)
        val local = Overviews.parseUpdates("pacman-local\n3")!!
        assertEquals("pacman", local.managerName)
        assertEquals("sudo pacman -Syu", local.upgradeCommand)
        assertTrue(local.staleHint != null)
    }
}
