package com.aichat.sandbox.data.model

import com.google.gson.annotations.SerializedName

/**
 * OpenAI-compatible tool/function calling data models.
 */

data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>? = null
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String // JSON string of arguments
)

// Metadata stored in Message.metadata for tool call messages
data class ToolCallMetadata(
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id")
    val toolCallId: String? = null,
    @SerializedName("tool_name")
    val toolName: String? = null,
    @SerializedName("tool_result")
    val toolResult: String? = null
)
