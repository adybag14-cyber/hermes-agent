import unittest

from odyn_ai.core.voice_commands import parse_voice_command


class VoiceCommandTests(unittest.TestCase):
    def test_build_command_is_detected_and_defaults_to_web(self):
        command = parse_voice_command("Zbuduj aplikację kalkulator")
        self.assertIsNotNone(command)
        self.assertEqual(command.action, "autonomous_build")
        self.assertEqual(command.platform, "web")
        self.assertEqual(command.name, "kalkulator")

    def test_android_command_selects_android(self):
        command = parse_voice_command("Stwórz aplikację kalkulator na Android")
        self.assertIsNotNone(command)
        self.assertEqual(command.platform, "android")
        self.assertEqual(command.name, "kalkulator")

    def test_normal_conversation_is_not_executed(self):
        self.assertIsNone(parse_voice_command("Opowiedz mi jak działa kalkulator"))


if __name__ == "__main__":
    unittest.main()
