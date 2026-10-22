package ai.paperclip.dashboard.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

data class SignedInUser(val name: String?, val email: String?)

/** Thin client for the Paperclip REST API (`/api`), matching the clients in ui/src/api. */
class PaperclipApi(private val store: SessionStore) {
    companion object {
        /** Issues per page; a full page means "Load more" may find more. */
        const val ISSUE_PAGE = 100
    }

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Called on any 401 so the app can drop back to the sign-in screen. */
    var onUnauthorized: (() -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .cookieJar(store.cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    // board-mutation-guard.ts rejects cookie-session mutations without a same-origin Origin header.
    private fun origin(): String {
        val u = store.baseUrl.toHttpUrl()
        val defaultPort = if (u.scheme == "https") 443 else 80
        return "${u.scheme}://${u.host}" + if (u.port != defaultPort) ":${u.port}" else ""
    }

    suspend fun raw(method: String, path: String, body: JsonElement? = null): String = withContext(Dispatchers.IO) {
        val url = store.baseUrl + "/api" + path
        val requestBody = when {
            body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)
            method == "GET" -> null
            else -> "{}".toRequestBody(jsonType)
        }
        val req = Request.Builder().url(url).method(method, requestBody).header("Accept", "application/json")
        if (method != "GET") req.header("Origin", origin())
        store.boardKey.takeIf { it.isNotBlank() }?.let { req.header("Authorization", "Bearer $it") }
        client.newCall(req.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                if (res.code == 401) onUnauthorized?.invoke()
                throw ApiException(res.code, errorMessage(text, res.code))
            }
            text
        }
    }

    private fun errorMessage(text: String, code: Int): String {
        val parsed = runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            (obj["error"] ?: obj["message"])?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return parsed ?: "HTTP $code"
    }

    suspend inline fun <reified T> get(path: String): T = json.decodeFromString(raw("GET", path))
    suspend inline fun <reified T> post(path: String, body: JsonObject = JsonObject(emptyMap())): T =
        json.decodeFromString(raw("POST", path, body))
    suspend inline fun <reified T> patch(path: String, body: JsonObject): T =
        json.decodeFromString(raw("PATCH", path, body))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // --- Auth (better-auth) ---

    suspend fun signIn(email: String, password: String) {
        raw("POST", "/auth/sign-in/email", buildJsonObject {
            put("email", email)
            put("password", password)
        })
    }

    suspend fun signUp(name: String, email: String, password: String) {
        raw("POST", "/auth/sign-up/email", buildJsonObject {
            put("name", name)
            put("email", email)
            put("password", password)
        })
    }

    /** Claims instance ownership with the one-time URL the server prints on first authenticated startup. */
    suspend fun claimBoard(token: String, code: String) {
        raw("POST", "/board-claim/$token/claim", buildJsonObject { put("code", code) })
    }

