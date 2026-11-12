package io.github.carnager.bastion.data

const val MAX_JUMPS = 8

sealed interface ChainResult {
    /** Hops in connection order: first jump host first, target last. */
    data class Ok(val hops: List<Host>) : ChainResult
    data class Error(val message: String) : ChainResult
}

/** Resolves the jump hosts in front of [target], rejecting loops, missing hosts and overlong chains. */
fun resolveChain(target: Host, vault: Vault): ChainResult {
    val hops = ArrayDeque<Host>()
    val seen = mutableSetOf<String>()
    var current: Host? = target
    while (current != null) {
        if (!seen.add(current.id)) return ChainResult.Error("Jump hosts form a loop at \"${current.label}\".")
        if (hops.size > MAX_JUMPS) return ChainResult.Error("More than $MAX_JUMPS jump hosts.")
        hops.addFirst(current)
        val next = current.jumpHostId ?: break
        current = vault.host(next)
            ?: return ChainResult.Error("Jump host of \"${hops.first().label}\" no longer exists.")
    }
    return ChainResult.Ok(hops.toList())
}

/** Hosts that can serve as jump host for [host] without creating a loop. */
fun jumpCandidates(host: Host, vault: Vault): List<Host> = vault.hosts.filter { candidate ->
    candidate.id != host.id && generateSequence(candidate) { vault.host(it.jumpHostId) }
        .take(MAX_JUMPS + 2)
        .none { it.id == host.id }
}
