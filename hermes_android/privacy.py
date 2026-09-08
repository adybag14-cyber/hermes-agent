"""Admission for Android remote processing uses the app's actual consent authority."""
from hermes_android.runtime_identity import is_embedded_android_runtime


def require_remote_processing_consent() -> None:
    if not is_embedded_android_runtime():
        return
    # No env-var substitute or permission inference: the installed app owns consent.
    from java import jclass

    jclass(
        "com.mobilefork.hermesagent.privacy.RemoteProcessingConsentStore"
    ).requireConfiguredRemoteConsent()
