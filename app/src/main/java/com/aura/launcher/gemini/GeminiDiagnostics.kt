package com.aura.launcher.gemini

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.aura.launcher.data.AuraPrefs
import com.aura.launcher.data.GeminiAuthMode
import com.aura.launcher.system.SystemPanels
import com.google.android.gms.common.GoogleApiAvailability
import org.json.JSONArray
import org.json.JSONObject

/** One line of the Gemini check. [ok] = null means informational. */
data class CheckResult(val name: String, val ok: Boolean?, val detail: String)

/**
 * End-to-end self-test of AURA's Gemini path: sign-in → token → model list → a real reply →
 * a real function call → permissions. Produces a shareable report with no secrets in it.
 */
object GeminiDiagnostics {

    suspend fun run(
        context: Context,
        prefs: AuraPrefs,
        credential: GeminiCredential?,
        tools: PhoneTools,
        emit: (CheckResult) -> Unit,
    ) {
        emit(CheckResult("Device", null, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"))
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        emit(CheckResult("AURA", null, "v$version · Play services ${if (gms == 0) "OK" else "status $gms"} · model ${prefs.model}"))

        // 1. Sign-in
        val mode = if (prefs.authMode == GeminiAuthMode.API_KEY) "API key" else "Google account"
        if (credential == null) {
            emit(CheckResult("Sign-in", false, "Not signed in ($mode). Open Gemini and tap Continue with Google."))
            return
        }
        emit(CheckResult("Sign-in", true, "Using $mode"))

        // 2. Access token
        if (credential is GeminiCredential.GoogleAccount) {
            val t0 = System.currentTimeMillis()
            val token = runCatching { credential.token(false) }
            if (token.isFailure) {
                emit(CheckResult("Access token", false, token.exceptionOrNull()?.message ?: "Couldn't get a token"))
                return
            }
            emit(CheckResult("Access token", true, "Obtained in ${System.currentTimeMillis() - t0} ms"))
        }

        // 3. Models
        val models = runCatching { GeminiApi.listModels(credential) }
        models.onSuccess { list ->
            val hasCurrent = list.any { it.id == prefs.model }
            emit(
                CheckResult(
                    "Models", if (list.isEmpty()) false else hasCurrent || prefs.model.endsWith("-latest"),
                    "${list.size} chat models available" + if (hasCurrent) " · current model OK" else " · “${prefs.model}” not in list",
                )
            )
        }.onFailure { emit(CheckResult("Models", false, describe(it))) }

        // 4. Plain reply with the chosen thinking level
        val t1 = System.currentTimeMillis()
        val reply = runCatching {
            val body = JSONObject().put("contents", JSONArray().put(userText("Reply with exactly: AURA OK")))
            GeminiApi.generateThinking(credential, prefs.model, body, prefs.thinking)
        }
        reply.onSuccess { r ->
            val text = textOf(r.json)
            emit(CheckResult("Gemini reply", text.isNotBlank(), "“${text.trim().take(60)}” in ${System.currentTimeMillis() - t1} ms"))
            emit(
                CheckResult(
                    "Thinking", if (r.note == null) true else null,
                    "Asked for ${prefs.thinking.label}; model used ${r.applied.label}" + (r.note?.let { " — $it" } ?: ""),
                )
            )
        }.onFailure { emit(CheckResult("Gemini reply", false, describe(it))); return }

        // 5. Function calling (Gemini choosing a phone tool). The tool is NOT executed.
        val t2 = System.currentTimeMillis()
        val call = runCatching {
            val body = JSONObject()
                .put("contents", JSONArray().put(userText("How much battery do I have left?")))
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools.declarations())))
            val parts = GeminiApi.generate(credential, prefs.model, body)
                .optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            (0 until (parts?.length() ?: 0)).mapNotNull { parts!!.getJSONObject(it).optJSONObject("functionCall")?.optString("name") }
        }
        call.onSuccess { names ->
            val ok = "get_device_status" in names
            emit(
                CheckResult(
                    "Phone actions", ok,
                    if (ok) "Gemini chose get_device_status in ${System.currentTimeMillis() - t2} ms"
                    else if (names.isEmpty()) "Gemini answered in text instead of using a tool"
                    else "Gemini chose ${names.joinToString()}",
                )
            )
        }.onFailure { emit(CheckResult("Phone actions", false, describe(it))) }

        // 6. Permissions Gemini's tools rely on
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val perms = listOf(
            "Contacts" to Manifest.permission.READ_CONTACTS,
            "Calls" to Manifest.permission.CALL_PHONE,
            "Microphone" to Manifest.permission.RECORD_AUDIO,
            "Location" to Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val missing = perms.filterNot { granted(it.second) }.map { it.first }
        emit(
            CheckResult(
                "Permissions", if (missing.isEmpty()) true else null,
                if (missing.isEmpty()) "All granted" else "Not yet granted: ${missing.joinToString()} (asked when first needed)",
            )
        )
        emit(
            CheckResult(
                "AURA gestures", null,
                if (SystemPanels.isGestureServiceEnabled(context)) "Accessibility service on" else "Accessibility service off",
            )
        )
    }

    fun report(results: List<CheckResult>): String =
        "AURA Gemini check\n" + results.joinToString("\n") { r ->
            val mark = when (r.ok) { true -> "✓"; false -> "✗"; null -> "•" }
            "$mark ${r.name}: ${r.detail}"
        }

    private fun userText(text: String) =
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun textOf(response: JSONObject): String {
        val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        return (0 until (parts?.length() ?: 0)).joinToString("") {
            val p = parts!!.getJSONObject(it)
            if (p.optBoolean("thought")) "" else p.optString("text")
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is GeminiHttpException -> "${t.message} [HTTP ${t.code}: ${t.raw.take(160)}]"
        else -> t.message ?: t.javaClass.simpleName
    }
}
