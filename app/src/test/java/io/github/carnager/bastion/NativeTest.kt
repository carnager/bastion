package io.github.carnager.bastion

import io.github.carnager.bastion.session.Containers
import io.github.carnager.bastion.session.DiskUsage
import io.github.carnager.bastion.session.Processes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeTest {
    @Test fun processesParse() {
        val ps = Processes.parse("    1 root      0.0  0.1 12345 9876543 systemd\n 2575589 caddy 3.2 1.4 70000 600 caddy\n")
        assertEquals(listOf(1, 2575589), ps.map { it.pid })
        assertEquals("caddy", ps[1].user)
        assertEquals(3.2, ps[1].cpu, 0.001)
        assertTrue(Processes.notPermitted("sh: kill: (1) - Operation not permitted"))
    }

    @Test fun diskUsageScriptAndParse() {
        val dir = Files.createTempDirectory("bastion du").toFile()
        File(dir, "big dir").mkdirs()
        File(dir, "big dir/blob").writeBytes(ByteArray(300_000))
        File(dir, ".hidden").mkdirs()
        File(dir, ".hidden/x").writeBytes(ByteArray(50_000))
        File(dir, "small.txt").writeText("hi")
        val p = ProcessBuilder("sh", "-c", DiskUsage.script(dir.path)).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val scan = DiskUsage.parse(dir.path, out)
        assertEquals("big dir", scan.entries.first().name)
        assertTrue(scan.entries.first().isDir)
        assertTrue(scan.entries.first { it.name == ".hidden" }.isDir)
        assertTrue(!scan.entries.first { it.name == "small.txt" }.isDir)
        assertTrue(scan.totalKb >= scan.entries.sumOf { it.kb } - 8)
        assertEquals(dir.path + "/big dir", scan.entries.first().path)
        dir.deleteRecursively()
    }

    @Test fun containersParse() {
        val out = """{"ID":"abc","Names":"web","Image":"nginx:latest","State":"running","Status":"Up 2 hours","Ports":"0.0.0.0:80->80/tcp","CreatedAt":"2026-11-10"}
{"ID":"def","Names":"db","Image":"postgres:16","State":"exited","Status":"Exited (0) 3 days ago","Ports":"","CreatedAt":"2026-11-01"}"""
        val list = Containers.parse(out)
        assertEquals(listOf("web", "db"), list.map { it.name })
        assertEquals(listOf(true, false), list.map { it.running })
        assertTrue(Containers.permissionDenied("permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock"))
    }

    @Test fun containerLogsSplitTimestamps() {
        val logs = Containers.parseLogs("2026-11-12T22:53:20.123456789Z \u001b[32mready\u001b[0m\nplain line\n")
        assertEquals("2026-11-12 22:53:20" to "ready", logs[0])
        assertEquals("" to "plain line", logs[1])
    }
}
