"""Regression checks for proof source repository boundaries."""
import subprocess
import tempfile
import unittest
from pathlib import Path

from build_quantum_proof import require_source_checkout


class SourceCheckoutTests(unittest.TestCase):
    def test_incomplete_cache_cannot_use_parent_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            cache = root / 'proof-cache'
            cache.mkdir()
            with self.assertRaisesRegex(RuntimeError, 'independent Git checkout'):
                require_source_checkout(cache)

    def test_independent_checkout_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            require_source_checkout(root)


if __name__ == '__main__':
    unittest.main()
