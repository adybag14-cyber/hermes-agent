from __future__ import annotations

import asyncio
import importlib
import sys
import types


_TARGET_MODULES = [
    "environments.agentic_opd_env",
    "environments.hermes_base_env",
    "environments.agent_loop",
    "environments.tool_context",
    "pydantic",
    "atroposlib",
    "atroposlib.envs",
    "atroposlib.envs.base",
    "atroposlib.envs.server_handling",
    "atroposlib.envs.server_handling.server_manager",
    "atroposlib.type_definitions",
]
_MISSING = object()


def _load_agentic_opd_module():
    originals = {name: sys.modules.get(name, _MISSING) for name in _TARGET_MODULES}
    for name in _TARGET_MODULES:
        sys.modules.pop(name, None)

    atroposlib = types.ModuleType("atroposlib")
    atroposlib_envs = types.ModuleType("atroposlib.envs")
    atroposlib_base = types.ModuleType("atroposlib.envs.base")

    class FakeBaseEnv:
        def __init__(self, config=None, server_configs=None, slurm=False, testing=False):
            self.config = config
            self.server = types.SimpleNamespace(servers=server_configs or [])

    class FakeBaseEnvConfig:
        def __init__(self, **kwargs):
            self.__dict__.update(kwargs)

    atroposlib_base.BaseEnv = FakeBaseEnv
    atroposlib_base.BaseEnvConfig = FakeBaseEnvConfig
    atroposlib_base.ScoredDataGroup = dict
    atroposlib_base.ScoredDataItem = dict

    atroposlib_server_handling = types.ModuleType("atroposlib.envs.server_handling")
    atroposlib_server = types.ModuleType(
        "atroposlib.envs.server_handling.server_manager"
    )

    class APIServerConfig:
        def __init__(self, **kwargs):
            self.__dict__.update(kwargs)

    atroposlib_server.APIServerConfig = APIServerConfig

    atroposlib_types = types.ModuleType("atroposlib.type_definitions")
    atroposlib_types.Item = dict

    hermes_base_env = types.ModuleType("environments.hermes_base_env")

    class HermesAgentBaseEnv(FakeBaseEnv):
        pass

    class HermesAgentEnvConfig(FakeBaseEnvConfig):
        pass

    hermes_base_env.HermesAgentBaseEnv = HermesAgentBaseEnv
    hermes_base_env.HermesAgentEnvConfig = HermesAgentEnvConfig

    agent_loop = types.ModuleType("environments.agent_loop")
    agent_loop.AgentResult = dict
    agent_loop.HermesAgentLoop = object

    tool_context = types.ModuleType("environments.tool_context")
    tool_context.ToolContext = object

    pydantic = types.ModuleType("pydantic")

    def field_stub(*args, **kwargs):
        return None

    pydantic.Field = field_stub

    sys.modules["atroposlib"] = atroposlib
    sys.modules["atroposlib.envs"] = atroposlib_envs
    sys.modules["atroposlib.envs.base"] = atroposlib_base
    sys.modules["atroposlib.envs.server_handling"] = atroposlib_server_handling
    sys.modules[
        "atroposlib.envs.server_handling.server_manager"
    ] = atroposlib_server
    sys.modules["atroposlib.type_definitions"] = atroposlib_types
    sys.modules["environments.hermes_base_env"] = hermes_base_env
    sys.modules["environments.agent_loop"] = agent_loop
    sys.modules["environments.tool_context"] = tool_context
    sys.modules["pydantic"] = pydantic

    try:
        return importlib.import_module("environments.agentic_opd_env")
    finally:
        for name, original in originals.items():
            if original is _MISSING:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = original


def test_opd_for_sequence_prepares_full_tokens_once_and_threads_it_through(
    monkeypatch,
) -> None:
    module = _load_agentic_opd_module()
    env = module.AgenticOPDEnv.__new__(module.AgenticOPDEnv)
    env.config = types.SimpleNamespace(
        distill_topk=2,
        hint_max_next_state_chars=100,
        prm_votes=1,
    )
    env._hints_extracted_buffer = []
    env._opd_turns_scored_buffer = []

    class FakeTokenizer:
        def apply_chat_template(
            self,
            conversation,
            tokenize=False,
            add_generation_prompt=False,
        ):
            parts = [f"{msg['role']}:{msg['content']}" for msg in conversation]
            if add_generation_prompt:
                parts.append("assistant:")
            return "|".join(parts)

        def __call__(self, text: str, add_special_tokens: bool = False) -> dict:
            return {"input_ids": [ord(char) for char in text]}

    class FakeServer:
        async def get_logprobs(self, input_ids, top_k, split):
            return {
                "prompt_topk_token_ids": [[101, 102], [201, 202]],
                "prompt_topk_logprobs": [[-0.1, -0.2], [-0.3, -0.4]],
            }

    prepared_marker = object()
    prepare_calls = []
    span_calls = []

    def fake_prepare(full_tokens):
        prepare_calls.append(list(full_tokens))
        return prepared_marker

    monkeypatch.setattr(module, "prepare_token_span_full", fake_prepare)

    env.tokenizer = FakeTokenizer()
    env.server = FakeServer()
    env._extract_turn_pairs = lambda messages: [
        {
            "context_messages": messages[:2],
            "assistant_text": "ab",
            "next_state_text": "tool result",
            "next_state_role": "tool",
        }
    ]

    async def fake_extract_hint(*args, **kwargs):
        return "Use the tool result."

    def fake_find_token_span(full_tokens, sub_tokens, prepared_full_tokens=None):
        span_calls.append(
            {
                "full_tokens": list(full_tokens),
                "sub_tokens": list(sub_tokens),
                "prepared_full_tokens": prepared_full_tokens,
            }
        )
        return 1

    env._extract_hint = fake_extract_hint
    env._find_token_span = fake_find_token_span

    group = {
        "messages": [
            [
                {"role": "system", "content": "You are helpful."},
                {"role": "user", "content": "Write fizzbuzz."},
                {"role": "assistant", "content": "ab"},
                {"role": "tool", "content": "tool result"},
            ]
        ],
        "tokens": [[0, 97, 98, 0]],
    }

    asyncio.run(module.AgenticOPDEnv._apply_opd_pipeline(env, group))

    distill_ids = group["distill_token_ids"][0]
    distill_lps = group["distill_logprobs"][0]

    assert prepare_calls == [[0, 97, 98, 0]]
    assert len(span_calls) == 1
    assert span_calls[0]["sub_tokens"] == [97, 98]
    assert span_calls[0]["prepared_full_tokens"] is prepared_marker
    assert distill_ids[1] == [101, 102]
    assert distill_ids[2] == [201, 202]
    assert distill_lps[1] == [-0.1, -0.2]
    assert distill_lps[2] == [-0.3, -0.4]
    assert env._hints_extracted_buffer == [1]
    assert env._opd_turns_scored_buffer == [1]
