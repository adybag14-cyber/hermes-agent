# Cognitive review in Hermes

Hermes remains responsible for model inference, conversation history, authorization, and tool execution. An optional reviewer inspects the actual candidate immediately before dispatch. It must not generate a replacement primary response or execute tools.

## Programmatic configuration

Pass a reviewer to the existing agent constructor:

```python
from run_agent import AIAgent

agent = AIAgent(
    # Configure the usual Hermes provider/model parameters here.
    cognitive_gate=reviewer,
    cognitive_gate_max_attempts=3,
)
```

The reviewer implements `evaluate_turn(candidate, *, context)` and returns a decision with an `action` and optional `reason`/`critic`. The candidate contains content, tool calls, and the finish reason. The context contains a copied conversation, task ID, and session ID. Reviewing these copies cannot alter Hermes's live transcript or pending tool calls.

| Action | Effect |
| --- | --- |
| `accept` | Continue through the normal Hermes authorization and executor. |
| `correct`, `retry`, `retrieve_evidence` | Record synthetic blocked results for the proposed calls and return feedback to the next model turn. |
| `escalate`, an unknown/malformed decision, or a reviewer exception | Stop the turn without dispatching the proposed tools. |

The rejection budget is per task and resets on acceptance or escalation. The normal Hermes iteration budget still applies. Evidence requests are feedback for regeneration: the adapter does not autonomously perform an evidence search. A subsequent proposed search must itself pass review.

Without a reviewer, the normal Hermes flow remains available. Whole tool batches must still contain JSON-object argument envelopes with finite numeric values before either executor lane can run. Acceptance never bypasses existing authorization, plugin policy, or guardrails.

## Critic-only model adapter

`odyn_ai.cognition.HermesDualModelGate` adapts a `DualModelEngine` to this contract by calling `review_candidate`. This path calls the critic endpoint only; Hermes has already generated the primary candidate. The cognition modules are shipped with the Hermes Python package. Their internal namespace does not change the application's name or interface.

The critic's `valid` field must be a JSON boolean and `confidence` a number between zero and one. Strings such as `"false"`, numeric booleans, non-finite confidence values, and malformed issue/evidence arrays cannot authorize tools.

For callers using `CognitiveEngine.run_and_execute` directly, the complete explicit tool plan is validated before inference and included in the review context. The primary and critic backends receive separate context snapshots. A malformed later call blocks the entire batch before any tool runs, and dispatch receives the reviewed plan rather than a caller's stale context entry.

`HermesToolExecutor` accepts a dispatcher callback, which is responsible for its authorization. Use the native agent gate when the full `AIAgent` plugin and guardrail chain is required.

An OpenAI-compatible llama.cpp endpoint can be wrapped with `LlamaCppInferenceBackend(LlamaCppEndpoint(...))`. Hermes and the device runtime remain responsible for starting servers, selecting models, and allocating memory. The boundary tests use controlled model/dispatcher doubles; they do not establish physical-device GGUF performance or model quality.
