package xyz.mederi.plan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.UUID

/** One named observation, compared by Core to the value approved for this case. */
@Serializable
data class AcceptanceCase(
    val id: String,
    val objective: String,
    val setup: String,
    val input: String,
    val steps: String,
    val observationSource: String,
    val expected: String,
    val detects: String,
    val manual: Boolean = false
) {
    internal fun isValid() = id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) &&
        expected.trim().uppercase() !in setOf("PASS", "ALL PASS", "BUILD SUCCESSFUL", "CHECK_PASS", "BEHAVIOR_CHECK_PASS") &&
        listOf(objective, setup, input, steps, observationSource, expected, detects).all { it.isNotBlank() && it.length <= 4000 }
}

@Serializable
enum class AcceptanceVerdict { PASS, FAIL, INSUFFICIENT_EVIDENCE, BLOCKED }

@Serializable
data class AcceptanceObservation(
    val caseId: String,
    val expected: String,
    val actual: String?,
    val verdict: AcceptanceVerdict
)

@Serializable
data class AcceptanceRun(
    val id: String,
    val timestamp: String,
    val exitCode: Int?,
    val detail: String,
    val observations: List<AcceptanceObservation>,
    val output: String = "",
    val confirmedBy: String? = null,
    val sourceDigests: Map<String, String> = emptyMap(),
    val executionNotes: String = ""
) {
    val passed: Boolean get() = observations.isNotEmpty() && observations.all { it.verdict == AcceptanceVerdict.PASS }

    companion object {
        internal fun manual(case: AcceptanceCase, observed: String, evidence: String, confirmedBy: String): AcceptanceRun {
            val verdict = if (observed == case.expected) AcceptanceVerdict.PASS else AcceptanceVerdict.FAIL
            return AcceptanceRun(UUID.randomUUID().toString(), Instant.now().toString(), null,
                "Operator attestation", listOf(AcceptanceObservation(case.id, case.expected, observed, verdict)),
                evidence, confirmedBy)
        }

        /** Stdout protocol: one JSON object, with a scalar observation under each approved case ID. */
        internal fun evaluate(
            cases: List<AcceptanceCase>,
            output: String,
            exitCode: Int?,
            blocked: String? = null
        ): AcceptanceRun {
            val values = if (blocked == null) {
                runCatching { Json.parseToJsonElement(output).jsonObject }.getOrNull()
            } else null
            val observations = cases.map { case ->
                val actual = (values?.get(case.id) as? JsonPrimitive)
                    ?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content?.takeIf { it.length <= 4000 }
                val verdict = when {
                    blocked != null -> AcceptanceVerdict.BLOCKED
                    exitCode != 0 -> AcceptanceVerdict.FAIL
                    actual == null -> AcceptanceVerdict.INSUFFICIENT_EVIDENCE
                    actual == case.expected -> AcceptanceVerdict.PASS
                    else -> AcceptanceVerdict.FAIL
                }
                AcceptanceObservation(case.id, case.expected, actual, verdict)
            }
            val detail = when {
                blocked != null -> blocked
                exitCode != 0 -> "Collector exited with code $exitCode."
                values == null -> "Collector did not return a JSON observation object."
                else -> "Core compared each observed scalar with its approved expected value."
            }
            return AcceptanceRun(UUID.randomUUID().toString(), Instant.now().toString(), exitCode, detail,
                observations, output.take(16000))
        }
    }
}
