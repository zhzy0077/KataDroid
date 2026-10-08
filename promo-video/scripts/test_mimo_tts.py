import unittest
from mimo_tts import fingerprint, request_payload, safe_message, speed_factor


class MiMoTests(unittest.TestCase):
    def setUp(self):
        self.plan = {"voice": "白桦", "style": "自然中文", "clips": []}
        self.clip = {"text": "离线分析。", "slotSeconds": 2.8}

    def test_target_text_uses_assistant_role(self):
        payload = request_payload(self.plan, self.clip)
        self.assertEqual(payload["messages"][1], {"role": "assistant", "content": "离线分析。"})
        self.assertEqual(payload["audio"], {"format": "wav", "voice": "白桦"})
        self.assertNotIn("api_key", payload)

    def test_fingerprint_changes_with_voice(self):
        self.assertEqual(fingerprint(self.plan), fingerprint(dict(self.plan)))
        self.assertNotEqual(fingerprint(self.plan), fingerprint({**self.plan, "voice": "茉莉"}))

    def test_credentials_are_redacted_from_errors(self):
        self.assertEqual(safe_message("key sk-example_secret rejected"), "key [REDACTED] rejected")

    def test_bounded_tempo_adjustment(self):
        self.assertEqual(speed_factor(2, 3), 1)
        self.assertGreater(speed_factor(3, 3), 1)
        with self.assertRaises(ValueError):
            speed_factor(5, 2)
        with self.assertRaises(ValueError):
            speed_factor(0, 2)


if __name__ == "__main__":
    unittest.main()
