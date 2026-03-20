package com.aichat.sandbox.data.tools

import com.aichat.sandbox.data.model.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry that maps tool names to their executor implementations.
 * Provides tool definitions to send to the API and executes tool calls.
 */
@Singleton
class ToolRegistry @Inject constructor() {
    private val executors = mutableMapOf<String, ToolExecutor>()

    init {
        // Register built-in tools
        register(CurrentDateTimeTool())
        register(CalculatorTool())
    }

    fun register(executor: ToolExecutor) {
        executors[executor.name] = executor
    }

    fun getExecutor(name: String): ToolExecutor? = executors[name]

    fun getToolDefinitions(): List<ToolDefinition> =
        executors.values.map { it.definition }

    fun hasTools(): Boolean = executors.isNotEmpty()

    suspend fun executeTool(name: String, arguments: String): String {
        val executor = executors[name]
            ?: return "Error: Unknown tool '$name'"
        return try {
            executor.execute(arguments)
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.message}"
        }
    }
}
