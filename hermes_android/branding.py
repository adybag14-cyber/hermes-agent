"""Android presentation identity, without changing upstream CLI or user personas."""
from __future__ import annotations

from hermes_android.runtime_identity import is_embedded_android_runtime

ANDROID_APP_HELP_GUIDANCE = (
    "You run in Agent, an independently maintained Android app. "
    "For app help, distinguish this Android interface from upstream CLI features. "
    "Settings > Models starts with choosing or importing a model. Local GGUF, "
    "LiteRT-LM (.litertlm), and Android .task imports use the system file picker "
    "and do not require an account or an internet connection. Model compatibility "
    "and available memory are checked separately before inference. Online downloads, "
    "providers, response preferences, runtime controls and advanced settings are "
    "separate labelled sections. Do not claim upstream authors endorse this app."
)


def default_identity_for_runtime(default_identity: str) -> str:
    if not is_embedded_android_runtime():
        return default_identity
    return default_identity.replace(
        "You are Hermes Agent, built by Nous Research.",
        "You are Agent, an independently maintained Android assistant.",
        1,
    )


def help_guidance_for_runtime(upstream_guidance: str) -> str:
    return ANDROID_APP_HELP_GUIDANCE if is_embedded_android_runtime() else upstream_guidance
