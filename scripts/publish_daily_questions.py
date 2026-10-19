"""Validate and append a dated batch of exactly 100 CFG Riff Rumble questions."""
import json
import re
import sys
from pathlib import Path
from difflib import SequenceMatcher
from collections import Counter

root = Path(__file__).resolve().parents[1]
bank_path = root / "app/src/main/assets/questions.json"
date = sys.argv[1] if len(sys.argv) > 1 else "2026-10-18"
folder = root / "questions-daily" / date
cats = ("rock", "gear", "legends", "theory", "studio")
old = json.loads(bank_path.read_text(encoding="utf-8"))
assert len(old) >= 657
new = []
audit = []
for category in cats:
    lines = (folder / (category + ".psv")).read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 20, (category, len(lines))
    for line in lines:
        parts = line.split("|")
        assert len(parts) == 8, line[:80]
        cat, prompt, answer, wrong1, wrong2, wrong3, explanation, source = parts
        assert cat == category and source and len(prompt) >= 8
        choices = [wrong1, wrong2, wrong3]
        index = len(new) % 4
        choices.insert(index, answer)
        assert len(choices) == 4 and len(set(choices)) == 4
        assert explanation and all(choices)
        new.append(dict(category=cat, prompt=prompt, options=choices,
                        correct_index=index, explanation=explanation))
        audit.append(dict(prompt=prompt, source_key=source))
assert len(new) == 100
assert Counter(q["category"] for q in new) == Counter({c: 20 for c in cats})
