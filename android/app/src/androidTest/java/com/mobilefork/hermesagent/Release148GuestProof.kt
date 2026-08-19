package com.mobilefork.hermesagent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Guest-side proof for the 148 Gemma/Bonsai smokes.
 *
 * Assistant prose and the echoed `sandbox_command` field are not evidence: the
 * prompt already contains "Alpine 3.21" and the requested printf line. Only the
 * tool-result `output` field and an independent `cat` of the marker file count.
 */
internal object Release148GuestProof {
    const val GENERIC_SUCCESS = "Command executed successfully."

    fun parseToolResults(rawResults: List<String>): List<JSONObject> {
        return rawResults.mapNotNull { raw -> runCatching { JSONObject(raw) }.getOrNull() }
    }

    fun stdoutOf(result: JSONObject): String = result.optString("output")

    fun sandboxCommandOf(result: JSONObject): String = result.optString("sandbox_command")

    fun logProcessedToolResults(label: String, toolNames: List<String>, rawResults: List<String>) {
        println("RELEASE148[$label] tool_names=$toolNames")
        val parsed = parseToolResults(rawResults)
        if (parsed.isEmpty()) {
            println("RELEASE148[$label] raw_tool_results=$rawResults")
        }
        parsed.forEachIndexed { index, json ->
            println(
                "RELEASE148[$label] tool_result[$index] " +
                    "sandbox_execution_mode=${json.optString("sandbox_execution_mode")} " +
                    "sandbox_command=${sandboxCommandOf(json)} " +
                    "tool_result_output=${stdoutOf(json)}",
            )
        }
    }

    fun logProofCat(label: String, proofPath: String, proof: JSONObject) {
        println(
            "RELEASE148[$label] proof_cat path=$proofPath " +
                "exit_code=${proof.optInt("exit_code", -1)} " +
                "sandbox_execution_mode=${proof.optString("sandbox_execution_mode")} " +
                "output=${stdoutOf(proof)}",
        )
    }

    fun assertMarkerFile(
        label: String,
        proofPath: String,
        proof: JSONObject,
        marker: String,
        toolNames: List<String>,
        rawResults: List<String>,
    ) {
        val parsed = parseToolResults(rawResults)
        val proofStdout = stdoutOf(proof).trim()
        logProcessedToolResults(label, toolNames, rawResults)
        logProofCat(label, proofPath, proof)

        assertEquals(
            "$label proof cat must run in the Alpine guest: $proof",
            "proot_distro_qemu",
            proof.optString("sandbox_execution_mode"),
        )
        assertEquals(
            "$label proof cat exit_code: $proof",
            0,
            proof.optInt("exit_code", -1),
        )
        assertFalse(
            "$label marker file $proofPath is missing or empty (generic success wrapper). " +
                "proof=$proof names=$toolNames parsed=$parsed",
            proofStdout.isEmpty() || proofStdout.equals(GENERIC_SUCCESS, ignoreCase = true),
        )
        assertTrue(
            "$label marker file $proofPath must contain $marker. " +
                "Assistant prose and sandbox_command echo do not count. " +
                "proof_output=$proofStdout names=$toolNames " +
                "tool_stdout=${parsed.joinToString(" | ") { stdoutOf(it) }} " +
                "sandbox_commands=${parsed.joinToString(" | ") { sandboxCommandOf(it) }}",
            proofStdout.contains(marker),
        )
    }
}
