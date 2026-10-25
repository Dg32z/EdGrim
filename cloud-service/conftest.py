import sys
from pathlib import Path

# Make `app` importable without installing the package, so `pytest` works straight from a checkout.
sys.path.insert(0, str(Path(__file__).resolve().parent))
