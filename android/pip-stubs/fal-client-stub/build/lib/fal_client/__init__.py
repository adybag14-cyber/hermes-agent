"""Android / Chaquopy placeholder for fal-client.

The Hermes Android MVP currently omits image generation from the default mobile
tool profile because fal-client depends on msgpack, which has no Android wheel
in Chaquopy's index today.
"""

__all__ = ["SyncClient", "submit", "client", "__version__", "__hermes_android_stub__"]
__version__ = "0.13.1"
__hermes_android_stub__ = True


class SyncClient:
    def __init__(self, *args, **kwargs):
        raise RuntimeError(
            "fal-client is not available in the Hermes Android MVP build. "
            "Image generation is deferred until Android wheels are available."
        )


def submit(*args, **kwargs):
    raise RuntimeError(
        "fal-client is not available in the Hermes Android MVP build. "
        "Image generation is deferred until Android wheels are available."
    )


class _ClientModule:
    def __getattr__(self, name):
        raise RuntimeError(
            "fal-client is not available in the Hermes Android MVP build. "
            "Image generation is deferred until Android wheels are available."
        )


client = _ClientModule()
