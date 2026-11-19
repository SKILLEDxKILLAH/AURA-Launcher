package com.aura.launcher.gemini

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Whether Gemini Nano can run on this phone right now. */
enum class NanoStatus { CHECKING, AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

/** A language model that runs on the phone. Separate from the ML Kit class so tests can use a fake. */
interface LocalModel {
    suspend fun status(): NanoStatus

    /** Downloads the model if needed, reporting progress from 0 to 1 when the size is known (else -1). */
    suspend fun download(onProgress: (Float) -> Unit)

    /** Answers [prompt] given the earlier turns, calling [onPartial] with the text so far. */
    suspend fun reply(history: List<Pair<Boolean, String>>, prompt: String, onPartial: suspend (String) -> Unit): String

    /** e.g. "Gemini Nano v3", for display. */
    suspend fun name(): String
}

/**
 * Gemini Nano through Android's AICore (ML Kit GenAI Prompt API). Nothing leaves the phone and
 * no account or key is needed. Only some phones have it; [status] says whether this one does.
 */
class MlKitNano : LocalModel {
    private val model: GenerativeModel by lazy { Generation.getClient() }

    override suspend fun status(): NanoStatus = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
            else -> NanoStatus.UNAVAILABLE
        }
    } catch (_: Throwable) {
        // Older Android, no AICore, unlocked bootloader...: simply not available here.
        NanoStatus.UNAVAILABLE
    }

    override suspend fun download(onProgress: (Float) -> Unit) {
        model.download().collect { s ->
            when (s) {
                is DownloadStatus.DownloadStarted -> onProgress(-1f)
                is DownloadStatus.DownloadProgress -> onProgress(-1f)
                is DownloadStatus.DownloadFailed -> throw IOException(friendly(s.e))
                else -> Unit
            }
        }
        onProgress(1f)
    }

    override suspend fun name(): String = runCatching { model.getBaseModelName() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Gemini Nano"

    override suspend fun reply(history: List<Pair<Boolean, String>>, prompt: String, onPartial: suspend (String) -> Unit): String {
        val turns = (history.takeLast(MAX_TURNS) + (true to prompt)).map { (fromUser, text) ->
            Content.Builder().role(if (fromUser) Content.Role.USER else Content.Role.MODEL).text(text.take(MAX_CHARS_PER_TURN)).build()
        }
        val withSystem = runCatching { model.isSystemPromptAvailable() }.getOrDefault(false)
        val request = GenerateContentRequest.Builder(if (withSystem) turns else withInstructionInFirstTurn(turns)).apply {
            if (withSystem) systemInstruction = SystemInstruction(systemPrompt())
            temperature = 0.6f
            maxOutputTokens = 512
        }.build()
        val text = StringBuilder()
        try {
            model.generateContentStream(request).collect { chunk ->
                val piece = chunk.candidates.firstOrNull()?.text.orEmpty()
                // Chunks are new text; if a chunk repeats what came before, use it as the whole.
                if (piece.startsWith(text) && text.isNotEmpty()) { text.setLength(0); text.append(piece) } else text.append(piece)
                onPartial(text.toString().trim())
            }
        } catch (e: GenAiException) {
            throw IOException(friendly(e))
        }
        return text.toString().trim().ifEmpty { throw IOException("Gemini Nano didn't answer — try asking another way") }
    }

    private fun withInstructionInFirstTurn(turns: List<Content>): List<Content> {
        // Phones without system prompts: put the instructions in front of the conversation.
        val intro = Content.Builder().role(Content.Role.USER).text(systemPrompt()).build()
        val ack = Content.Builder().role(Content.Role.MODEL).text("Understood.").build()
        return listOf(intro, ack) + turns
    }

    private fun systemPrompt(): String {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy, h:mm a"))
        return "You are Gemini, running privately on the user's phone inside the AURA launcher. Answer helpfully and briefly, " +
            "in plain sentences without markdown. You can't see the screen, browse the web or control the phone; if asked to, " +
            "say the user can ask AURA directly, for example \"set a timer for 10 minutes\" or \"open Maps\". Now: $now."
    }

    private fun friendly(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("BINDING_FAILURE", true) || msg.contains("CONNECTION_ERROR", true) ->
                "Couldn't reach Android's AI service (AICore). Update “AICore” and “Private Compute Services” in the Play Store, then try again."
            msg.contains("FEATURE_NOT_FOUND", true) || msg.contains("PREPARATION_ERROR", true) ->
                "Gemini Nano is still being set up on this phone. This can take a while after first use — try again later."
            msg.contains("DOWNLOAD", true) -> "Couldn't download Gemini Nano. Check your connection and try again."
            msg.contains("quota", true) || msg.contains("BUSY", true) -> "Gemini Nano is busy — try again in a moment."
            else -> "Gemini Nano couldn't answer (${msg.take(120)})"
        }
    }

    companion object {
        /** Nano's context is about 4,000 tokens, so keep only recent turns. */
        private const val MAX_TURNS = 6
        private const val MAX_CHARS_PER_TURN = 1500
    }
}
