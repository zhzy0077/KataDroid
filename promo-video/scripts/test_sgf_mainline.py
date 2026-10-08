from pathlib import Path
import unittest
from sgf_mainline import factual_mainline, mainline, parse


class SgfTests(unittest.TestCase):
    def test_escaped_comment_and_side_variation_are_removed(self):
        source = r"(;SZ[19]C[comment with ; ( ) and \] escaped bracket];B[aa](;W[bb])(;W[cc]C[side line]))"
        clean, moves = factual_mainline(source)
        self.assertEqual(moves, [{"B": ["aa"]}, {"W": ["bb"]}])
        self.assertNotIn("C[", clean)
        self.assertNotIn("cc", clean)
        self.assertEqual(len(mainline(parse(clean)[0])), 3)

    def test_tutorial_game_is_complete_and_contains_only_factual_properties(self):
        path = Path(__file__).resolve().parents[1] / "content/tutorial-game.sgf"
        nodes = mainline(parse(path.read_text())[0])
        self.assertEqual(len(nodes), 181)
        self.assertEqual(nodes[0]["DT"], ["2016-03-13"])
        self.assertEqual(nodes[0]["PB"], ["AlphaGo"])
        self.assertEqual(nodes[0]["PW"], ["Lee Sedol"])
        self.assertEqual(nodes[0]["RE"], ["W+Resign"])
        self.assertTrue(all("C" not in n for n in nodes))

    def test_invalid_sgf_is_rejected(self):
        with self.assertRaises(ValueError):
            parse("(;B[aa]")


if __name__ == "__main__":
    unittest.main()
