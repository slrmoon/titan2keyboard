#!/usr/bin/env python3
"""
Build the bundled word lists.

    pip install wordfreq pymorphy3 pymorphy3-dicts-ru
    python3 tools/build_dictionary.py

Writes app/src/main/assets/dict/<lang>.tsv.gz with lines "word<TAB>zipf*100".

Word frequencies come from wordfreq (CC BY-SA 4.0, see NOTICE.md). Web-derived lists
contain popular misspellings ("здраствуйте", "вообщем", "teh"); if they stayed in the
dictionary the keyboard would treat them as real words and never fix them. They are
dropped here when:
  * Russian: pymorphy3 does not know the word AND a much more frequent dictionary word
    is one edit away (slang like "кста" or "норм" has no such neighbour and is kept);
  * English: the word is a swap of two adjacent letters of a far more frequent word.
"""
import gzip
import os
import re

from wordfreq import top_n_list, zipf_frequency

LANGS = {
    # language: (how many words, allowed characters, alphabet for edit neighbours)
    "ru": (100_000, re.compile(r"^[а-яё]+(?:-[а-яё]+)*$"), "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"),
    "en": (60_000, re.compile(r"^[a-z]+(?:['-][a-z]+)*$"), "abcdefghijklmnopqrstuvwxyz"),
}
MAX_LEN = 24
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "dict")

RU_SUSPECT_MAX_ZIPF = 3.6  # only rare-ish words are checked
RU_MIN_GAP = 2.0           # neighbour must be at least 100x more frequent (keeps rare names)
RU_MIN_LEN = 5             # short words have too many neighbours to judge

# Well-known misspellings that are frequent enough to slip past the automatic filter.
KNOWN_MISSPELLINGS = {
    "ru": """здраствуйте здраствуй извените извени извеняюсь сдесь вобщем вообщем вобще щитаю
        прийдет прийдешь прийду ложить пологаю впринципе потомучто чтоли незнаю неочень немогу
        нехочу помойму помоему вкурсе какбы кароче карочь будующий будующее симпотичный оплотить
        агенство инциндент расчитывать расчитать""".split(),
    "en": "teh adn taht thier recieve wich becuase alot definately seperate untill occured".split(),
}
EN_SWAP_MIN_GAP = 2.5


def edits1(word: str, alphabet: str):
    splits = [(word[:i], word[i:]) for i in range(len(word) + 1)]
    for a, b in splits:
        if b:
            yield a + b[1:]                      # deletion
        if len(b) > 1:
            yield a + b[1] + b[0] + b[2:]        # transposition
        for c in alphabet:
            if b:
                yield a + c + b[1:]              # substitution
            yield a + c + b                      # insertion


def swaps(word: str):
    for i in range(len(word) - 1):
        yield word[:i] + word[i + 1] + word[i] + word[i + 2:]


def collect(lang, limit, pattern):
    rows = []
    for w in top_n_list(lang, limit * 2, wordlist="large"):
        if len(rows) >= limit:
            break
        if len(w) > MAX_LEN or not pattern.match(w):
            continue
        if len(w) == 1 and lang == "en" and w not in ("a", "i"):
            continue
        z = zipf_frequency(w, lang, wordlist="large")
        if z > 0:
            rows.append((w, z))
    return rows


def drop_misspellings(lang, rows, alphabet):
    freq = dict(rows)
    dropped = []
    if lang == "ru":
        import pymorphy3
        morph = pymorphy3.MorphAnalyzer()
        for w, z in rows:
            if z >= RU_SUSPECT_MAX_ZIPF or len(w) < RU_MIN_LEN or morph.word_is_known(w):
                continue
            if any(freq.get(e, 0) >= z + RU_MIN_GAP for e in edits1(w, alphabet) if e != w):
                dropped.append(w)
    elif lang == "en":
        for w, z in rows:
            if any(freq.get(e, 0) >= z + EN_SWAP_MIN_GAP for e in swaps(w) if e != w):
                dropped.append(w)
    dropped += [w for w in KNOWN_MISSPELLINGS.get(lang, []) if w in freq and w not in dropped]
    bad = set(dropped)
    return [(w, z) for w, z in rows if w not in bad], dropped


def build(lang, limit, pattern, alphabet):
    rows = collect(lang, int(limit * 1.05), pattern)
    rows, dropped = drop_misspellings(lang, rows, alphabet)
    rows = rows[:limit]
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, f"{lang}.tsv.gz")
    with gzip.open(path, "wt", encoding="utf-8", compresslevel=9) as f:
        f.write(f"# {lang} word list from wordfreq (CC BY-SA 4.0), {len(rows)} words\n")
        for w, z in rows:
            f.write(f"{w}\t{int(round(z * 100))}\n")
    return len(rows), dropped


if __name__ == "__main__":
    for lang, (limit, pattern, alphabet) in LANGS.items():
        n, dropped = build(lang, limit, pattern, alphabet)
        print(f"{lang}: {n} words, {len(dropped)} misspellings dropped, e.g. {dropped[:30]}")
