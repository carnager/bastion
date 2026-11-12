package io.github.carnager.bastion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.carnager.bastion.session.TerminalSession

/** The session's files over SFTP, starting in the shell's folder. */
@Composable
fun FilesScreen(
    session: TerminalSession,
    onInsert: (String) -> Unit,
    onRun: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Files", onNav = onBack)
        SearchField(query, { query = it }, "Filter, or type a /path", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        BrowsePane(
            session = session,
            query = query,
            onQueryChange = { query = it },
            onInsert = onInsert,
            onRun = onRun,
            onOpenFile = onOpenFile,
        )
    }
}
