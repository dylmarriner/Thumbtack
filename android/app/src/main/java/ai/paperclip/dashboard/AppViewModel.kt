package ai.paperclip.dashboard

import ai.paperclip.dashboard.data.Agent
import ai.paperclip.dashboard.data.Company
import ai.paperclip.dashboard.data.PaperclipApi
import ai.paperclip.dashboard.data.Project
import ai.paperclip.dashboard.data.SidebarBadges
import kotlinx.serialization.json.JsonObject
import ai.paperclip.dashboard.data.SessionStore
import ai.paperclip.dashboard.data.SignedInUser
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

enum class Phase { Checking, SignedOut, Ready }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = SessionStore(app)
    val api = PaperclipApi(store)

    var phase by mutableStateOf(Phase.Checking)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var user by mutableStateOf<SignedInUser?>(null)
        private set
    var companies by mutableStateOf<List<Company>>(emptyList())
        private set
    var companyId by mutableStateOf(store.companyId)
        private set
    /** Agent lookup for the selected company, used to label assignees and actors. */
    var agentsById by mutableStateOf<Map<String, Agent>>(emptyMap())
        private set
    /** Badge counts and project list shown in the side menu. */
    var badges by mutableStateOf(SidebarBadges())
        private set
    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    /** Item handed from a generic list screen to its detail screen. */
    var inspected by mutableStateOf<JsonObject?>(null)

    val company: Company? get() = companies.firstOrNull { it.id == companyId }

    init {
        api.onUnauthorized = {
            viewModelScope.launch {
                if (phase == Phase.Ready) {
                    error = "Session expired. Sign in again."
                    phase = Phase.SignedOut
                }
            }
        }
        if (store.baseUrl.isBlank()) phase = Phase.SignedOut else connect()
    }

    fun connect() {
        viewModelScope.launch {
            phase = Phase.Checking
            error = null
            try {
                val list = api.companies()
                user = api.currentUser()
                companies = list
                val selected = list.firstOrNull { it.id == companyId } ?: list.firstOrNull()
                selectCompany(selected?.id)
                phase = Phase.Ready
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                phase = Phase.SignedOut
            }
        }
    }

    fun signIn(
        baseUrl: String,
        email: String,
        password: String,
        boardKey: String,
        signUpName: String? = null,
        claimUrl: String = "",
    ) {
        val url = baseUrl.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
        store.baseUrl = url
        store.boardKey = boardKey
        viewModelScope.launch {
            phase = Phase.Checking
            error = null
            try {
                if (email.isNotBlank()) {
                    if (signUpName != null) api.signUp(signUpName.trim(), email.trim(), password)
                    else api.signIn(email.trim(), password)
                }
                if (claimUrl.isNotBlank()) {
                    val (token, code) = parseBoardClaimUrl(claimUrl)
                        ?: throw IllegalArgumentException("Board claim URL should look like …/board-claim/<token>?code=<code>")
                    api.claimBoard(token, code)
                }
                connect()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                phase = Phase.SignedOut
            }
        }
    }

    /** Connects with the server URL and board key from a pairing QR code. Returns false for a non-pairing link. */
    fun pair(link: String): Boolean {
        val (url, key) = parsePairingLink(link) ?: run {
            error = "That QR code is not a Paperclip pairing code."
            return false
        }
        signIn(url, "", "", key)
        return true
    }

    fun signOut() {
        viewModelScope.launch {
            api.signOut()
            user = null
            companies = emptyList()
            agentsById = emptyMap()
            phase = Phase.SignedOut
        }
    }

    fun selectCompany(id: String?) {
        companyId = id
        store.companyId = id
        agentsById = emptyMap()
        badges = SidebarBadges()
        projects = emptyList()
        refreshSidebar()
    }

    fun refreshAgents() {
        val id = companyId ?: return
        viewModelScope.launch {
            runCatching { api.agents(id) }.onSuccess { list -> agentsById = list.associateBy { it.id } }
        }
    }

    fun refreshSidebar() {
        val id = companyId ?: return
        refreshAgents()
        viewModelScope.launch { runCatching { api.sidebarBadges(id) }.onSuccess { badges = it } }
        viewModelScope.launch {
            runCatching { api.projects(id) }.onSuccess { list -> projects = list.filter { it.archivedAt == null } }
        }
    }
}

/** Extracts (token, code) from a board-claim URL such as `http://host:3100/board-claim/<token>?code=<code>`. */
/** Parses `paperclip://connect?url=<server>&key=<board key>` from the web "Pair a phone" QR code. */
fun parsePairingLink(link: String): Pair<String, String>? {
    val uri = runCatching { java.net.URI(link.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "paperclip" || uri.host != "connect") return null
    val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
        val i = part.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
        java.net.URLDecoder.decode(part.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
    }.toMap()
    val url = params["url"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    val key = params["key"]?.takeIf { it.isNotBlank() } ?: return null
    return url to key
}

fun parseBoardClaimUrl(url: String): Pair<String, String>? {
    val m = Regex("""board-claim/([A-Za-z0-9]+)\?(?:.*&)?code=([A-Za-z0-9]+)""").find(url.trim()) ?: return null
    return m.groupValues[1] to m.groupValues[2]
}
