"""Shared locations: committed reference/model inputs, disposable generated output."""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parent.parent
REFERENCE=ROOT/'reference/audio'
MODEL_DIR=ROOT/'audio-analysis/model'
OUTPUT=ROOT/'audio-analysis/output'
RENDERED=OUTPUT/'rendered'
def app_version():
    return re.search(r'versionName\s*=\s*"([^"]+)"',(ROOT/'app/build.gradle.kts').read_text()).group(1)
