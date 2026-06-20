package com.aichat.sandbox.data.notes

import android.util.Log
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.ImageAttachment
import com.aichat.sandbox.data.model.ImageMetadata
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.data.model.ModelCapabilities
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.remote.ChatStreamer
import com.aichat.sandbox.data.remote.StreamEvent
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core AI request pipeline for the notes feature (sub-phase 2.5 of
 * `docs/STYLUS_NOTES_PHASE_2.md`). No UI, no canned-prompt routing — just the
 * vision-vs-OCR branch and a [Flow] of [AiChunk]s.
 *
 * Vision branch: rasterize the selection (or whole note) at `MAX_EDGE_PX`,
 * base64-encode the PNG, and hand it to [ChatStreamer] as a synthetic
 * multimodal user message. The image is the user's input; the
 * `userPrompt` is the user's question about it.
 *
 * Non-vision branch: run [HandwritingOcr] over the relevant strokes (lazy —
 * the selection path always re-runs; the whole-note path prefers
 * `Note.ocrText` if it's already populated) and send a plain text prompt.
 *
 * Each call is one-shot — the side sheet packs prior turns separately
 * (intentional; see 2.6 risks). Cancellation propagates naturally because
 * the downstream stream is a cold [Flow].
 */
@Singleton
class NoteAiService @Inject constructor(
    private val chatStreamer: ChatStreamer,
    private val ocr: HandwritingRecognizer,
    private val imageRenderer: NoteImageRenderer = NoteRasterizerImageRenderer,
    // Debug capture of each exchange (prompt + raw reply + outcome). A no-op
    // unless the "Capture AI debug log" setting is on; default instance keeps
    // existing unit-test constructors working without wiring it.
    private val aiDebugLog: AiDebugLog = AiDebugLog(),
) {

    fun ask(request: AskRequest): Flow<AiChunk> = flow {
        val caps = ModelCapabilities.of(request.modelId)
        if (request.mode == AskMode.DESIGN_BRUSH) {
            collectDesignBrush(request)
            return@flow
        }
        if (request.mode == AskMode.SUGGEST_PALETTE) {
            collectPalette(request, caps.supportsVision)
            return@flow
        }
        if (request.mode == AskMode.CRITIQUE) {
            collectCritique(request, caps.supportsVision)
            return@flow
        }
        if (request.mode == AskMode.RESTYLE) {
            collectRestyle(request, caps.supportsVision)
            return@flow
        }
        if (request.mode == AskMode.SUGGEST_METADATA) {
            collectMetadata(request, caps.supportsVision)
            return@flow
        }
        if (request.mode == AskMode.EDIT) {
            if (request.generate) {
                collectGenerate(request, caps.supportsVision)
            } else {
                collectEdit(request, caps.supportsVision)
            }
            return@flow
        }
        val upstream = if (caps.supportsVision) {
            buildVisionStream(request)
        } else {
            buildOcrStream(request)
        }
        upstream.collect { event ->
            emit(mapEvent(event))
        }
    }

    /**
     * Sub-phase 7.3 — EDIT-mode dispatcher. Buffers the streamed reply, parses
     * it through [EditOpsParser] on completion, and emits a single
     * [AiChunk.EditPreview] terminal event (plus any deltas the side sheet
     * wants to render as "AI thinking…").
     */
    private suspend fun FlowCollector<AiChunk>.collectEdit(
        request: AskRequest,
        supportsVision: Boolean,
    ) {
        val scopedItems = request.selection ?: request.allItems
        val serialized = VectorCanvasJson.serialize(
            items = scopedItems,
            bounds = null,
            layers = request.layers,
        )
        val preflight = buildPreflight(scopedItems, serialized, pngByteSizeActual = null)
        if (preflight.describe().isNotBlank()) {
            emit(AiChunk.Preflight(preflight))
        }
        if (preflight.requiresExplicitConfirmation && !request.confirmLargeScope) {
            emit(AiChunk.Error(LARGE_SCOPE_CONFIRMATION_MESSAGE))
            return
        }
        val upstream = if (supportsVision) {
            buildEditVisionStream(request, serialized.json)
        } else {
            buildEditOcrStream(request, serialized.json)
        }
        val buffer = StringBuilder()
        var lastUsage: com.aichat.sandbox.data.model.Usage? = null
        var errored = false
        upstream.collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    buffer.append(event.content)
                    // No `Delta` re-emit — EDIT replies are JSON, not prose;
                    // the side sheet renders a fixed "Thinking…" indicator.
                }
                is StreamEvent.Complete -> { lastUsage = event.usage }
                is StreamEvent.Error -> {
                    errored = true
                    emit(AiChunk.Error(event.message))
                }
                is StreamEvent.ToolCallDelta -> { /* impossible in this flow */ }
            }
        }
        val mode = if (request.isIcon) "ICON_EDIT" else "EDIT"
        if (errored) {
            aiDebugLog.record(mode, request.modelId, serialized.json, buffer.toString(), "stream error")
            return
        }
        val parseResult = EditOpsParser.parse(
            raw = buffer.toString(),
            knownIds = serialized.idMap.keys,
            knownLayers = serialized.layerMap.keys,
        )
        parseResult.fold(
            onSuccess = { doc ->
                aiDebugLog.record(
                    mode = mode,
                    modelId = request.modelId,
                    request = serialized.json,
                    rawReply = buffer.toString(),
                    outcome = "${doc.ops.size} ops accepted, ${doc.rejected.size} rejected",
                    rejections = doc.rejected.map { it.reason },
                )
                emit(AiChunk.EditPreview(
                    doc = doc,
                    idMap = serialized.idMap,
                    layerMap = serialized.layerMap,
                    usage = lastUsage,
                ))
            },
            onFailure = { t ->
                Log.w(TAG, "edit-ops parse failed: ${t.message}")
                aiDebugLog.record(mode, request.modelId, serialized.json, buffer.toString(), "parse failed: ${t.message}")
                emit(AiChunk.Error(PARSE_FAILED_MESSAGE))
            },
        )
    }

    /**
     * Phase 2 — palette assistant dispatcher. Serializes the in-scope colours as
     * [VectorCanvasJson] (so any per-item assignment ids line up with the
     * applier), optionally attaches the rasterized preview for a vision model,
     * buffers the reply, validates it with [PaletteParser], and emits a single
     * [AiChunk.PaletteResult]. Non-mutating — the swatches are surfaced as
     * suggestions, never applied here.
     */
    private suspend fun FlowCollector<AiChunk>.collectPalette(
        request: AskRequest,
        supportsVision: Boolean,
    ) {
        val serialized = VectorCanvasJson.serialize(
            items = request.selection ?: request.allItems,
            bounds = null,
            layers = request.layers,
        )
        val chat = Chat(
            id = SYNTHETIC_CHAT_ID,
            title = "Palette",
            model = request.modelId,
            systemMessage = PaletteParser.SYSTEM_MESSAGE,
        )
        val promptBody = buildPalettePromptBody(request.userPrompt, serialized.json)
        val items = request.selection ?: request.allItems
        val userMessage = if (supportsVision && items.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                val pngBytes = try {
                    imageRenderer.renderToPng(
                        items = items,
                        backgroundStyle = request.note.backgroundStyle,
                        maxEdgePx = MAX_EDGE_PX,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to rasterize note ${request.note.id} for palette", t)
                    null
                }
                if (pngBytes == null) {
                    textMessage(promptBody)
                } else {
                    val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
                    val metadata = gson.toJson(
                        ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri)))
                    )
                    Message(
                        chatId = SYNTHETIC_CHAT_ID,
                        role = MessageRole.USER.value,
                        content = promptBody,
                        contentType = "multimodal",
                        metadata = metadata,
                    )
                }
            }
        } else {
            textMessage(promptBody)
        }
        val upstream = chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = chat,
            messages = listOf(userMessage),
        )
        val buffer = StringBuilder()
        var lastUsage: com.aichat.sandbox.data.model.Usage? = null
        var errored = false
        upstream.collect { event ->
            when (event) {
                is StreamEvent.Delta -> buffer.append(event.content)
                is StreamEvent.Complete -> { lastUsage = event.usage }
                is StreamEvent.Error -> {
                    errored = true
                    emit(AiChunk.Error(event.message))
                }
                is StreamEvent.ToolCallDelta -> { /* impossible in this flow */ }
            }
        }
        if (errored) {
            aiDebugLog.record("PALETTE", request.modelId, serialized.json, buffer.toString(), "stream error")
            return
        }
        PaletteParser.parse(buffer.toString(), knownIds = serialized.idMap.keys).fold(
            onSuccess = { suggestion ->
                aiDebugLog.record("PALETTE", request.modelId, serialized.json, buffer.toString(), "palette parsed")
                emit(AiChunk.PaletteResult(
                    suggestion = suggestion,
                    idMap = serialized.idMap,
                    usage = lastUsage,
                ))
            },
            onFailure = { t ->
                Log.w(TAG, "palette parse failed: ${t.message}")
                aiDebugLog.record("PALETTE", request.modelId, serialized.json, buffer.toString(), "parse failed: ${t.message}")
                emit(AiChunk.Error(PARSE_FAILED_MESSAGE))
            },
        )
    }

    private fun textMessage(body: String): Message = Message(
        chatId = SYNTHETIC_CHAT_ID,
        role = MessageRole.USER.value,
        content = body,
        contentType = "text",
        metadata = null,
    )

    /**
     * Build the palette prompt body. Exposed `internal` so a unit test can pin
     * the wire format. The user's scheme hint leads; the canvas JSON follows so
     * the model can read the current colours and reference items by id.
     */
    internal fun buildPalettePromptBody(userPrompt: String, vectorJson: String): String = buildString {
        if (userPrompt.isNotBlank()) {
            append(userPrompt)
        } else {
            append("Suggest a cohesive colour palette for this drawing.")
        }
        append("\n\n")
        append("Here is the vector JSON of the in-scope items (reference colours by `id`):\n")
        append("```json\n")
        append(vectorJson)
        append("\n```")
    }

    /**
     * Phase 3 — composition-critique dispatcher. Serializes the in-scope
     * geometry as [VectorCanvasJson] (so any per-suggestion op ids line up with
     * the applier), optionally attaches the rasterized preview for a vision
     * model, buffers the reply, validates it with [CritiqueParser], and emits a
     * single [AiChunk.CritiqueResult]. Non-mutating — the suggestions are
     * surfaced as advisory cards; applying a fix is a separate, previewable step.
     */
    private suspend fun FlowCollector<AiChunk>.collectCritique(
        request: AskRequest,
        supportsVision: Boolean,
    ) {
        val serialized = VectorCanvasJson.serialize(
            items = request.selection ?: request.allItems,
            bounds = null,
            layers = request.layers,
        )
        val chat = Chat(
            id = SYNTHETIC_CHAT_ID,
            title = "Critique",
            model = request.modelId,
            systemMessage = CritiqueParser.SYSTEM_MESSAGE,
        )
        val items = request.selection ?: request.allItems
        // Fall back to OCR text only when we can't show the model the image; a
        // vision model reads the drawing directly.
        val ocrText = if (supportsVision) null
            else resolveOcrText(request).takeIf { it.isNotBlank() }
        val promptBody = buildCritiquePromptBody(request.userPrompt, serialized.json, ocrText)
        val userMessage = if (supportsVision && items.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                val pngBytes = try {
                    imageRenderer.renderToPng(
                        items = items,
                        backgroundStyle = request.note.backgroundStyle,
                        maxEdgePx = MAX_EDGE_PX,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to rasterize note ${request.note.id} for critique", t)
                    null
                }
                if (pngBytes == null) {
                    textMessage(promptBody)
                } else {
                    val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
                    val metadata = gson.toJson(
                        ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri)))
                    )
                    Message(
                        chatId = SYNTHETIC_CHAT_ID,
                        role = MessageRole.USER.value,
                        content = promptBody,
                        contentType = "multimodal",
                        metadata = metadata,
                    )
                }
            }
        } else {
            textMessage(promptBody)
        }
        val upstream = chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = chat,
            messages = listOf(userMessage),
        )
        val buffer = StringBuilder()
        var lastUsage: com.aichat.sandbox.data.model.Usage? = null
        var errored = false
        upstream.collect { event ->
            when (event) {
                is StreamEvent.Delta -> buffer.append(event.content)
                is StreamEvent.Complete -> { lastUsage = event.usage }
                is StreamEvent.Error -> {
                    errored = true
                    emit(AiChunk.Error(event.message))
                }
                is StreamEvent.ToolCallDelta -> { /* impossible in this flow */ }
            }
        }
        if (errored) {
            aiDebugLog.record("CRITIQUE", request.modelId, serialized.json, buffer.toString(), "stream error")
            return
        }
        CritiqueParser.parse(
            raw = buffer.toString(),
            knownIds = serialized.idMap.keys,
            knownLayers = serialized.layerMap.keys,
        ).fold(
            onSuccess = { critique ->
                aiDebugLog.record("CRITIQUE", request.modelId, serialized.json, buffer.toString(), "critique parsed")
                emit(AiChunk.CritiqueResult(
                    critique = critique,
                    idMap = serialized.idMap,
                    layerMap = serialized.layerMap,
                    usage = lastUsage,
                ))
            },
            onFailure = { t ->
                Log.w(TAG, "critique parse failed: ${t.message}")
                aiDebugLog.record("CRITIQUE", request.modelId, serialized.json, buffer.toString(), "parse failed: ${t.message}")
                emit(AiChunk.Error(PARSE_FAILED_MESSAGE))
            },
        )
    }

    /**
     * Build the critique prompt body. Exposed `internal` so a unit test can pin
     * the wire format. Any OCR transcription leads (non-vision branch only), the
     * user's framing follows, then the canvas JSON so the model can reference
     * items by id when it proposes a fix.
     */
    internal fun buildCritiquePromptBody(
        userPrompt: String,
        vectorJson: String,
        ocrText: String?,
    ): String = buildString {
        if (!ocrText.isNullOrBlank()) {
            append("Transcribed note (may have OCR errors):\n")
            append(ocrText)
            append("\n\n")
        }
        if (userPrompt.isNotBlank()) {
            append(userPrompt)
        } else {
            append("How can I improve the composition and layout of this drawing?")
        }
        append("\n\n")
        append("Here is the vector JSON of the in-scope items (reference items by `id`):\n")
        append("```json\n")
        append(vectorJson)
        append("\n```")
    }

    /**
     * Phase 9 — **metadata & accessibility** dispatcher. Serializes the in-scope
     * geometry as [VectorCanvasJson], attaches the rasterized preview for a
     * vision model (or OCR text otherwise), buffers the reply, validates it with
     * [MetadataParser], and emits a single [AiChunk.MetadataResult]. Non-mutating
     * — the title / tags / description are surfaced as editable suggestions and
     * applied (if at all) only to the note row, the tag table, and export alt
     * text; canvas geometry is never touched.
     */
    private suspend fun FlowCollector<AiChunk>.collectMetadata(
        request: AskRequest,
        supportsVision: Boolean,
    ) {
        val serialized = VectorCanvasJson.serialize(
            items = request.selection ?: request.allItems,
            bounds = null,
            layers = request.layers,
        )
        val chat = Chat(
            id = SYNTHETIC_CHAT_ID,
            title = "Metadata",
            model = request.modelId,
            systemMessage = MetadataParser.SYSTEM_MESSAGE,
        )
        val items = request.selection ?: request.allItems
        // OCR transcription helps a non-vision model title/tag a handwritten
        // note; a vision model reads the image directly.
        val ocrText = if (supportsVision) null
            else resolveOcrText(request).takeIf { it.isNotBlank() }
        val promptBody = buildMetadataPromptBody(serialized.json, ocrText)
        val userMessage = if (supportsVision && items.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                val pngBytes = try {
                    imageRenderer.renderToPng(
                        items = items,
                        backgroundStyle = request.note.backgroundStyle,
                        maxEdgePx = MAX_EDGE_PX,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to rasterize note ${request.note.id} for metadata", t)
                    null
                }
                if (pngBytes == null) {
                    textMessage(promptBody)
                } else {
                    val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
                    val metadata = gson.toJson(
                        ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri)))
                    )
                    Message(
                        chatId = SYNTHETIC_CHAT_ID,
                        role = MessageRole.USER.value,
                        content = promptBody,
                        contentType = "multimodal",
                        metadata = metadata,
                    )
                }
            }
        } else {
            textMessage(promptBody)
        }
        val upstream = chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = chat,
            messages = listOf(userMessage),
        )
        val buffer = StringBuilder()
        var lastUsage: com.aichat.sandbox.data.model.Usage? = null
        var errored = false
        upstream.collect { event ->
            when (event) {
                is StreamEvent.Delta -> buffer.append(event.content)
                is StreamEvent.Complete -> { lastUsage = event.usage }
                is StreamEvent.Error -> {
                    errored = true
                    emit(AiChunk.Error(event.message))
                }
                is StreamEvent.ToolCallDelta -> { /* impossible in this flow */ }
            }
        }
        if (errored) {
            aiDebugLog.record("METADATA", request.modelId, serialized.json, buffer.toString(), "stream error")
            return
        }
        MetadataParser.parse(buffer.toString()).fold(
            onSuccess = { suggestion ->
                aiDebugLog.record("METADATA", request.modelId, serialized.json, buffer.toString(), "metadata parsed")
                emit(AiChunk.MetadataResult(suggestion = suggestion, usage = lastUsage))
            },
            onFailure = { t ->
                Log.w(TAG, "metadata parse failed: ${t.message}")
                aiDebugLog.record("METADATA", request.modelId, serialized.json, buffer.toString(), "parse failed: ${t.message}")
                emit(AiChunk.Error(PARSE_FAILED_MESSAGE))
            },
        )
    }

    /**
     * Build the metadata prompt body. Exposed `internal` so a unit test can pin
     * the wire format. Any OCR transcription leads (non-vision branch only), then
     * the canvas JSON so the model can read the drawing's structure.
     */
    internal fun buildMetadataPromptBody(
        vectorJson: String,
        ocrText: String?,
    ): String = buildString {
        if (!ocrText.isNullOrBlank()) {
            append("Transcribed note (may have OCR errors):\n")
            append(ocrText)
            append("\n\n")
        }
        append("Suggest a title, tags, and a short description for this drawing.")
        append("\n\n")
        append("Here is the vector JSON of the in-scope items:\n")
        append("```json\n")
        append(vectorJson)
        append("\n```")
    }

    /**
     * Phase 7 — named-style **restyle** dispatcher. Mirrors [collectEdit] /
     * [collectCritique]: serializes the in-scope geometry as [VectorCanvasJson]
     * (so op ids line up with the applier), optionally attaches the rasterized
     * preview for a vision model, buffers the reply, and validates it through
     * [RestyleParser] — which keeps only the non-additive, non-moving op subset
     * so a restyle can never add new subject matter. Emits a single
     * [AiChunk.EditPreview] so the result stages through exactly the same
     * preview/diff/accept-reject surface as any other AI edit.
     */
    private suspend fun FlowCollector<AiChunk>.collectRestyle(
        request: AskRequest,
        supportsVision: Boolean,
    ) {
        val serialized = VectorCanvasJson.serialize(
            items = request.selection ?: request.allItems,
            bounds = null,
            layers = request.layers,
        )
        val chat = Chat(
            id = SYNTHETIC_CHAT_ID,
            title = "Restyle",
            model = request.modelId,
            systemMessage = RestyleParser.SYSTEM_MESSAGE,
        )
        val items = request.selection ?: request.allItems
        val ocrText = if (supportsVision) null
            else resolveOcrText(request).takeIf { it.isNotBlank() }
        val promptBody = buildRestylePromptBody(request.userPrompt, serialized.json, ocrText)
        val userMessage = if (supportsVision && items.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                val pngBytes = try {
                    imageRenderer.renderToPng(
                        items = items,
                        backgroundStyle = request.note.backgroundStyle,
                        maxEdgePx = MAX_EDGE_PX,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to rasterize note ${request.note.id} for restyle", t)
                    null
                }
                if (pngBytes == null) {
                    textMessage(promptBody)
                } else {
                    val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
                    val metadata = gson.toJson(
                        ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri)))
                    )
                    Message(
                        chatId = SYNTHETIC_CHAT_ID,
                        role = MessageRole.USER.value,
                        content = promptBody,
                        contentType = "multimodal",
                        metadata = metadata,
                    )
                }
            }
        } else {
            textMessage(promptBody)
        }
        val upstream = chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = chat,
            messages = listOf(userMessage),
        )
        val buffer = StringBuilder()
        var lastUsage: com.aichat.sandbox.data.model.Usage? = null
        var errored = false
        upstream.collect { event ->
            when (event) {
                is StreamEvent.Delta -> buffer.append(event.content)
                is StreamEvent.Complete -> { lastUsage = event.usage }
                is StreamEvent.Error -> {
                    errored = true
                    emit(AiChunk.Error(event.message))
                }
                is StreamEvent.ToolCallDelta -> { /* impossible in this flow */ }
            }
        }
        if (errored) {
            aiDebugLog.record("RESTYLE", request.modelId, serialized.json, buffer.toString(), "stream error")
            return
        }
        RestyleParser.parse(
            raw = buffer.toString(),
            knownIds = serialized.idMap.keys,
            knownLayers = serialized.layerMap.keys,
        ).fold(
            onSuccess = { doc ->
                aiDebugLog.record(
                    mode = "RESTYLE",
                    modelId = request.modelId,
                    request = serialized.json,
                    rawReply = buffer.toString(),
                    outcome = "${doc.ops.size} ops accepted, ${doc.rejected.size} rejected",
                    rejections = doc.rejected.map { it.reason },
                )
                emit(AiChunk.EditPreview(
                    doc = doc,
                    idMap = serialized.idMap,
                    layerMap = serialized.layerMap,
                    usage = lastUsage,
                ))
            },
            onFailure = { t ->
                Log.w(TAG, "restyle parse failed: ${t.message}")
                aiDebugLog.record("RESTYLE", request.modelId, serialized.json, buffer.toString(), "parse failed: ${t.message}")
                emit(AiChunk.Error(PARSE_FAILED_MESSAGE))
            },
        )
    }

    /**
     * Build the restyle prompt body. Exposed `internal` so a unit test can pin
     * the wire format. Any OCR transcription leads (non-vision branch only), the
     * preset instruction (from [StylePreset.buildInstruction]) follows, then the
     * canvas JSON so the model references items by id.
     */
    internal fun buildRestylePromptBody(
        userPrompt: String,
        vectorJson: String,
        ocrText: String?,
    ): String = buildString {
        if (!ocrText.isNullOrBlank()) {
            append("Transcribed note (may have OCR errors):\n")
            append(ocrText)
            append("\n\n")
        }
        if (userPrompt.isNotBlank()) {
            append(userPrompt)
        } else {
            append("Restyle these items into a cleaner, more cohesive look.")
        }
        append("\n\n")
        append("Here is the vector JSON of the items to restyle (reference items by `id`; ")
        append("restyle only these — do not add new ones):\n")
        append("```json\n")
        append(vectorJson)
        append("\n```")
    }

    /**
     * Phase 17.5 #1 — generation dispatcher. The model authors a brand-new
     * icon from scratch (no raster, no existing-item id space) in the style of
     * [AskRequest.styleReferences], which ride in the system message. The reply
     * is parsed with an empty `knownIds` set so any stray modify op (which
     * would reference a non-existent id) is dropped — only `add_*` ops survive.
     * Emits a single [AiChunk.EditPreview] with empty id / layer maps.
     */
    private suspend fun FlowCollector<AiChunk>.collectGenerate(
        request: AskRequest,
        supportsVision: Boolean,
    ) {
        // 17.5 #2 — "Make real" refines a selected sketch: show the model the
        // rasterized sketch (vision) and ask it to redraw it cleanly. Falls
        // back to text-only authoring when the model can't see images.
        val refineItems = request.selection?.takeIf { request.refine && it.isNotEmpty() }
        val refining = refineItems != null
        // Phase 8 — a scene generates several grouped objects (never a refine).
        val scene = request.scene && !refining
        val systemMessage = when {
            refining -> EditOpsParser.ICON_REFINE_SYSTEM_MESSAGE
            scene -> EditOpsParser.SCENE_GENERATE_SYSTEM_MESSAGE
            else -> EditOpsParser.buildIconGenerateSystemMessage(request.styleReferences)
        }
        val userMessage = if (refining && supportsVision) {
            buildRefineVisionMessage(request, refineItems!!) ?: return emit(AiChunk.Error(RENDER_FAILED_MESSAGE))
        } else {
            Message(
                chatId = SYNTHETIC_CHAT_ID,
                role = MessageRole.USER.value,
                content = when {
                    refining -> buildRefinePromptBody(request.userPrompt)
                    scene -> buildScenePromptBody(request.userPrompt, request.sceneComplexity)
                    else -> buildGeneratePromptBody(request.userPrompt)
                },
                contentType = "text",
                metadata = null,
            )
        }
        val chat = Chat(
            id = SYNTHETIC_CHAT_ID,
            title = when {
                refining -> "Icon Refine"
                scene -> "Scene Generate"
                else -> "Icon Generate"
            },
            model = request.modelId,
            systemMessage = systemMessage,
        )
        val upstream = chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = chat,
            messages = listOf(userMessage),
        )
        val buffer = StringBuilder()
        var lastUsage: com.aichat.sandbox.data.model.Usage? = null
        var errored = false
        upstream.collect { event ->
            when (event) {
                is StreamEvent.Delta -> buffer.append(event.content)
                is StreamEvent.Complete -> { lastUsage = event.usage }
                is StreamEvent.Error -> {
                    errored = true
                    emit(AiChunk.Error(event.message))
                }
                is StreamEvent.ToolCallDelta -> { /* impossible in this flow */ }
            }
        }
        val mode = when {
            refining -> "REFINE"
            scene -> "SCENE"
            else -> "GENERATE"
        }
        if (errored) {
            aiDebugLog.record(mode, request.modelId, systemMessage, buffer.toString(), "stream error")
            return
        }
        EditOpsParser.parse(raw = buffer.toString(), knownIds = emptySet(), knownLayers = emptySet())
            .fold(
                onSuccess = { parsed ->
                    // Phase 8 — bound a scene's object count so a runaway reply
                    // stays compact (extras land in `rejected`, not on canvas).
                    val doc = if (scene) {
                        SceneGen.capSceneAddOps(parsed, request.sceneComplexity.maxObjects)
                    } else {
                        parsed
                    }
                    aiDebugLog.record(
                        mode = mode,
                        modelId = request.modelId,
                        request = systemMessage,
                        rawReply = buffer.toString(),
                        outcome = "${doc.ops.size} ops accepted, ${doc.rejected.size} rejected",
                        rejections = doc.rejected.map { it.reason },
                    )
                    emit(AiChunk.EditPreview(
                        doc = doc,
                        idMap = emptyMap(),
                        layerMap = emptyMap(),
                        usage = lastUsage,
                    ))
                },
                onFailure = { t ->
                    Log.w(TAG, "icon-generate parse failed: ${t.message}")
                    aiDebugLog.record(mode, request.modelId, systemMessage, buffer.toString(), "parse failed: ${t.message}")
                    emit(AiChunk.Error(PARSE_FAILED_MESSAGE))
                },
            )
    }

    /**
     * Phase I4 / N1 — **AI brush designer** dispatcher. A pure text round-trip:
     * the user describes a brush ("a dry-gouache brush with a soft taper"), the
     * model replies with a small brush-spec JSON, and [BrushSpecParser] validates
     * it into a [BrushSpec]. Emits a single [AiChunk.BrushDesign]. No raster, no
     * id space, no canvas mutation — only the user's brush library grows — so it
     * never touches the `StrokeCodec` / edit-ops pipeline.
     */
    private suspend fun FlowCollector<AiChunk>.collectDesignBrush(request: AskRequest) {
        val chat = Chat(
            id = SYNTHETIC_CHAT_ID,
            title = "Brush Designer",
            model = request.modelId,
            systemMessage = DESIGN_BRUSH_SYSTEM_MESSAGE,
        )
        val userMessage = Message(
            chatId = SYNTHETIC_CHAT_ID,
            role = MessageRole.USER.value,
            content = buildDesignBrushPromptBody(request.userPrompt),
            contentType = "text",
            metadata = null,
        )
        val upstream = chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = chat,
            messages = listOf(userMessage),
        )
        val buffer = StringBuilder()
        var lastUsage: com.aichat.sandbox.data.model.Usage? = null
        var errored = false
        upstream.collect { event ->
            when (event) {
                is StreamEvent.Delta -> buffer.append(event.content)
                is StreamEvent.Complete -> { lastUsage = event.usage }
                is StreamEvent.Error -> {
                    errored = true
                    emit(AiChunk.Error(event.message))
                }
                is StreamEvent.ToolCallDelta -> { /* impossible in this flow */ }
            }
        }
        val brushReq = buildDesignBrushPromptBody(request.userPrompt)
        if (errored) {
            aiDebugLog.record("DESIGN_BRUSH", request.modelId, brushReq, buffer.toString(), "stream error")
            return
        }
        BrushSpecParser.parse(buffer.toString()).fold(
            onSuccess = { spec ->
                aiDebugLog.record("DESIGN_BRUSH", request.modelId, brushReq, buffer.toString(), "brush spec parsed")
                emit(AiChunk.BrushDesign(spec = spec, usage = lastUsage))
            },
            onFailure = { t ->
                Log.w(TAG, "brush-spec parse failed: ${t.message}")
                aiDebugLog.record("DESIGN_BRUSH", request.modelId, brushReq, buffer.toString(), "parse failed: ${t.message}")
                emit(AiChunk.Error(PARSE_FAILED_MESSAGE))
            },
        )
    }

    /**
     * Build the brush-designer prompt body. Exposed `internal` so a unit test can
     * pin the wire format (mirrors [buildGeneratePromptBody]).
     */
    internal fun buildDesignBrushPromptBody(userPrompt: String): String = buildString {
        append(userPrompt)
        append("\n\n")
        append("Design a single reusable brush matching that description. ")
        append("Reply with the brush-spec JSON only.")
    }

    /**
     * Build the generation prompt body. Exposed `internal` so the Phase 17.5
     * test can pin the wire format. Includes the artboard edge so the model's
     * coordinates land inside the icon canvas.
     */
    internal fun buildGeneratePromptBody(userPrompt: String): String = buildString {
        append(userPrompt)
        append("\n\n")
        append("Design this as a new icon on a square artboard, ")
        append(ICON_ARTBOARD_WORLD.toInt())
        append("×")
        append(ICON_ARTBOARD_WORLD.toInt())
        append(" units, top-left at (0,0). Author the geometry with add_path / add_shape ops.")
    }

    /**
     * Phase 8 — build the scene-generation prompt body. Exposed `internal` so a
     * unit test can pin the wire format. States the square artboard edge (so the
     * model's coordinates land inside it before the editor's fit), the object
     * cap, and the complexity hint.
     */
    internal fun buildScenePromptBody(userPrompt: String, complexity: SceneComplexity): String = buildString {
        append(userPrompt)
        append("\n\n")
        append("Design this as a small editable scene on a square artboard, ")
        append(SceneGen.SCENE_ARTBOARD_WORLD.toInt())
        append("×")
        append(SceneGen.SCENE_ARTBOARD_WORLD.toInt())
        append(" units, top-left at (0,0). Use at most ")
        append(complexity.maxObjects)
        append(" objects, each authored with add_path / add_shape ops and tagged ")
        append("with a \"group\" label. ")
        append(complexity.promptHint)
    }

    /**
     * 17.5 #2 — build the multimodal refine message: the rasterized sketch as
     * an image plus the refine instruction. Returns null when rasterization
     * fails so the caller can surface [RENDER_FAILED_MESSAGE].
     */
    private suspend fun buildRefineVisionMessage(
        request: AskRequest,
        items: List<NoteItem>,
    ): Message? = withContext(Dispatchers.Default) {
        val pngBytes = try {
            imageRenderer.renderToPng(
                items = items,
                backgroundStyle = request.note.backgroundStyle,
                maxEdgePx = MAX_EDGE_PX,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to rasterize sketch for refine", t)
            null
        } ?: return@withContext null
        val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
        val metadata = gson.toJson(
            ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri)))
        )
        Message(
            chatId = SYNTHETIC_CHAT_ID,
            role = MessageRole.USER.value,
            content = buildRefinePromptBody(request.userPrompt),
            contentType = "multimodal",
            metadata = metadata,
        )
    }

    /** 17.5 #2 — refine instruction body (shared by the vision + text paths). */
    internal fun buildRefinePromptBody(userPrompt: String): String = buildString {
        append("Redraw the sketch in the image as a clean vector, authoring the ")
        append("geometry with add_path / add_shape ops at roughly the sketch's ")
        append("own coordinates.")
        if (userPrompt.isNotBlank()) {
            append("\n\n")
            append(userPrompt)
        }
    }

    private suspend fun buildVisionStream(request: AskRequest): Flow<StreamEvent> {
        val items = request.selection ?: request.allItems
        if (items.isEmpty()) {
            return errorFlow(EMPTY_NOTE_MESSAGE)
        }
        // Bitmap rasterization is CPU-bound; keep it off whatever dispatcher
        // the caller is on (typically `viewModelScope` → Main) so the editor
        // stays responsive while a 1536px PNG is being produced.
        val userMessage = withContext(Dispatchers.Default) {
            val pngBytes = try {
                imageRenderer.renderToPng(
                    items = items,
                    backgroundStyle = request.note.backgroundStyle,
                    maxEdgePx = MAX_EDGE_PX,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to rasterize note ${request.note.id}", t)
                null
            } ?: return@withContext null

            val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
            val metadata = gson.toJson(
                ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri)))
            )
            Message(
                chatId = SYNTHETIC_CHAT_ID,
                role = MessageRole.USER.value,
                content = request.userPrompt,
                contentType = "multimodal",
                metadata = metadata,
            )
        } ?: return errorFlow(RENDER_FAILED_MESSAGE)

        return chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = syntheticChat(request),
            messages = listOf(userMessage),
        )
    }

    private suspend fun buildOcrStream(request: AskRequest): Flow<StreamEvent> {
        val transcribed = resolveOcrText(request)
        val body = buildString {
            if (transcribed.isNotBlank()) {
                append("Transcribed note (may have OCR errors):\n")
                append(transcribed)
                append("\n\n")
            } else {
                append("(The note contains no recognizable handwriting; ")
                append("the user is asking about its non-text contents.)\n\n")
            }
            append("User question:\n")
            append(request.userPrompt)
        }
        val userMessage = Message(
            chatId = SYNTHETIC_CHAT_ID,
            role = MessageRole.USER.value,
            content = body,
            contentType = "text",
            metadata = null,
        )
        return chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = syntheticChat(request),
            messages = listOf(userMessage),
        )
    }

    /**
     * Resolve OCR text for the non-vision branch.
     *
     * - Selection scope: always re-run OCR over the selection. We can't cache
     *   per-selection results, and the cached `Note.ocrText` covers the whole
     *   note (would over-share the context).
     * - Whole-note scope: prefer `Note.ocrText` if present so we don't pay
     *   for a recognizer pass on every ask; fall back to a synchronous run
     *   if the field is empty (e.g. note was saved before 2.4 landed, or a
     *   prior OCR pass came back empty).
     */
    private suspend fun resolveOcrText(request: AskRequest): String {
        val selection = request.selection
        if (selection != null) {
            return ocr.recognize(selection.filter { it.kind == STROKE_KIND }).text
        }
        val cached = request.note.ocrText
        if (!cached.isNullOrBlank()) return cached
        val strokes = request.allItems.filter { it.kind == STROKE_KIND }
        if (strokes.isEmpty()) return ""
        return ocr.recognize(strokes).text
    }

    /**
     * Sub-phase 7.3 — vision EDIT branch. Sends the rasterised PNG plus the
     * vector JSON inline in the prompt body, using the Phase 7.2 system
     * message instead of the conversational one.
     */
    private suspend fun buildEditVisionStream(
        request: AskRequest,
        vectorJson: String,
    ): Flow<StreamEvent> {
        val items = request.selection ?: request.allItems
        if (items.isEmpty()) return errorFlow(EMPTY_NOTE_MESSAGE)
        val userMessage = withContext(Dispatchers.Default) {
            val pngBytes = try {
                imageRenderer.renderToPng(
                    items = items,
                    backgroundStyle = request.note.backgroundStyle,
                    maxEdgePx = MAX_EDGE_PX,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to rasterize note ${request.note.id} for EDIT", t)
                null
            } ?: return@withContext null
            val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
            val metadata = gson.toJson(
                ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri)))
            )
            Message(
                chatId = SYNTHETIC_CHAT_ID,
                role = MessageRole.USER.value,
                content = buildEditPromptBody(request.userPrompt, vectorJson, ocrText = null),
                contentType = "multimodal",
                metadata = metadata,
            )
        } ?: return errorFlow(RENDER_FAILED_MESSAGE)

        return chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = syntheticEditChat(request),
            messages = listOf(userMessage),
        )
    }

    /**
     * Sub-phase 7.3 — non-vision EDIT branch. OCR text + vector JSON inline,
     * no image attachment.
     */
    private suspend fun buildEditOcrStream(
        request: AskRequest,
        vectorJson: String,
    ): Flow<StreamEvent> {
        val transcribed = resolveOcrText(request)
        val message = Message(
            chatId = SYNTHETIC_CHAT_ID,
            role = MessageRole.USER.value,
            content = buildEditPromptBody(
                userPrompt = request.userPrompt,
                vectorJson = vectorJson,
                ocrText = transcribed.takeIf { it.isNotBlank() },
            ),
            contentType = "text",
            metadata = null,
        )
        return chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = syntheticEditChat(request),
            messages = listOf(message),
        )
    }

    /**
     * Build the prompt body for an EDIT request. Exposed `internal` so the
     * Phase 7.3 unit test can pin the exact wire format.
     */
    internal fun buildEditPromptBody(
        userPrompt: String,
        vectorJson: String,
        ocrText: String?,
    ): String = buildString {
        if (!ocrText.isNullOrBlank()) {
            append("Transcribed note (may have OCR errors):\n")
            append(ocrText)
            append("\n\n")
        }
        append(userPrompt)
        append("\n\n")
        append("Here is the vector JSON of the note. Edit by referencing IDs from `items`:\n")
        append("```json\n")
        append(vectorJson)
        append("\n```")
    }

    private fun syntheticChat(request: AskRequest): Chat = Chat(
        id = SYNTHETIC_CHAT_ID,
        title = "Note AI",
        model = request.modelId,
        systemMessage = SYSTEM_INSTRUCTION,
    )

    private fun syntheticEditChat(request: AskRequest): Chat = Chat(
        id = SYNTHETIC_CHAT_ID,
        title = "Note Edit",
        model = request.modelId,
        systemMessage = if (request.isIcon) EditOpsParser.ICON_SYSTEM_MESSAGE
        else EditOpsParser.SYSTEM_MESSAGE,
    )

    private fun mapEvent(event: StreamEvent): AiChunk = when (event) {
        is StreamEvent.Delta -> AiChunk.Delta(event.content)
        is StreamEvent.Complete -> AiChunk.Complete(event.usage)
        is StreamEvent.Error -> AiChunk.Error(event.message)
        // Tool-call deltas can't appear on this path (we never send tools).
        is StreamEvent.ToolCallDelta -> AiChunk.Delta("")
    }

    private fun buildPreflight(
        items: List<NoteItem>,
        serialized: VectorCanvasJson.SerializedCanvas?,
        pngByteSizeActual: Int?,
    ): NoteAiPreflightResult {
        val pixels = MAX_EDGE_PX * MAX_EDGE_PX
        return NoteAiPreflightResult(
            itemCount = items.size,
            rasterPixelSize = pixels,
            // Conservative RGBA upper-bound estimate; actual PNG size is filled
            // when a raster has already been produced by a caller.
            pngByteSizeEstimate = pixels * 4,
            pngByteSizeActual = pngByteSizeActual,
            jsonByteSize = serialized?.jsonByteSize ?: 0,
            droppedItemIds = serialized?.droppedItemIds.orEmpty(),
        )
    }

    private fun errorFlow(message: String): Flow<StreamEvent> = flow {
        emit(StreamEvent.Error(message))
    }

    companion object {
        /**
         * Maximum longest-edge in px when rasterizing the note for a vision
         * call. 1536 keeps inline base64 payloads comfortably under typical
         * provider body limits while still leaving enough resolution for OCR
         * on the model side. Bump down to 1024 if self-hosted backends start
         * rejecting requests; see Phase 2.5 risks.
         */
        const val MAX_EDGE_PX: Int = 1536

        const val LARGE_SCOPE_CONFIRMATION_MESSAGE: String =
            "This AI scope is very large. Confirm that you want to send it before trying again."

        /**
         * Icon artboard edge in world units, mirroring the editor's
         * `ICON_ARTBOARD_WORLD` (768 = 24 × the 32-unit grid). Used only to
         * tell the model where to lay generated geometry (17.5 #1).
         */
        const val ICON_ARTBOARD_WORLD: Float = 768f

        internal const val SYSTEM_INSTRUCTION: String =
            "You are helping the user with a handwritten note. Be concise. " +
                "If the user pasted an image of the note, treat the handwriting as their input; " +
                "transcribe relevant parts when answering."

        /**
         * Phase I4 / N1 — system message for the AI brush designer. Pins the
         * exact JSON contract [BrushSpecParser] validates: a single brush object
         * with only renderable, user-facing fields. Anything out of range is
         * clamped and unknown enum values fall back to defaults, but a tight
         * prompt keeps replies on-spec.
         */
        internal const val DESIGN_BRUSH_SYSTEM_MESSAGE: String =
            "You design drawing brushes. Reply with ONLY a JSON object describing one brush, " +
                "no prose. Schema:\n" +
                "{\n" +
                "  \"schema\": 1,\n" +
                "  \"brush\": {\n" +
                "    \"name\": string,            // short, human label\n" +
                "    \"tool\": \"pen\"|\"pencil\"|\"highlighter\"|\"marker\",\n" +
                "    \"color\": \"#RRGGBB\",        // hex; opacity is separate\n" +
                "    \"width\": number,           // base width in px, 0.5..64\n" +
                "    \"opacity\": number,         // 0..1\n" +
                "    \"taperStart\": number,      // 0..1 fraction that fades in\n" +
                "    \"taperEnd\": number,        // 0..1 fraction that fades out\n" +
                "    \"jitter\": number,          // 0..1 width jitter\n" +
                "    \"pressureCurve\": \"LINEAR\"|\"EASE_IN\"|\"EASE_OUT\"|\"EASE_IN_OUT\",\n" +
                "    \"texture\": \"smooth\"|\"charcoal\"|\"watercolor\"|\"marker\"\n" +
                "  }\n" +
                "}\n" +
                "Pick the closest tool and texture for the requested feel. Use opacity for " +
                "translucency (e.g. highlighters), taper for inky pen strokes, and jitter/texture " +
                "for dry/grainy media."

        private const val SYNTHETIC_CHAT_ID: String = "note-ai-synthetic"
        private const val STROKE_KIND: String = "stroke"
        private const val TAG: String = "NoteAiService"
        private const val EMPTY_NOTE_MESSAGE: String = "Note is empty — nothing to send."
        private const val RENDER_FAILED_MESSAGE: String = "Couldn't render the note for the AI request."
        internal const val PARSE_FAILED_MESSAGE: String =
            "Could not parse AI edit response — try rephrasing."

        private val gson = Gson()
    }
}

/**
 * Tiny indirection over [NoteRasterizer] so [NoteAiService] can be unit
 * tested without depending on `android.graphics.Bitmap` (which isn't
 * available on the host JVM). Production code uses
 * [NoteRasterizerImageRenderer] which delegates straight through.
 */
interface NoteImageRenderer {
    fun renderToPng(
        items: List<NoteItem>,
        backgroundStyle: String,
        maxEdgePx: Int,
    ): ByteArray?
}

object NoteRasterizerImageRenderer : NoteImageRenderer {
    override fun renderToPng(
        items: List<NoteItem>,
        backgroundStyle: String,
        maxEdgePx: Int,
    ): ByteArray? {
        val bounds = NoteRasterizer.computeBounds(items) ?: return null
        val bitmap = NoteRasterizer.render(
            items = items,
            bounds = bounds,
            maxEdgePx = maxEdgePx,
            backgroundStyle = backgroundStyle,
        )
        return try {
            NoteRasterizer.toPng(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
