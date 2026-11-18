package com.aura.launcher.gemini

import android.content.Intent
import android.net.Uri
import com.aura.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Why someone reported a Gemini reply. */
enum class ReportReason(val label: String) {
    OFFENSIVE("Offensive or hateful"),
    HARMFUL("Harmful or dangerous"),
    SEXUAL("Sexual content"),
    WRONG("Wrong or misleading"),
    OTHER("Something else"),
}

/**
 * Reports a Gemini reply to the developer, as Google Play requires for apps with AI chat.
 * With a report endpoint configured (store builds), the report is sent from inside the app;
 * otherwise a pre-filled issue on the project's GitHub opens for the person to submit.
 * Only the reply, the reason, the model and the app version are included: no account or prompt.
 */
object ReplyReports {
    const val ISSUES_URL = "https://github.com/SKILLEDxKILLAH/AURA-Launcher/issues/new"

    val hasEndpoint: Boolean get() = BuildConfig.REPORT_URL.isNotBlank()

    fun payload(reply: String, reason: ReportReason, model: String): JSONObject = JSONObject()
        .put("type", "gemini_reply_report")
        .put("reason", reason.name)
        .put("reply", reply.take(4000))
        .put("model", model)
        .put("app_version", BuildConfig.VERSION_NAME)

    /** Sends to the configured endpoint; true when it was accepted. */
    suspend fun send(reply: String, reason: ReportReason, model: String, endpoint: String = BuildConfig.REPORT_URL): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(payload(reply, reason, model).toString().toByteArray()) }
                    conn.responseCode in 200..399
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
        }

    /** A GitHub "new issue" page with the report filled in, for builds without an endpoint. */
    fun issueIntent(reply: String, reason: ReportReason, model: String): Intent {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val body = "**Reason:** ${reason.label}\n**Model:** $model\n**AURA:** ${BuildConfig.VERSION_NAME}\n\n**Gemini's reply:**\n> " +
            reply.take(1500).replace("\n", "\n> ")
        val url = "$ISSUES_URL?labels=${enc("ai-report")}&title=${enc("Reported Gemini reply: ${reason.label}")}&body=${enc(body)}"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }
}
