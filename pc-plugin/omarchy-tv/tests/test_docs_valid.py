"""v2.21 review: docs/openapi.yaml must parse (a comma in an unquoted flow mapping broke it once)."""
import unittest
from pathlib import Path

DOC = Path(__file__).resolve().parents[3] / "docs" / "openapi.yaml"


class OpenApiTest(unittest.TestCase):
    def test_parses_and_lists_the_mcp_routes(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML not installed")
        d = yaml.safe_load(DOC.read_text(encoding="utf-8"))
        self.assertIn("/v1/mcp", d["paths"])
        self.assertIn("202", d["paths"]["/mcp"]["post"]["responses"])
        for p, ops in d["paths"].items():
            for op in ops.values():
                if "x-scope" in op:
                    self.assertIn(op["x-scope"], ("read", "notify", "control"), p)


if __name__ == "__main__":
    unittest.main()
