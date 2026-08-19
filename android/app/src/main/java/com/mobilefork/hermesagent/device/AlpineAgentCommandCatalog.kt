package com.mobilefork.hermesagent.device

/**
 * Fifteen distinct Alpine guest commands used by the in-app agent path.
 *
 * Each entry is a real guest shell command (not a host `/system/bin` stand-in)
 * with a unique proof needle so dropped or host-routed tool calls fail tests.
 */
object AlpineAgentCommandCatalog {
    data class GuestCommand(
        val id: String,
        val command: String,
        val proofNeedle: String,
        val proofFile: String,
    )

    const val SANDBOX_NAME = "hermes-alpine"
    const val DISTRO_ID = "alpine-3-21"
    const val ALPINE_RELEASE_NEEDLE = "3.21"
    const val GUEST_PROMPT_PREFIX =
        "Inside the active Alpine 3.21 guest, perform this as one guest action: "

    val release148Commands: List<GuestCommand> = listOf(
        GuestCommand(
            id = "alpine-release",
            command = "cat /etc/alpine-release",
            proofNeedle = ALPINE_RELEASE_NEEDLE,
            proofFile = "/tmp/hermes-148-01-release",
        ),
        GuestCommand(
            id = "uname-s",
            command = "uname -s",
            proofNeedle = "Linux",
            proofFile = "/tmp/hermes-148-02-uname",
        ),
        GuestCommand(
            id = "id-u",
            command = "id -u",
            proofNeedle = "0",
            proofFile = "/tmp/hermes-148-03-id",
        ),
        GuestCommand(
            id = "printf-marker",
            command = "printf 'H148_04\\n'",
            proofNeedle = "H148_04",
            proofFile = "/tmp/hermes-148-04-printf",
        ),
        GuestCommand(
            id = "write-cat",
            command = "printf 'H148_05' > /tmp/hermes-148-05-write; cat /tmp/hermes-148-05-write",
            proofNeedle = "H148_05",
            proofFile = "/tmp/hermes-148-05-write",
        ),
        GuestCommand(
            id = "ls-release-file",
            command = "ls /etc/alpine-release",
            proofNeedle = "alpine-release",
            proofFile = "/tmp/hermes-148-06-ls",
        ),
        GuestCommand(
            id = "wc-release",
            command = "wc -c /etc/alpine-release",
            proofNeedle = "alpine-release",
            proofFile = "/tmp/hermes-148-07-wc",
        ),
        GuestCommand(
            id = "echo-marker",
            command = "echo H148_08",
            proofNeedle = "H148_08",
            proofFile = "/tmp/hermes-148-08-echo",
        ),
        GuestCommand(
            id = "os-release",
            command = "cat /etc/os-release",
            proofNeedle = "Alpine",
            proofFile = "/tmp/hermes-148-09-os",
        ),
        GuestCommand(
            id = "pwd",
            command = "pwd",
            proofNeedle = "/",
            proofFile = "/tmp/hermes-148-10-pwd",
        ),
        GuestCommand(
            id = "test-release",
            command = "test -f /etc/alpine-release && printf H148_11",
            proofNeedle = "H148_11",
            proofFile = "/tmp/hermes-148-11-test",
        ),
        GuestCommand(
            id = "busybox-echo",
            command = "busybox echo H148_12",
            proofNeedle = "H148_12",
            proofFile = "/tmp/hermes-148-12-busybox",
        ),
        GuestCommand(
            id = "mkdir-marker",
            command = "mkdir -p /tmp/h148 && printf H148_13 > /tmp/h148/m && cat /tmp/h148/m",
            proofNeedle = "H148_13",
            proofFile = "/tmp/h148/m",
        ),
        GuestCommand(
            id = "hostname",
            command = "hostname",
            proofNeedle = "",
            proofFile = "/tmp/hermes-148-14-hostname",
        ),
        GuestCommand(
            id = "date-year",
            command = "date +%Y",
            proofNeedle = "20",
            proofFile = "/tmp/hermes-148-15-date",
        ),
    )

    fun guestPrompt(command: String): String {
        return GUEST_PROMPT_PREFIX + command +
            ". Write the command output into the matching /tmp/hermes-148-* proof file as well."
    }

    fun wrappedGuestCommand(entry: GuestCommand): String {
        if (entry.proofFile.isBlank()) {
            return entry.command
        }
        // Do not tee onto a path the command already writes; that truncates the proof.
        if (entry.command.contains(entry.proofFile)) {
            return entry.command
        }
        return "( ${entry.command} ) | tee ${entry.proofFile}"
    }
}
