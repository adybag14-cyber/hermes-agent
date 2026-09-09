"""Bound response buffering before the official SDK parses HTTP JSON or SSE."""
from __future__ import annotations

import httpx2

MAX_MESSAGE_BYTES = 1024 * 1024


class ResponseBudget:
    def __init__(self, *, event_stream: bool, maximum: int = MAX_MESSAGE_BYTES):
        self.event_stream, self.maximum = event_stream, maximum
        self.frame_bytes = self.line_bytes = 0
        self.previous_cr = False

    def feed(self, chunk: bytes) -> None:
        if not self.event_stream:
            self.frame_bytes += len(chunk)
            if self.frame_bytes > self.maximum:
                raise ValueError("MCP HTTP response exceeds the byte limit")
            return
        # SSE events may span multiple data lines. Only a blank line resets the
        # event budget; arbitrary chunk boundaries and LF/CRLF/CR are equivalent.
        for byte in chunk:
            self.frame_bytes += 1
            if self.frame_bytes > self.maximum:
                raise ValueError("MCP SSE event exceeds the byte limit")
            if byte in (10, 13):
                if not (byte == 10 and self.previous_cr):
                    if self.line_bytes == 0:
                        self.frame_bytes = 0
                    self.line_bytes = 0
                self.previous_cr = byte == 13
            else:
                self.previous_cr = False
                self.line_bytes += 1


class BoundedResponseStream(httpx2.AsyncByteStream):
    def __init__(self, stream, *, event_stream: bool, encoding_allowed: bool = True):
        self.stream = stream
        self.budget = ResponseBudget(event_stream=event_stream)
        self.encoding_allowed = encoding_allowed

    async def __aiter__(self):
        if not self.encoding_allowed:
            raise httpx2.StreamError("Compressed MCP responses are not accepted by the bounded Android client")
        async for chunk in self.stream:
            try:
                self.budget.feed(chunk)
            except ValueError as error:
                # The SDK converts StreamError into a failed request and then
                # unwinds its owned connection normally. An arbitrary ValueError
                # instead escapes its JSON reader's background task group.
                raise httpx2.StreamError(str(error)) from error
            yield chunk

    async def aclose(self):
        await self.stream.aclose()


async def bound_sdk_response(response):
    encoding = response.headers.get("content-encoding", "identity").lower().strip()
    event_stream = response.headers.get("content-type", "").split(";", 1)[0].strip().lower() == "text/event-stream"
    # Reject compression while the SDK is reading the body, before httpx can
    # decompress it, so the failure also follows the SDK's request-error path.
    response.stream = BoundedResponseStream(response.stream, event_stream=event_stream,
                                            encoding_allowed=encoding in {"", "identity"})