    /** Returns null when no browser-style session exists (e.g. board key or local_trusted mode). */
    suspend fun currentUser(): SignedInUser? {
        val text = try {
            raw("GET", "/auth/get-session")
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 404) return null else throw e
        }
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val session = (root["data"] as? JsonObject) ?: root
        val user = session["user"] as? JsonObject ?: return null
        return SignedInUser(
            name = user["name"]?.jsonPrimitive?.contentOrNull,
            email = user["email"]?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun signOut() {
        runCatching { raw("POST", "/auth/sign-out") }
        store.clearAuth()
    }

    // --- Companies & dashboard ---

    suspend fun companies(): List<Company> = get("/companies?scope=accessible")
    suspend fun dashboard(companyId: String): DashboardSummary = get("/companies/$companyId/dashboard")

    // --- Issues ---

    /** [filter] is extra query string, e.g. `projectId=…` or `touchedByUserId=me` for the inbox. */
    suspend fun issues(companyId: String, status: String?, query: String?, filter: String? = null, offset: Int = 0): List<Issue> {
        val qs = buildList {
            add("limit=$ISSUE_PAGE")
            if (offset > 0) add("offset=$offset")
            status?.let { add("status=${enc(it)}") }
            query?.takeIf { it.isNotBlank() }?.let { add("q=${enc(it)}") }
            filter?.let { add(it) }
        }.joinToString("&")
        return get("/companies/$companyId/issues?$qs")
    }

    suspend fun issue(id: String): Issue = get("/issues/$id")

    /** Newest 200 comments, oldest first. Fetching desc keeps a long thread from hiding its latest replies. */
    suspend fun issueComments(id: String): List<IssueComment> =
        get<List<IssueComment>>("/issues/$id/comments?order=desc&limit=200").asReversed()

    suspend fun addIssueComment(id: String, body: String): IssueComment =
        post("/issues/$id/comments", buildJsonObject {
            put("body", body)
            put("clientRequestId", java.util.UUID.randomUUID().toString())
        })

    suspend fun updateIssue(id: String, fields: JsonObject) {
        raw("PATCH", "/issues/$id", fields)
    }

    suspend fun createIssue(companyId: String, title: String, description: String, priority: String, assigneeAgentId: String?): Issue =
        post("/companies/$companyId/issues", buildJsonObject {
            put("title", title)
            if (description.isNotBlank()) put("description", description)
            put("priority", priority)
            put("status", "todo")
            assigneeAgentId?.let { put("assigneeAgentId", it) }
        })

    // --- Agents ---

    suspend fun agents(companyId: String): List<Agent> = get("/companies/$companyId/agents")
    suspend fun agent(id: String, companyId: String): Agent = get("/agents/$id?companyId=$companyId")
    suspend fun pauseAgent(id: String, companyId: String) { raw("POST", "/agents/$id/pause?companyId=$companyId") }
    suspend fun resumeAgent(id: String, companyId: String) { raw("POST", "/agents/$id/resume?companyId=$companyId") }
    suspend fun invokeAgent(id: String, companyId: String) { raw("POST", "/agents/$id/heartbeat/invoke?companyId=$companyId") }

    // --- Approvals ---

    suspend fun approvals(companyId: String, status: String?): List<Approval> =
        get("/companies/$companyId/approvals" + (status?.let { "?status=${enc(it)}" } ?: ""))

    suspend fun approval(id: String): Approval = get("/approvals/$id")
    suspend fun approvalComments(id: String): List<ApprovalComment> = get("/approvals/$id/comments")

    suspend fun addApprovalComment(id: String, body: String) {
        raw("POST", "/approvals/$id/comments", buildJsonObject { put("body", body) })
    }

    /** decision is one of approve | reject | request-revision. */
    suspend fun decideApproval(id: String, decision: String, note: String) {
        raw("POST", "/approvals/$id/$decision", buildJsonObject {
            if (note.isNotBlank()) put("decisionNote", note)
        })
    }

    // --- Activity ---

    suspend fun activity(companyId: String): List<ActivityEvent> = get("/companies/$companyId/activity?limit=100")

    // --- Side-menu sections ---

    suspend fun sidebarBadges(companyId: String): SidebarBadges = get("/companies/$companyId/sidebar-badges")

    suspend fun search(companyId: String, q: String): SearchResponse =
        get("/companies/$companyId/search?q=${enc(q)}&limit=50")

    suspend fun projects(companyId: String): List<Project> = get("/companies/$companyId/projects")
    suspend fun project(id: String, companyId: String): Project = get("/projects/$id?companyId=$companyId")

    suspend fun goals(companyId: String): List<Goal> = get("/companies/$companyId/goals")
    suspend fun goal(id: String): Goal = get("/goals/$id")

    suspend fun updateGoal(id: String, fields: JsonObject) {
        raw("PATCH", "/goals/$id", fields)
    }

    suspend fun routines(companyId: String): List<Routine> = get("/companies/$companyId/routines")
    suspend fun routine(id: String): Routine = get("/routines/$id")
    suspend fun routineRuns(id: String): List<RoutineRunSummary> = get("/routines/$id/runs?limit=50")

    suspend fun runRoutine(id: String) {
        raw("POST", "/routines/$id/run")
    }

    suspend fun setRoutineStatus(id: String, status: String) {
        raw("PATCH", "/routines/$id", buildJsonObject { put("status", status) })
    }

    suspend fun decisions(companyId: String, status: String?): List<Decision> =
        get("/companies/$companyId/decisions" + (status?.let { "?status=${enc(it)}" } ?: ""))

    suspend fun decide(id: String, optionId: String) {
        raw("POST", "/decisions/$id/decide", buildJsonObject {
            put("optionId", optionId)
            put("idempotencyKey", java.util.UUID.randomUUID().toString())
        })
    }

    suspend fun dismissDecision(id: String) {
        raw("POST", "/decisions/$id/dismiss")
    }

    suspend fun costSummary(companyId: String): CostSummary = get("/companies/$companyId/costs/summary")
    suspend fun costsByAgent(companyId: String): List<CostByAgent> = get("/companies/$companyId/costs/by-agent")
    suspend fun costsByProject(companyId: String): List<CostByProject> = get("/companies/$companyId/costs/by-project")

    suspend fun org(companyId: String): List<OrgNode> = get("/companies/$companyId/org")

    suspend fun company(companyId: String): Company = get("/companies/$companyId")

    suspend fun updateCompany(companyId: String, fields: JsonObject) {
        raw("PATCH", "/companies/$companyId", fields)
    }

    /** Untyped GET for sections whose shapes vary across server versions (artifacts, skills, connectors, ...). */
    suspend fun getJson(path: String): JsonElement = json.parseToJsonElement(raw("GET", path))
}
