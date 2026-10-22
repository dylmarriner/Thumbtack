package ai.paperclip.dashboard.ui

import ai.paperclip.dashboard.data.Agent
import ai.paperclip.dashboard.data.ISSUE_PRIORITIES
import ai.paperclip.dashboard.data.ISSUE_STATUSES
import ai.paperclip.dashboard.data.Issue
import ai.paperclip.dashboard.data.PaperclipApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun IssuesScreen(
    api: PaperclipApi,
    companyId: String,
    initialStatus: String?,
    agentsById: Map<String, Agent>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    filter: String? = null,
) {
    var status by rememberSaveable(initialStatus) { mutableStateOf(initialStatus) }
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf(query) }
    androidx.compose.runtime.LaunchedEffect(query) {
        delay(350)
        debounced = query
    }
    val state = rememberLoad(companyId, status, debounced, filter) { api.issues(companyId, status, debounced, filter) }

    Box(Modifier.fillMaxSize()) {
        Column {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search issues") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = status == null, onClick = { status = null }, label = { Text("All") })
                ISSUE_STATUSES.forEach { s ->
                    FilterChip(selected = status == s, onClick = { status = s }, label = { Text(humanize(s)) })
                }
            }
            LoadedContent(state) { issues ->
                if (issues.isEmpty()) {
                    // Keep it scrollable so pull-to-refresh still works on an empty list.
                    LazyColumn(Modifier.fillMaxSize()) { item { EmptyState("No issues") } }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(issues, key = { it.id }) { issue ->
                            IssueRow(issue, agentsById[issue.assigneeAgentId]?.name) { onOpen(issue.id) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, "New issue")
        }
    }
}

@Composable
private fun IssueRow(issue: Issue, assignee: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            issue.identifier?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(issue.status)
            if (issue.priority == "critical" || issue.priority == "high") StatusPill(issue.priority)
        }
        Text(issue.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(assignee, relativeTime(issue.updatedAt).ifBlank { null }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun IssueDetailScreen(api: PaperclipApi, issueId: String, agentsById: Map<String, Agent>) {
    val issueState = rememberLoad(issueId) { api.issue(issueId) }
    val commentsState = rememberLoad(issueId) { api.issueComments(issueId) }
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    fun update(field: String, value: String?) {
        scope.launch {
            actionError = runCatching {
                api.updateIssue(issueId, buildJsonObject { put(field, value) })
            }.exceptionOrNull()?.message
            issueState.reload()
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LoadedContent(issueState, Modifier.weight(1f)) { issue ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        issue.identifier?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(issue.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoiceButton("Status", issue.status, ISSUE_STATUSES) { update("status", it) }
                            ChoiceButton("Priority", issue.priority, ISSUE_PRIORITIES) { update("priority", it) }
                        }
                        AssigneeButton(issue.assigneeAgentId, agentsById) { update("assigneeAgentId", it) }
                        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text(
                            issue.description?.ifBlank { null } ?: "No description",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item { Text("Comments", style = MaterialTheme.typography.titleMedium) }
                val comments = commentsState.data
                when {
                    comments == null && commentsState.error != null -> item { Text(commentsState.error, color = MaterialTheme.colorScheme.error) }
                    comments == null -> item { Text("Loading…") }
                    comments.isEmpty() -> item { Text("No comments yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> items(comments, key = { it.id }) { c ->
                        val author = c.authorAgentId?.let { agentsById[it]?.name ?: "Agent" }
                            ?: if (c.authorUserId != null) "Board" else humanize(c.authorType ?: "system")
                        CommentCard(author, c.createdAt, c.body)
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Add a comment") },
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = draft.isNotBlank() && !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val result = runCatching { api.addIssueComment(issueId, draft.trim()) }
                        actionError = result.exceptionOrNull()?.message
                        if (result.isSuccess) draft = ""
                        sending = false
                        commentsState.reload()
                    }
                },
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

@Composable
fun CommentCard(author: String, createdAt: String?, body: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(author, style = MaterialTheme.typography.labelLarge)
                Text(relativeTime(createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChoiceButton(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${humanize(value)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(humanize(o)) }, onClick = {
                    open = false
                    if (o != value) onPick(o)
                })
            }
        }
    }
}

@Composable
private fun AssigneeButton(agentId: String?, agentsById: Map<String, Agent>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("Assignee: " + (agentId?.let { agentsById[it]?.name ?: "Agent" } ?: "Unassigned"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { open = false; if (agentId != null) onPick(null) })
            agentsById.values.filter { it.status != "terminated" }.sortedBy { it.name }.forEach { a ->
                DropdownMenuItem(text = { Text(a.name) }, onClick = { open = false; if (a.id != agentId) onPick(a.id) })
            }
        }
    }
}

@Composable
fun NewIssueScreen(api: PaperclipApi, companyId: String, agentsById: Map<String, Agent>, onCreated: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf("medium") }
    var assignee by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            description, { description = it },
            label = { Text("Description") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("Priority", priority, ISSUE_PRIORITIES) { priority = it }
        }
        AssigneeButton(assignee, agentsById) { assignee = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = title.isNotBlank() && !busy,
            onClick = {
                busy = true
                scope.launch {
                    runCatching { api.createIssue(companyId, title.trim(), description.trim(), priority, assignee) }
                        .onSuccess { onCreated(it.id) }
                        .onFailure { error = it.message }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Creating…" else "Create issue") }
    }
}
