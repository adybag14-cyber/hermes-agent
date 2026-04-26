"""Android / Chaquopy placeholder for the Anthropic SDK.

Hermes' Android app does not expose the direct Anthropic provider in the MVP,
but the shared Python package currently depends on ``anthropic``. The real SDK
pulls ``jiter``, which has no Android wheel in Chaquopy's index today.

This stub is preinstalled only inside the embedded Android build so shared-core
imports can succeed while any attempted direct Anthropic usage still fails with
an explicit runtime error.
"""

__all__ = ["Anthropic", "__version__"]
__version__ = "0.39.0"


class Anthropic:
    def __init__(self, *args, **kwargs):
        raise RuntimeError(
            "The real 'anthropic' SDK is not available in the Hermes Android MVP build. "
            "Use Nous, OpenAI, OpenRouter, or another OpenAI-compatible provider in the app."
        )
