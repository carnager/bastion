package io.github.carnager.bastion

import io.github.carnager.bastion.session.Catalog
import io.github.carnager.bastion.session.FieldType
import io.github.carnager.bastion.session.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    @Test fun fieldsAreFoundTypedAndLabelled() {
        val f = Templates.fields("docker exec -it {container} {shell:Shell, e.g. bash}")
        assertEquals(listOf(FieldType.CONTAINER, FieldType.TEXT), f.map { it.type })
        assertEquals("Shell, e.g. bash", f[1].label)
        assertEquals("Container", f[0].label)
    }

    @Test fun shellBracesAreNotFields() {
        assertTrue(Templates.fields("echo \${HOME} ; find . -exec rm {} \\; ; awk '{print \$1}'").isEmpty())
        assertEquals(1, Templates.fields("\${EDITOR:-vi} {file}").size)
    }

    @Test fun fillQuotesWhereNeeded() {
        val t = "find {folder} -iname {pattern:Name}"
        val values = mapOf("{folder}" to "My Dir", "{pattern:Name}" to "*.conf")
        assertEquals("find 'My Dir' -iname '*.conf'", Templates.fill(t, values))
        assertEquals("journalctl -u nginx -f", Templates.fill("journalctl -u {unit} -f", mapOf("{unit}" to "nginx")))
        assertEquals("kill 1234", Templates.fill("kill {process}", mapOf("{process}" to "1234")))
        assertEquals("grep -rn \"a b\" .", Templates.fill("grep -rn {text} .", mapOf("{text}" to "\"a b\"")))
    }

    @Test fun repeatedFieldIsFilledEverywhere() {
        assertEquals("cp 'a b' 'a b'.bak", Templates.fill("cp {file} {file}.bak", mapOf("{file}" to "a b")))
    }

    @Test fun catalogFollowsTools() {
        val arch = Catalog.available(setOf("systemctl", "journalctl", "pacman", "ss"))
        assertTrue(arch.any { it.id == "pac-upgrade" })
        assertTrue(arch.none { it.id.startsWith("apt-") || it.id.startsWith("ctr-") })
        val podman = Catalog.available(setOf("podman"))
        assertEquals("podman logs -f --tail 200 {container}", podman.first { it.id == "ctr-logs" }.template)
        assertEquals(Catalog.all, Catalog.available(null))
    }

    @Test fun everyBuiltInTemplateParses() {
        Catalog.all.forEach { e -> Templates.fields(e.template).forEach { assertTrue(e.id, it.label.isNotBlank()) } }
        assertEquals(Catalog.all.size, Catalog.all.map { it.id }.toSet().size)
    }

    @Test fun choicesParse() {
        val units = Catalog.parseChoices(FieldType.UNIT, "nginx.service loaded active running A high performance web server\nsshd.service loaded inactive dead OpenSSH Daemon\n")
        assertEquals(listOf("nginx", "sshd"), units.map { it.value })
        assertEquals("active · running — A high performance web server", units[0].detail)
        val procs = Catalog.parseChoices(FieldType.PROCESS, "  412 nginx 3.2 http\n    1 systemd  0.0 root\n")
        assertEquals(listOf("412", "1"), procs.map { it.value })
        assertEquals("nginx", procs[0].label)
        val ctr = Catalog.parseChoices(FieldType.CONTAINER, "web\tUp 2 hours\tnginx:latest\n")
        assertEquals("Up 2 hours · nginx:latest", ctr.single().detail)
    }

    @Test fun probeAndSourcesRunUnderSh() {
        val p = ProcessBuilder("sh", "-c", Catalog.probeCommand()).start()
        val found = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        assertTrue("probe should at least find ip or ss here: $found", found.all { it in Catalog.PROBED })
    }
}
