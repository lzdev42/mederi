package xyz.mederi.tools.background

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import xyz.mederi.tools.ToolName

class BackgroundTaskTools(
    private val backgroundTaskManager: BackgroundTaskManager,
    private val sessionId: String,
) {
    @Serializable
    data class SetTimerArgs(
        @LLMDescription("Duration in seconds until the timer fires.")
        @SerialName("duration_seconds")
        val durationSeconds: Int = 0,
        @LLMDescription("A description of what this timer is for. You will receive this text when the timer fires, so write it as a reminder to yourself.")
        val description: String = "",
    )

    inner class SetTimerTool : SimpleTool<SetTimerArgs>(
        argsType = typeToken<SetTimerArgs>(),
        name = ToolName.SET_TIMER.value,
        description = "Set a timer. After duration_seconds, you receive a message with the description you wrote. Use for reminders, periodic checks, or waiting before retrying. You can continue working while the timer runs."
    ) {
        override suspend fun execute(args: SetTimerArgs): String {
            if (args.durationSeconds <= 0) return "Error: duration_seconds must be positive."
            val taskId = backgroundTaskManager.startTimer(sessionId, args.durationSeconds, args.description)
            return "Timer set: will fire in ${args.durationSeconds}s. Task ID: $taskId. Description: ${args.description}"
        }
    }
}
