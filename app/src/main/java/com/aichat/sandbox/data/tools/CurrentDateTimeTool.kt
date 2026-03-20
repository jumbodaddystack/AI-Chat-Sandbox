package com.aichat.sandbox.data.tools

import com.aichat.sandbox.data.model.FunctionDefinition
import com.aichat.sandbox.data.model.ToolDefinition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CurrentDateTimeTool : ToolExecutor {
    override val name = "get_current_datetime"

    override val definition = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = "Get the current date and time. Optionally specify a timezone.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "timezone" to mapOf(
                        "type" to "string",
                        "description" to "IANA timezone (e.g. 'America/New_York'). Defaults to device timezone."
                    )
                ),
                "required" to emptyList<String>()
            )
        )
    )

    override suspend fun execute(arguments: String): String {
        val tz = try {
            val parsed = com.google.gson.JsonParser.parseString(arguments).asJsonObject
            val tzStr = parsed.get("timezone")?.asString
            if (tzStr != null) TimeZone.getTimeZone(tzStr) else TimeZone.getDefault()
        } catch (_: Exception) {
            TimeZone.getDefault()
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z (EEEE)", Locale.US)
        sdf.timeZone = tz
        return sdf.format(Date())
    }
}
