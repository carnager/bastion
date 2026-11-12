package io.github.carnager.bastion

import io.github.carnager.bastion.data.ChainResult
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.Vault
import io.github.carnager.bastion.data.jumpCandidates
import io.github.carnager.bastion.data.resolveChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpChainTest {
    private fun host(id: String, jump: String? = null) =
        Host(id = id, name = id, hostname = "$id.example", username = "u", jumpHostId = jump)

    @Test fun directHostIsItsOwnChain() {
        val a = host("a")
        assertEquals(ChainResult.Ok(listOf(a)), resolveChain(a, Vault(hosts = listOf(a))))
    }

    @Test fun chainIsOrderedFromFirstJumpToTarget() {
        val bastion = host("bastion")
        val inner = host("inner", "bastion")
        val target = host("target", "inner")
        val result = resolveChain(target, Vault(hosts = listOf(target, inner, bastion)))
        assertEquals(ChainResult.Ok(listOf(bastion, inner, target)), result)
    }

    @Test fun loopIsRejected() {
        val a = host("a", "b")
        val b = host("b", "a")
        assertTrue(resolveChain(a, Vault(hosts = listOf(a, b))) is ChainResult.Error)
    }

    @Test fun missingJumpHostIsRejected() {
        val a = host("a", "gone")
        assertTrue(resolveChain(a, Vault(hosts = listOf(a))) is ChainResult.Error)
    }

    @Test fun candidatesExcludeSelfAndHostsThatJumpThroughIt() {
        val a = host("a")
        val b = host("b", "a")
        val c = host("c", "b")
        val d = host("d")
        val vault = Vault(hosts = listOf(a, b, c, d))
        assertEquals(listOf("d"), jumpCandidates(a, vault).map { it.id })
        assertEquals(listOf("a", "d"), jumpCandidates(b, vault).map { it.id })
    }
}
