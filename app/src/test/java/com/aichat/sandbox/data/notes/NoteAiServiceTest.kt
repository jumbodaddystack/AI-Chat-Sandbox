package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.model.ToolDefinition
import com.aichat.sandbox.data.model.Usage
import com.aichat.sandbox.data.remote.ChatStreamer
import com.aichat.sandbox.data.remote.StreamEvent
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * JVM coverage of the four behaviours called out in
 * `docs/STYLUS_NOTES_PHASE_2.md` sub-phase 2.5: vision happy-path, OCR
 * fallback prompt construction, error pass-through, and cancellation
 * propagation.
 */
class NoteAiServiceTest {

    @Test
    fun visionStreamPassesThroughChunksUnchanged() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("Hello "),
                StreamEvent.Delta("world"),
                StreamEvent.Complete(Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15)),
            ),
        )
        val imageRenderer = FakeImageRenderer(ByteArray(8) { 1 })
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer("should-not-be-called"),
            imageRenderer = imageRenderer,
        )
        val filesDir = File("/tmp/note-ai-service-files")

        val chunks = service.ask(
            visionRequest(strokes = listOf(strokeItem())).copy(filesDir = filesDir)
        ).toList()

        assertEquals(3, chunks.size)
        assertEquals(AiChunk.Delta("Hello "), chunks[0])
        assertEquals(AiChunk.Delta("world"), chunks[1])
        val complete = chunks[2] as AiChunk.Complete
        assertEquals(15, complete.usage?.totalTokens)

        val sent = streamer.lastMessages
        assertEquals(1, sent.size)
        val sentMsg = sent.single()
        assertEquals("user", sentMsg.role)
        assertEquals("multimodal", sentMsg.contentType)
        assertEquals(VISION_PROMPT, sentMsg.content)
        assertNotNull(sentMsg.metadata)
        assertTrue(
            "image metadata should embed a base64 PNG data URI",
            sentMsg.metadata!!.contains("data:image/png;base64,")
        )
        assertEquals(filesDir, imageRenderer.lastFilesDir)
    }

    @Test
    fun nonVisionStreamIncludesOcrTextInPrompt() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("ack"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer("hello world"),
            // Image renderer must not be touched on the OCR branch.
            imageRenderer = ExplodingImageRenderer,
        )

        val chunks = service.ask(
            nonVisionRequest(
                strokes = listOf(strokeItem()),
                cachedOcrText = null,
            )
        ).toList()

        assertEquals(2, chunks.size)
        assertEquals(AiChunk.Delta("ack"), chunks[0])
        assertTrue(chunks[1] is AiChunk.Complete)

        val body = streamer.lastMessages.single().content
        assertTrue(
            "OCR text should be inlined in the prompt: $body",
            body.contains("hello world")
        )
        assertTrue(
            "User prompt should be preserved verbatim: $body",
            body.contains(NON_VISION_PROMPT)
        )
        assertEquals(
            "Non-vision branch must send a plain text message, not multimodal.",
            "text",
            streamer.lastMessages.single().contentType,
        )
    }

    @Test
    fun nonVisionStreamUsesCachedOcrTextWhenAvailable() = runTest {
        val streamer = RecordingChatStreamer(flow = flowOf(StreamEvent.Complete(null)))
        val recognizer = FakeRecognizer("should-not-be-called")
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = recognizer,
            imageRenderer = ExplodingImageRenderer,
        )

        service.ask(
            nonVisionRequest(
                strokes = listOf(strokeItem()),
                cachedOcrText = "cached transcription",
            )
        ).toList()

        assertEquals(
            "Recognizer must not run when Note.ocrText is already populated.",
            0,
            recognizer.calls,
        )
        assertTrue(streamer.lastMessages.single().content.contains("cached transcription"))
    }

    @Test
    fun errorFromUpstreamSurfacesAsAiChunkError() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(StreamEvent.Error("boom")),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val chunks = service.ask(visionRequest(strokes = listOf(strokeItem()))).toList()

        assertEquals(1, chunks.size)
        assertEquals(AiChunk.Error("boom"), chunks[0])
    }

    @Test
    fun cancellationPropagatesToUpstreamFlow() = runTest {
        val onCompletion = CompletableDeferred<Throwable?>()
        val firstChunk = CompletableDeferred<AiChunk>()
        // Flow that suspends until cancelled after the first delta — its
        // `onCompletion` is the signal we assert on.
        val neverEnding: Flow<StreamEvent> = flow<StreamEvent> {
            emit(StreamEvent.Delta("partial"))
            awaitCancellation()
        }.onCompletion { cause -> onCompletion.complete(cause) }

        val service = NoteAiService(
            chatStreamer = RecordingChatStreamer(flow = neverEnding),
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val job = launch {
            service.ask(visionRequest(strokes = listOf(strokeItem())))
                .collect { chunk ->
                    if (!firstChunk.isCompleted) firstChunk.complete(chunk)
                }
        }

        val first = firstChunk.await()
        assertTrue("expected a delta first; got $first", first is AiChunk.Delta)

        job.cancelAndJoin()

        val cause = onCompletion.await()
        assertNotNull("upstream flow should observe a cancellation cause", cause)
    }

    @Test
    fun editModeEmitsEditPreviewWithParsedOps() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```edit-ops\n"),
                StreamEvent.Delta("{ \"schema\": 1, \"summary\": \"deleted one\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"delete\", \"ids\": [\"s_001\"] } ] }"),
                StreamEvent.Delta("\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val item = strokeItem()
        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = listOf(item),
                selection = null,
                userPrompt = "Delete it",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                layers = emptyList(),
            )
        ).toList()

        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        assertEquals("deleted one", preview.doc.summary)
        assertEquals(1, preview.doc.ops.size)
        // idMap exposes the on-disk uuid behind the short id the model used.
        assertEquals(item.id, preview.idMap["s_001"])
        // The wire body includes the vector JSON inline as a fenced block.
        val sent = streamer.lastMessages.single().content
        assertTrue("EDIT body should include the vector JSON", sent.contains("\"items\""))
        assertTrue("EDIT body should reference s_001", sent.contains("s_001"))
    }

    @Test
    fun editModeMalformedReplyEmitsError() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("not even close to JSON"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = listOf(strokeItem()),
                selection = null,
                userPrompt = "Do something",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
            )
        ).toList()

        // No EditPreview was emitted; an Error chunk was produced instead.
        assertTrue(chunks.none { it is AiChunk.EditPreview })
        assertTrue(chunks.any { it is AiChunk.Error })
    }

    @Test
    fun generateModeEmitsEditPreviewWithAddOpsAndStyleRefsInSystem() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"gear\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"add_path\", \"closed\": true, "),
                StreamEvent.Delta("\"anchors\": [ [0,0], [10,0], [10,10] ] } ] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = ExplodingImageRenderer, // generation must not rasterize
        )

        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = emptyList(), // blank artboard
                selection = null,
                userPrompt = "a settings gear",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                isIcon = true,
                generate = true,
                styleReferences = listOf("{\"schema\":1,\"items\":[]}"),
                // Isolate the from-scratch generation contract: no self-refine
                // second turn, so the ExplodingImageRenderer proves the *first*
                // turn never rasterizes. (Self-refine has dedicated tests.)
                selfRefineIterations = 0,
            )
        ).toList()

        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        assertEquals("gear", preview.doc.summary)
        assertTrue(preview.doc.ops.single() is EditOp.AddPath)
        // No existing-item id space for a from-scratch generation.
        assertTrue(preview.idMap.isEmpty())
        // The style reference rides in the system message, not the user turn.
        val sys = streamer.lastChat!!.systemMessage
        assertTrue("system message should embed the reference", sys.contains("Reference 1:"))
        assertTrue(sys.contains("{\"schema\":1,\"items\":[]}"))
        // The user turn names the artboard, plain text (no image).
        val sent = streamer.lastMessages.single()
        assertEquals("text", sent.contentType)
        assertTrue(sent.content.contains("a settings gear"))
    }

    @Test
    fun refineModeRastersSketchAndUsesRefineSystemMessage() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"clean\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"add_path\", \"closed\": false, "),
                StreamEvent.Delta("\"anchors\": [ [0,0], [10,10] ] } ] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(8) { 7 }),
        )
        val sketch = strokeItem()
        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = listOf(sketch),
                selection = listOf(sketch),
                userPrompt = "make the corners sharper",
                modelId = "gpt-4o", // vision-capable
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                isIcon = true,
                generate = true,
                refine = true,
            )
        ).toList()

        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        assertTrue(preview.doc.ops.single() is EditOp.AddPath)
        // First turn: the refine system message + the sketch raster as a
        // multimodal image. (The reused streamer flow makes the self-refine
        // second turn return the same add_path, so the staged doc is unchanged.)
        assertEquals(EditOpsParser.ICON_REFINE_SYSTEM_MESSAGE, streamer.chats.first().systemMessage)
        val sent = streamer.messageLists.first().single()
        assertEquals("multimodal", sent.contentType)
        assertTrue(sent.metadata!!.contains("data:image/png;base64,"))
        assertTrue(sent.content.contains("make the corners sharper"))
        // A vision refine always runs one self-refine critique turn.
        assertEquals(2, streamer.callCount)
        assertEquals(EditOpsParser.ICON_CRITIQUE_REFINE_SYSTEM_MESSAGE, streamer.chats[1].systemMessage)
    }

    @Test
    fun refineFallsBackToTextWhenModelHasNoVision() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"\", \"ops\": [] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = ExplodingImageRenderer, // must not rasterize without vision
        )
        val sketch = strokeItem()
        service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = listOf(sketch),
                selection = listOf(sketch),
                userPrompt = "",
                modelId = "gpt-3.5-turbo", // no vision
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                isIcon = true,
                generate = true,
                refine = true,
            )
        ).toList()

        val sent = streamer.lastMessages.single()
        assertEquals("text", sent.contentType)
        assertEquals(EditOpsParser.ICON_REFINE_SYSTEM_MESSAGE, streamer.lastChat!!.systemMessage)
        // Non-vision models can't see the rendered candidate, so self-refine
        // is skipped — exactly one turn.
        assertEquals(1, streamer.callCount)
    }

    @Test
    fun selfRefineSecondTurnSendsCandidateAndAdoptsCorrection() = runTest {
        // Turn 1: a rough first draft. Turn 2 (critique): a corrected doc the
        // service should adopt over the first draft.
        val streamer = RecordingChatStreamer.ofTurns(
            flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"draft\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"add_path\", \"closed\": true, "),
                StreamEvent.Delta("\"anchors\": [ [0,0], [10,0], [10,10] ] } ] }\n```"),
                StreamEvent.Complete(null),
            ),
            flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"refined\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"add_shape\", \"shape\": "),
                StreamEvent.Delta("{ \"type\": \"ellipse\", \"cx\": 5, \"cy\": 5, \"r\": 4 } } ] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(8) { 3 }),
        )
        val sketch = strokeItem()
        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = listOf(sketch),
                selection = listOf(sketch),
                userPrompt = "tidy it",
                modelId = "gpt-4o", // vision
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                isIcon = true,
                generate = true,
                refine = true,
            )
        ).toList()

        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        // The corrected (second-turn) doc was adopted.
        assertEquals("refined", preview.doc.summary)
        assertTrue(preview.doc.ops.single() is EditOp.AddShape)
        // The critique turn carried the prior assistant reply + a rendered image.
        assertEquals(2, streamer.callCount)
        val critiqueMsgs = streamer.messageLists[1]
        assertEquals(2, critiqueMsgs.size)
        assertEquals(MessageRole.ASSISTANT.value, critiqueMsgs[0].role)
        assertTrue(critiqueMsgs[0].content.contains("draft"))
        assertEquals("multimodal", critiqueMsgs[1].contentType)
        // Refine sends two images (the sketch + the rendered candidate).
        assertEquals(2, countOccurrences(critiqueMsgs[1].metadata!!, "data:image/png;base64,"))
    }

    @Test
    fun selfRefineEmptyCritiqueKeepsFirstDoc() = runTest {
        // Turn 2 returns an empty ops array → the model judged the render good,
        // so the first-turn doc is kept unchanged.
        val streamer = RecordingChatStreamer.ofTurns(
            flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"draft\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"add_path\", \"closed\": true, "),
                StreamEvent.Delta("\"anchors\": [ [0,0], [10,0], [10,10] ] } ] }\n```"),
                StreamEvent.Complete(null),
            ),
            flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"good\", \"ops\": [] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(8) { 9 }),
        )
        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = emptyList(),
                selection = null,
                userPrompt = "a gear",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                isIcon = true,
                generate = true,
            )
        ).toList()

        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        assertEquals("draft", preview.doc.summary)
        assertTrue(preview.doc.ops.single() is EditOp.AddPath)
        assertEquals(2, streamer.callCount)
    }


    @Test
    fun nonIconGenerateSkipsIconSelfRefineCritique() = runTest {
        val streamer = RecordingChatStreamer.ofTurns(
            flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"tutorial geometry\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"add_path\", \"closed\": false, "),
                StreamEvent.Delta("\"anchors\": [ [0,0], [10,10] ] } ] }\n```"),
                StreamEvent.Complete(null),
            ),
            flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"should not run\", \"ops\": [] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(8) { 4 }),
        )

        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = emptyList(),
                selection = null,
                userPrompt = "draw with me",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                isIcon = false,
                generate = true,
            )
        ).toList()

        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        assertEquals("tutorial geometry", preview.doc.summary)
        assertEquals(1, streamer.callCount)
        assertEquals(EditOpsParser.ICON_GENERATE_SYSTEM_MESSAGE, streamer.chats.single().systemMessage)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    @Test
    fun editModePreflightWarnsWhenCanvasJsonOmitsItems() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"ok\", \"ops\": [] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )
        val strokes = largeStrokeSet(900)

        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = strokes,
                selection = null,
                userPrompt = "clean up",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                confirmLargeScope = true,
            )
        ).toList()

        val preflight = chunks.filterIsInstance<AiChunk.Preflight>().single().result
        assertEquals(strokes.size, preflight.itemCount)
        assertTrue(preflight.jsonByteSize <= VectorCanvasJson.MAX_JSON_BYTES)
        assertTrue("expected omitted item warning", preflight.droppedItemIds.isNotEmpty())
        assertTrue(preflight.describe().contains("items omitted from editable JSON"))
        assertTrue("request should still dispatch after explicit confirmation", streamer.lastMessages.isNotEmpty())
    }

    @Test
    fun editModeBlocksExtremeScopeWithoutConfirmation() = runTest {
        val streamer = RecordingChatStreamer(flow = flowOf(StreamEvent.Complete(null)))
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = largeStrokeSet(900),
                selection = null,
                userPrompt = "clean up",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
            )
        ).toList()

        assertTrue(chunks.any { it is AiChunk.Preflight })
        assertEquals(NoteAiService.LARGE_SCOPE_CONFIRMATION_MESSAGE, chunks.filterIsInstance<AiChunk.Error>().single().message)
        assertTrue("blocked requests must not dispatch upstream", streamer.lastMessages.isEmpty())
    }

    @Test
    fun jsonDrivenVisionModesRasterizeOnlyUnlockedSerializedScope() = runTest {
        val lockedLayer = NoteLayer(
            id = "L_LOCK", noteId = "n", name = "Locked",
            opacityPercent = 100, visible = true, locked = true, ordinal = 0,
        )
        val openLayer = lockedLayer.copy(id = "L_OPEN", name = "Ink", locked = false, ordinal = 1)
        val locked = strokeItem().copy(layerId = lockedLayer.id)
        val unlocked = strokeItem().copy(layerId = openLayer.id)
        val unlayered = strokeItem()

        val modesAndReplies = listOf(
            AskMode.EDIT to "```edit-ops\n{ \"schema\": 1, \"summary\": \"none\", \"ops\": [] }\n```",
            AskMode.RESTYLE to "```edit-ops\n{ \"schema\": 1, \"summary\": \"none\", \"ops\": [] }\n```",
            AskMode.CRITIQUE to "{ \"summary\": \"ok\", \"suggestions\": [ { \"title\": \"Keep it\", \"why\": \"Balanced.\" } ] }",
            AskMode.SUGGEST_PALETTE to "{ \"swatches\": [\"#111111\", \"#222222\", \"#333333\"] }",
            AskMode.SUGGEST_METADATA to "{ \"title\": \"Ink\", \"tags\": [\"ink\"], \"description\": \"Unlocked ink.\" }",
        )

        for ((mode, reply) in modesAndReplies) {
            val renderer = RecordingImageRenderer(ByteArray(4))
            val streamer = RecordingChatStreamer(
                flow = flowOf(StreamEvent.Delta(reply), StreamEvent.Complete(null)),
            )
            val service = NoteAiService(
                chatStreamer = streamer,
                ocr = FakeRecognizer("unused"),
                imageRenderer = renderer,
            )

            service.ask(
                visionRequest(strokes = listOf(locked, unlocked, unlayered)).copy(
                    mode = mode,
                    layers = listOf(lockedLayer, openLayer),
                )
            ).toList()

            assertEquals("$mode raster scope", listOf(unlocked.id, unlayered.id), renderer.lastItems.map { it.id })
            val body = streamer.lastMessages.single().content
            assertTrue("$mode JSON should include editable item ids", body.contains("s_001"))
            assertEquals("$mode JSON should omit locked layer", false, body.contains("Locked"))
        }
    }

    // ---- I4 / N1: DESIGN_BRUSH mode ----

    @Test
    fun designBrushModeEmitsValidatedBrushDesign() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```json\n{ \"schema\": 1, \"brush\": { "),
                StreamEvent.Delta("\"name\": \"Inky Pen\", \"tool\": \"pen\", "),
                StreamEvent.Delta("\"color\": \"#101820\", \"width\": 5, \"taperEnd\": 0.3 } }\n```"),
                StreamEvent.Complete(Usage(promptTokens = 3, completionTokens = 4, totalTokens = 7)),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = ExplodingImageRenderer, // brush design must not rasterize
        )

        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = emptyList(),
                selection = null,
                userPrompt = "an inky pen with taper",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.DESIGN_BRUSH,
            )
        ).toList()

        val design = chunks.filterIsInstance<AiChunk.BrushDesign>().single()
        assertEquals("Inky Pen", design.spec.name)
        assertEquals("pen", design.spec.tool)
        assertEquals(0xFF101820.toInt(), design.spec.colorArgb)
        assertEquals(5f, design.spec.baseWidthPx, 0f)
        assertEquals(0.3f, design.spec.taperEnd, 1e-4f)
        assertEquals(7, design.usage?.totalTokens)
        // The designer uses its own system message and a plain text turn — no raster.
        assertEquals(NoteAiService.DESIGN_BRUSH_SYSTEM_MESSAGE, streamer.lastChat!!.systemMessage)
        val sent = streamer.lastMessages.single()
        assertEquals("text", sent.contentType)
        assertTrue(sent.content.contains("an inky pen with taper"))
    }

    // NOTE: the malformed-reply *service* path (parse failure → AiChunk.Error)
    // is intentionally not unit-tested here — it routes through `android.util.Log`
    // which is unmocked on the JVM host (the same documented limitation as
    // `editModeMalformedReplyEmitsError`). The parse-failure behaviour itself is
    // covered headlessly by `BrushSpecParserTest.emptyOrJsonlessReplyFails`.

    // ---- Phase 9: SUGGEST_METADATA mode ----

    @Test
    fun metadataModeEmitsValidatedMetadataResult() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```json\n{ \"schema\": 1, "),
                StreamEvent.Delta("\"title\": \"Settings gear\", "),
                StreamEvent.Delta("\"tags\": [\"settings\", \"gear\"], "),
                StreamEvent.Delta("\"description\": \"A grey gear icon.\" }\n```"),
                StreamEvent.Complete(Usage(promptTokens = 2, completionTokens = 3, totalTokens = 5)),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer("unused"),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val chunks = service.ask(
            visionRequest(strokes = listOf(strokeItem())).copy(mode = AskMode.SUGGEST_METADATA)
        ).toList()

        val result = chunks.filterIsInstance<AiChunk.MetadataResult>().single()
        assertEquals("Settings gear", result.suggestion.title)
        assertEquals(listOf("settings", "gear"), result.suggestion.tags)
        assertEquals("A grey gear icon.", result.suggestion.description)
        assertEquals(5, result.usage?.totalTokens)
        // Uses the metadata system message + a vision turn (image attached).
        assertEquals(MetadataParser.SYSTEM_MESSAGE, streamer.lastChat!!.systemMessage)
        assertEquals("multimodal", streamer.lastMessages.single().contentType)
    }

    @Test
    fun metadataModeNonVisionInlinesOcrText() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```json\n{ \"title\": \"Shopping list\", \"tags\": [\"list\"] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer("milk eggs bread"),
            imageRenderer = ExplodingImageRenderer, // non-vision must not rasterize
        )

        val chunks = service.ask(
            nonVisionRequest(strokes = listOf(strokeItem()), cachedOcrText = null)
                .copy(mode = AskMode.SUGGEST_METADATA)
        ).toList()

        assertTrue(chunks.any { it is AiChunk.MetadataResult })
        val sent = streamer.lastMessages.single()
        assertEquals("text", sent.contentType)
        assertTrue("OCR text should be inlined: ${sent.content}", sent.content.contains("milk eggs bread"))
    }

    // ---- helpers ----

    @Test
    fun sceneModeUsesSceneSystemMessageAndCapsObjects() = runTest {
        // The model returns more objects than the SIMPLE cap allows; the service
        // must use the scene system message + scene prompt body and trim the
        // extras into `rejected` so the staged scene stays compact.
        val tooMany = buildString {
            append("```edit-ops\n{ \"schema\": 1, \"summary\": \"campsite\", \"ops\": [")
            for (i in 0 until 9) {
                if (i > 0) append(",")
                append("{ \"op\": \"add_shape\", \"group\": \"o$i\", \"shape\": ")
                append("{ \"type\": \"rect\", \"x0\": $i, \"y0\": 0, \"x1\": ${i + 1}, \"y1\": 1 } }")
            }
            append("] }\n```")
        }
        val streamer = RecordingChatStreamer(flow = flowOf(StreamEvent.Delta(tooMany), StreamEvent.Complete(null)))
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer("unused"),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )
        val request = visionRequest(strokes = listOf(strokeItem())).copy(
            userPrompt = "a small campsite at night",
            mode = AskMode.EDIT,
            generate = true,
            scene = true,
            sceneComplexity = SceneComplexity.SIMPLE,
        )
        val chunks = service.ask(request).toList()
        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        // SIMPLE caps at 6 objects; the other 3 are rejected, not applied.
        assertEquals(SceneComplexity.SIMPLE.maxObjects, preview.doc.ops.size)
        assertTrue(preview.doc.rejected.size >= 3)
        // Scene system message + scene prompt body were used.
        assertEquals(EditOpsParser.SCENE_GENERATE_SYSTEM_MESSAGE, streamer.lastChat?.systemMessage)
        assertTrue(streamer.lastMessages.single().content.contains("a small campsite at night"))
        assertTrue(streamer.lastMessages.single().content.contains("scene"))
    }

    private fun visionRequest(strokes: List<NoteItem>): AskRequest = AskRequest(
        note = sampleNote(),
        allItems = strokes,
        selection = null,
        userPrompt = VISION_PROMPT,
        // 4o is in the registry as supportsVision=true.
        modelId = "gpt-4o",
        baseUrl = "https://example.invalid/v1/",
        apiKey = "test-key",
    )

    private fun nonVisionRequest(
        strokes: List<NoteItem>,
        cachedOcrText: String?,
    ): AskRequest = AskRequest(
        note = sampleNote().copy(ocrText = cachedOcrText),
        allItems = strokes,
        selection = null,
        userPrompt = NON_VISION_PROMPT,
        // gpt-3.5-turbo is in the registry as supportsVision=false.
        modelId = "gpt-3.5-turbo",
        baseUrl = "https://example.invalid/v1/",
        apiKey = "test-key",
    )

    private fun sampleNote() = Note(
        id = UUID.randomUUID().toString(),
        title = "test",
        backgroundStyle = "plain",
        schemaVersion = 1,
        minX = 0f, minY = 0f, maxX = 100f, maxY = 100f,
        thumbnailPath = null,
        ocrText = null,
    )

    private fun strokeItem(): NoteItem = NoteItem(
        noteId = "n",
        zIndex = 0,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 2f,
        payload = StrokeCodec.encode(floatArrayOf(10f, 10f, 1f, 0f, 20f, 20f, 1f, 0f)),
    )

    private fun largeStrokeSet(count: Int): List<NoteItem> = (0 until count).map { idx ->
        val samples = FloatArray(160 * StrokeCodec.FLOATS_PER_SAMPLE) { sample ->
            ((idx * 17 + sample) % 1000).toFloat()
        }
        strokeItem().copy(zIndex = idx, payload = StrokeCodec.encode(samples))
    }

    companion object {
        private const val VISION_PROMPT = "What does this note say?"
        private const val NON_VISION_PROMPT = "Summarize this."
    }

    // ---- fakes ----

    private class RecordingChatStreamer private constructor(
        private val flows: List<Flow<StreamEvent>>,
    ) : ChatStreamer {
        /** Single reused flow (back-compat: every turn returns the same stream). */
        constructor(flow: Flow<StreamEvent>) : this(listOf(flow))

        /** Full per-turn history, in call order. */
        val chats = mutableListOf<Chat>()
        val messageLists = mutableListOf<List<Message>>()
        var callCount: Int = 0
            private set

        val lastChat: Chat? get() = chats.lastOrNull()
        val lastMessages: List<Message> get() = messageLists.lastOrNull() ?: emptyList()

        override fun sendMessageStream(
            baseUrl: String,
            apiKey: String,
            chat: Chat,
            messages: List<Message>,
            onRetryAttempt: ((Int) -> Unit)?,
            tools: List<ToolDefinition>?,
            extraImageOnLastUserTurn: ByteArray?,
            extraSystemSuffix: String?,
        ): Flow<StreamEvent> {
            chats += chat
            messageLists += messages
            // Turn i returns flows[i]; once exhausted, the last flow is reused.
            val f = flows[minOf(callCount, flows.size - 1)]
            callCount++
            return f
        }

        companion object {
            /** A streamer that returns a distinct flow for each successive turn. */
            fun ofTurns(vararg turnFlows: Flow<StreamEvent>) =
                RecordingChatStreamer(turnFlows.toList())
        }
    }

    private class FakeRecognizer(private val text: String) : HandwritingRecognizer {
        var calls: Int = 0
            private set

        override suspend fun recognize(strokes: List<NoteItem>, locale: String): OcrResult {
            calls++
            return OcrResult(text = text, confidence = if (text.isEmpty()) 0f else 1f, perWord = emptyList())
        }
    }

    private class RecordingImageRenderer(private val bytes: ByteArray) : NoteImageRenderer {
        var lastItems: List<NoteItem> = emptyList()
            private set

        override fun renderToPng(
            items: List<NoteItem>,
            backgroundStyle: String,
            maxEdgePx: Int,
            filesDir: File?,
        ): ByteArray {
            lastItems = items
            return bytes
        }
    }

    private class FakeImageRenderer(private val bytes: ByteArray) : NoteImageRenderer {
        var lastFilesDir: File? = null
            private set

        override fun renderToPng(
            items: List<NoteItem>,
            backgroundStyle: String,
            maxEdgePx: Int,
            filesDir: java.io.File?,
        ): ByteArray {
            lastFilesDir = filesDir
            return bytes
        }
    }

    private object ExplodingImageRenderer : NoteImageRenderer {
        override fun renderToPng(
            items: List<NoteItem>,
            backgroundStyle: String,
            maxEdgePx: Int,
            filesDir: java.io.File?,
        ): ByteArray = error("image renderer must not be called on the non-vision branch")
    }
}
