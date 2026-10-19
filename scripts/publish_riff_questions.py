#!/usr/bin/env python3
"""Validate and publish one reviewed daily trivia batch to the Android remote bank.

Run: python3 scripts/publish_riff_questions.py 2026-10-20
No third-party packages or secrets required. Does not touch multiplayer server.
"""
import collections
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BANK = ROOT / "app/src/main/assets/questions.json"
CATS = {"rock", "gear", "legends", "theory", "studio"}
STOP = set("which what who where when how many is are was were in the a an of and to for by on with did do does at as from into among its according name year".split())


def normalize(value):
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", value.lower().replace("’", "'").replace("'", " "))).strip()


def words(value):
    return {t for t in normalize(value).split() if len(t) > 1 and t not in STOP}


def trigrams(value):
    s = normalize(value)
    return {s[i:i+3] for i in range(len(s) - 2)}


def similarity(a, b):
    return len(a & b) / max(1, len(a | b))


def valid(q, index):
    if not isinstance(q, dict) or q.get("category") not in CATS:
        raise ValueError(f"Invalid category at {index}")
    if not isinstance(q.get("prompt"), str) or len(q["prompt"]) < 20:
        raise ValueError(f"Invalid prompt at {index}")
    opts = q.get("options")
    if not isinstance(opts, list) or len(opts) != 4 or any(not isinstance(x, str) or not x.strip() for x in opts):
        raise ValueError(f"Invalid options at {index}")
    if len({normalize(x) for x in opts}) != 4:
        raise ValueError(f"Repeated options at {index}")
    answer = q.get("correct_index")
    if type(answer) is not int or not 0 <= answer <= 3:
        raise ValueError(f"Invalid correct_index at {index}")
    if not isinstance(q.get("explanation"), str) or len(q["explanation"]) < 25:
        raise ValueError(f"Invalid explanation at {index}")
    if not q.get("source", "https://").startswith("https://"):
        raise ValueError(f"Invalid source at {index}")


def main(day):
    if not re.fullmatch(r"20\d\d-\d\d-\d\d", day):
        raise ValueError("Expected ISO date YYYY-MM-DD")
    src = ROOT / "docs/question-sources" / (day + ".json")
    doc = json.loads(src.read_text(encoding="utf-8"))
    bank = json.loads(BANK.read_text(encoding="utf-8"))
    new = doc["questions"]
    if doc["date"] != day or len(new) != 100 or doc["added"] != 100:
        raise ValueError("Exactly 100 questions and matching date required")
    if collections.Counter(q["category"] for q in new) != dict.fromkeys(sorted(CATS), 20):
        raise ValueError("Expected 20 questions in each of five categories")

    # Editorial correction after duplicate review: a unique harmony question
    # replaces an overly similar 'parallel major' prompt in the first draft.
    if day == "2026-10-20":
        q = [x for x in new if x["category"] == "theory"][3]
        q.update(
            prompt="In Roman numeral harmony analysis, what does a lowercase numeral usually indicate?",
            options=["A minor triad", "A major triad", "An octave doubling", "A rest"],
            correct_index=0,
            explanation="Lowercase Roman numerals conventionally label minor triads.",
        )

    for i, q in enumerate(new):
        valid(q, i)

    old_prompts = {normalize(q["prompt"]) for q in bank}
    fresh_prompts = {normalize(q["prompt"]) for q in new}
    if len(fresh_prompts) != 100:
        raise ValueError("Repeated prompt within new batch")
    if fresh_prompts.issubset(old_prompts) and len(bank) == doc["total"]:
        print(f"Already published: {len(bank)} questions")
        return
    if len(bank) != doc["previous"] or doc["total"] != len(bank) + 100:
        raise ValueError(f"Unexpected baseline: bank={len(bank)}, expected={doc['previous']}")
    if fresh_prompts & old_prompts:
        raise ValueError("Exact question duplicates")

    prior = []
    for q in bank + new:
        text = q["prompt"]
        t, g = words(text), trigrams(text)
        for prev_text, prev_t, prev_g in prior:
            if similarity(t, prev_t) >= 0.53 or similarity(g, prev_g) >= 0.76:
                raise ValueError(f"Near-duplicate: {text!r} vs {prev_text!r}")
        prior.append((text, t, g))

    merged = bank + [{k: q[k] for k in ("category", "prompt", "options", "correct_index", "explanation")} for q in new]
    if len(merged) != 957:
        raise ValueError("Unexpected merged bank size")
    BANK.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    src.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Added 100 questions. Total {len(merged)}. Source manifest updated.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: publish_riff_questions.py YYYY-MM-DD")
    main(sys.argv[1])
