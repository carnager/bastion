package io.github.carnager.bastion.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Prompt {
    /** A host key we have not seen ([previous] == null) or one that changed since last time. */
    data class HostKey(
        val endpoint: String,
        val keyType: String,
        val fingerprint: String,
        val previous: String?,
    ) : Prompt

    data class Password(val hostLabel: String, val address: String) : Prompt
}

sealed interface PromptAnswer {
    data object Rejected : PromptAnswer
    data object Accepted : PromptAnswer
    data class Text(val value: String, val remember: Boolean) : PromptAnswer
}

/** Asks the UI one question at a time. Connections block on the answer. */
class Prompter {
    private class Pending(val prompt: Prompt, val answer: CompletableDeferred<PromptAnswer>)

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<Pending?>(null)
    val current: StateFlow<Prompt?> get() = _current
    private val _current = MutableStateFlow<Prompt?>(null)

    suspend fun ask(prompt: Prompt): PromptAnswer = mutex.withLock {
        val pending = Pending(prompt, CompletableDeferred())
        _pending.value = pending
        _current.value = prompt
        try {
            pending.answer.await()
        } finally {
            _pending.value = null
            _current.value = null
        }
    }

    /** For MINA callbacks, which run on its own I/O threads and expect a synchronous answer. */
    fun askBlocking(prompt: Prompt): PromptAnswer = runBlocking { ask(prompt) }

    fun answer(answer: PromptAnswer) {
        _pending.value?.answer?.complete(answer)
    }
}
