import importlib.util
import unittest
from pathlib import Path


def load_analyzer_module():
    analyzer_path = Path(__file__).with_name("analyzer.py")
    spec = importlib.util.spec_from_file_location("analyzer", analyzer_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


analyzer = load_analyzer_module()


class AnalyzerRegressionTests(unittest.TestCase):
    def test_compilation_error_beats_warning_noise(self):
        log = """
[WARNING] The dependency declaration is duplicated but still allowed.
[WARNING] Duplicate dependency org.projectlombok:lombok found in pom.xml
[INFO] --- maven-compiler-plugin:3.14.1:compile (default-compile) @ app ---
[ERROR] /Users/me/project/src/main/java/com/cyhub/backend/controller/AdminChatController.java:[42,13] cannot find symbol
[ERROR]   symbol:   class AdminConversationSummary
[ERROR]   location: package com.cyhub.backend.dto.admin
[ERROR] /Users/me/project/src/main/java/com/cyhub/backend/controller/AdminChatController.java:[58,13] cannot find symbol
[ERROR]   symbol:   class AdminReplyRequest
[ERROR]   location: package com.cyhub.backend.dto.admin
[ERROR] COMPILATION ERROR :
[ERROR] Compilation failure
""".strip()

        result = analyzer.classify_and_fix(log)

        self.assertEqual(result["errorType"], "COMPILATION_ERROR")
        self.assertEqual(result["errorTypeDisplay"], "Compilation Failure - Missing Classes")
        self.assertIn("AdminConversationSummary", result["missingSymbols"])
        self.assertIn("AdminReplyRequest", result["missingSymbols"])
        self.assertIn("Missing classes", result["whatIsWrong"])
        self.assertIn("Create the missing classes", result["fixRecommendation"])


if __name__ == "__main__":
    unittest.main()
