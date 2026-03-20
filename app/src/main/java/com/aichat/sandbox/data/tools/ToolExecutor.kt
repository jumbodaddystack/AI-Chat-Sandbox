package com.aichat.sandbox.data.tools

import com.aichat.sandbox.data.model.ToolDefinition

/**
 * Interface for tool implementations that can be called by the AI.
 */
interface ToolExecutor {
    /** Unique tool name matching the function name in the API. */
    val name: String

    /** Tool definition sent to the API. */
    val definition: ToolDefinition

    /** Execute the tool with the given JSON arguments string. Returns a result string. */
    suspend fun execute(arguments: String): String
}
