package io.github.carnager.bastion

import io.github.carnager.bastion.session.FinderKind
import io.github.carnager.bastion.session.RemoteLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs the generated listing commands through a real `sh`, the way sshd's exec would. */
class RemoteListsTest {
    private fun run(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    @Test fun listsFilesAndFoldersWithAwkwardNames() {
        val dir = Files.createTempDirectory("bastion it's").toFile()
        File(dir, "src/main").mkdirs()
        File(dir, "src/main/App.kt").writeText("x")
        File(dir, "My Notes.txt").writeText("x")
        File(dir, ".git/objects").mkdirs()
        File(dir, ".git/objects/blob").writeText("x")

        val files = RemoteLists.parse(run(RemoteLists.command(FinderKind.FILES, dir.path)!!), FinderKind.FILES).sorted()
        assertEquals(listOf("My Notes.txt", "src/main/App.kt"), files)

        val folders = RemoteLists.parse(run(RemoteLists.command(FinderKind.FOLDERS, dir.path)!!), FinderKind.FOLDERS).sorted()
        assertEquals(listOf("src/", "src/main/"), folders)
        dir.deleteRecursively()
    }

    @Test fun listsCommands() {
        val commands = RemoteLists.parse(run(RemoteLists.command(FinderKind.COMMANDS, "~")!!), FinderKind.COMMANDS)
        assertTrue("ls" in commands)
    }

    @Test fun missingFolderFailsQuietly() {
        val out = run(RemoteLists.command(FinderKind.FILES, "/does/not/exist")!!)
        assertTrue(RemoteLists.parse(out, FinderKind.FILES).none { it.endsWith(".kt") })
    }
}
