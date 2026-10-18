import json
from pathlib import Path
bank_path=Path("app/src/main/assets/questions.json")
source_path=Path("docs/question-sources/2026-10-19.json")
bank=json.loads(bank_path.read_text())
doc=json.loads(source_path.read_text())
new=doc["questions"]
assert len(bank)==doc["previous"]==757
assert len(new)==doc["added"]==100
assert doc["total"]==857
categories=dict.fromkeys(("rock","gear","legends","theory","studio"),0)
seen={q["prompt"].strip().casefold() for q in bank}
assert len(seen)==len(bank)
for q in new:
    assert set(q)=={"category","prompt","options","correct_index","explanation","source"}
    assert q["category"] in categories
    assert q["prompt"].endswith("?")
    key=q["prompt"].strip().casefold()
    assert key not in seen,key
    seen.add(key)
    assert len(q["options"])==4 and len(set(q["options"]))==4
    assert 0<=q["correct_index"]<4
    assert len(q["explanation"])>25 and q["source"].startswith("https://")
    categories[q["category"]]+=1
    bank.append({k:q[k] for k in ("category","prompt","options","correct_index","explanation")})
assert len(bank)==857 and all(n==20 for n in categories.values())
bank_path.write_text(json.dumps(bank,indent=2,ensure_ascii=False)+"\n")
print("Appended",len(new),"questions, total",len(bank))
