from hermes_android.nous_portal_bridge import read_nous_portal_state


def test_read_nous_portal_state_defaults(monkeypatch):
    monkeypatch.setattr(
        "hermes_android.nous_portal_bridge.get_nous_auth_status",
        lambda: {
            "logged_in": False,
            "portal_base_url": None,
            "inference_base_url": None,
        },
    )

    state = read_nous_portal_state()

    assert state == {
        "portal_url": "https://portal.nousresearch.com",
        "logged_in": False,
        "inference_url": "",
    }


def test_read_nous_portal_state_prefers_auth_status_url(monkeypatch):
    monkeypatch.setattr(
        "hermes_android.nous_portal_bridge.get_nous_auth_status",
        lambda: {
            "logged_in": True,
            "portal_base_url": "https://portal-staging.nousresearch.com",
            "inference_base_url": "https://inference-api.nousresearch.com/v1",
        },
    )

    state = read_nous_portal_state()

    assert state == {
        "portal_url": "https://portal-staging.nousresearch.com",
        "logged_in": True,
        "inference_url": "https://inference-api.nousresearch.com/v1",
    }
