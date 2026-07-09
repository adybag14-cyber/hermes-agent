import importlib.util
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
HARNESS_PATH = REPO_ROOT / "scripts" / "android_visual_harness.py"


def _load_harness():
    spec = importlib.util.spec_from_file_location("android_visual_harness", HARNESS_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_tap_center_for_label_strips_leading_and_trailing_whitespace():
    harness = _load_harness()
    xml = """
    <hierarchy>
      <node clickable="true" bounds="[100,200][300,400]">
        <node text=" Sensor Advisor " bounds="[120,220][280,360]" />
      </node>
    </hierarchy>
    """.strip()

    center = harness.tap_center_for_label(xml, "Sensor Advisor")

    assert center == (200, 300)


def test_tap_center_for_label_matches_content_description():
    harness = _load_harness()
    xml = """
    <hierarchy>
      <node clickable="true" bounds="[10,20][110,120]">
        <node content-desc="More input actions" bounds="[20,30][100,110]" />
      </node>
    </hierarchy>
    """.strip()

    center = harness.tap_center_for_label(xml, "More input actions")

    assert center == (60, 70)


def test_is_chat_home_xml_requires_composer_not_drawer_title():
    harness = _load_harness()
    chat_home = """
    <hierarchy>
      <node text="Message Hermes Fork" />
      <node text="Welcome to Hermes Agent Fork" />
    </hierarchy>
    """
    drawer_only = """
    <hierarchy>
      <node text="Hermes Fork" />
      <node text="Accounts" />
      <node text="Settings" />
    </hierarchy>
    """

    assert harness.is_chat_home_xml(chat_home)
    assert not harness.is_chat_home_xml(drawer_only)


def test_navigation_drawer_is_open_distinguishes_drawer_from_chat_home():
    harness = _load_harness()
    drawer = """
    <hierarchy>
      <node text="Accounts" />
      <node text="Settings" />
      <node text="Portal" />
    </hierarchy>
    """
    chat_home = """
    <hierarchy>
      <node text="Message Hermes Fork" />
      <node text="Accounts" />
      <node text="Settings" />
    </hierarchy>
    """

    assert harness.navigation_drawer_is_open(drawer)
    assert not harness.navigation_drawer_is_open(chat_home)


def test_ui_contains_text_tolerates_surrounding_whitespace_in_xml():
    harness = _load_harness()
    xml = '<node text=" Motion Trends " />'

    assert harness.ui_contains_text(xml, "Motion Trends")
    assert not harness.ui_contains_text(xml, "Radio Decision", "Missing label")