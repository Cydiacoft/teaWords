"""Build one SQLite dictionary database from the real ECDICT CSV.

Design decisions that matter:

* **Source**: ECDICT CSV (MIT, verified by reading its LICENSE). No third-party dictionary text is
  bundled; Qwerty Learner's files are GPL-3.0 and their data is scraped from elsewhere, so they are
  deliberately left out and recorded as "pending licence verification".
* **Size**: ECDICT has 770,611 rows. Shipping all of them would add roughly 150 MB to the APK, so the
  build keeps every exam-tagged word plus the most frequent general words. That keeps the dictionary
  genuinely usable while staying a sane download. The filter is by corpus rank, not by hand.
* **Six exam books** come from the `tag` column, split on spaces: one word can belong to several books
  without being duplicated. The `tag` field is never treated as an opaque string.
* **Frequency** is kept twice: the raw corpus rank and a 0..1 score. Rank is what ranking needs;
  the score is a convenience for the UI.
* **Forms** come from the `exchange` field, which is ECDICT's morphological data. Irregular forms are
  therefore handled by data, not by stripping suffixes.
* **Chinese senses** are split on the separators ECDICT actually uses, each stored as its own row. This
  is a heuristic split of a gloss, not verified sense disambiguation, and the confidence column says so.
"""

import csv
import os
import sqlite3
import sys
from collections import Counter
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')

TOOLS_DIR = Path(__file__).resolve().parent
CSV_PATH = str(TOOLS_DIR / 'data' / 'ecdict.csv')
OUT_PATH = sys.argv[1] if len(sys.argv) > 1 else str(TOOLS_DIR / 'out' / 'dictionary.db')
# Keep the most frequent general words in addition to every exam-tagged one.
MAX_GENERAL_RANK = 60000

# exam tag -> (book id, display name, description, category)
BOOKS = [
    ('zk',    'zk',    '中考词汇',   '初中毕业学业考试大纲词汇（ECDICT 标签 zk）',      'school'),
    ('gk',    'gk',    '高考词汇',   '普通高等学校招生考试大纲词汇（ECDICT 标签 gk）',  'school'),
    ('cet4',  'cet4',  '大学英语四级', 'CET-4 词汇（ECDICT 标签 cet4）',                'college'),
    ('cet6',  'cet6',  '大学英语六级', 'CET-6 词汇（ECDICT 标签 cet6）',                'college'),
    ('ky',    'ky',    '考研英语',   '硕士研究生入学考试词汇（ECDICT 标签 ky）',        'postgraduate'),
    ('ielts', 'ielts', '雅思 IELTS', 'IELTS 词汇（ECDICT 标签 ielts）',                'international'),
    ('toefl', 'toefl', '托福 TOEFL', 'TOEFL 词汇（ECDICT 标签 toefl）',                'international'),
    ('gre',   'gre',   'GRE',       'GRE 词汇（ECDICT 标签 gre）',                    'international'),
]

SOURCE_NAME = 'ECDICT'
SOURCE_LICENSE = 'MIT（已在仓库 LICENSE 中核实）。词表数据混合多个来源，考试标签不等同于官方大纲。'
SOURCE_VERSION = 'ecdict.csv @ skywind3000/ECDICT master'


def normalize(word: str) -> str:
    """Same normalisation the app uses, so stored keys and queries agree."""
    return ' '.join(word.strip().replace('\u2019', "'").lower().split())


def split_senses(gloss: str):
    """Split a gloss into candidate senses, with a confidence for the split."""
    text = gloss.replace('\\n', '\n').replace('\n', '；').strip()
    if not text:
        return []
    pieces = []
    for chunk in text.split('；'):
        for part in chunk.replace('，', ',').split(','):
            part = part.strip()
            if part and part not in pieces:
                pieces.append(part)
    pieces = pieces[:6]
    if not pieces:
        return []
    split = len(pieces) > 1
    out = []
    for i, piece in enumerate(pieces):
        confidence = 0.6 if (split and len(piece) <= 12) else (0.45 if split else (0.4 if len(piece) <= 12 else 0.25))
        out.append((i, piece, confidence))
    return out


def parse_exchange(raw: str):
    """ECDICT exchange: 'd:perceived/p:perceived/3:perceives/i:perceiving'."""
    forms = []
    for item in (raw or '').split('/'):
        if ':' not in item:
            continue
        kind, value = item.split(':', 1)
        if kind in ('0', '1') or not value:
            continue
        for part in value.split(','):
            part = part.strip()
            if part:
                forms.append((normalize(part), kind))
    return forms


def main():
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    if os.path.exists(OUT_PATH):
        os.remove(OUT_PATH)

    db = sqlite3.connect(OUT_PATH)
    db.executescript('''
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE lex_words(
            id INTEGER PRIMARY KEY,
            normalized TEXT NOT NULL,
            word TEXT NOT NULL,
            phonetic TEXT NOT NULL DEFAULT '',
            zh TEXT NOT NULL DEFAULT '',
            en TEXT NOT NULL DEFAULT '',
            pos TEXT NOT NULL DEFAULT '',
            tags TEXT NOT NULL DEFAULT '',
            forms TEXT NOT NULL DEFAULT '',
            frequency TEXT NOT NULL DEFAULT '',
            source TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE lex_senses(
            id INTEGER PRIMARY KEY,
            word_id INTEGER NOT NULL,
            source TEXT NOT NULL,
            language TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            definition TEXT NOT NULL,
            confidence REAL NOT NULL DEFAULT 0
        );
        CREATE TABLE lex_forms(
            form TEXT NOT NULL,
            word_id INTEGER NOT NULL,
            kind TEXT NOT NULL
        );
        CREATE TABLE lex_zh(token TEXT NOT NULL, word_id INTEGER NOT NULL, freq_rank INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE lex_books_meta(book_id TEXT PRIMARY KEY, entry_count INTEGER NOT NULL);
        CREATE TABLE lex_sources(
            name TEXT PRIMARY KEY, license TEXT NOT NULL, version TEXT NOT NULL,
            imported_at INTEGER NOT NULL, note TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE word_books(
            id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
            exam_type TEXT NOT NULL, category TEXT NOT NULL, source_id TEXT NOT NULL,
            version TEXT NOT NULL, language TEXT NOT NULL DEFAULT 'en',
            total_word_count INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
        );
        CREATE TABLE word_book_entries(
            book_id TEXT NOT NULL, word_id INTEGER NOT NULL, priority INTEGER NOT NULL DEFAULT 0,
            freq_rank INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(book_id, word_id)
        );
        CREATE TABLE dictionary_meta(key TEXT PRIMARY KEY, value TEXT);
    ''')

    book_counts = Counter()
    book_priority = {b[0]: 0 for b in BOOKS}
    words = 0
    sense_rows = 0
    form_rows = 0
    zh_tokens = 0
    import_time = int(__import__('time').time() * 1000)

    insert_word = ('INSERT INTO lex_words(id, normalized, word, phonetic, zh, en, pos, tags, forms, '
                   'frequency, source) VALUES (?,?,?,?,?,?,?,?,?,?,?)')
    insert_sense = 'INSERT INTO lex_senses(word_id, source, language, ordinal, definition, confidence) VALUES (?,?,?,?,?,?)'
    insert_form = 'INSERT INTO lex_forms(form, word_id, kind) VALUES (?,?,?)'
    insert_zh = 'INSERT INTO lex_zh(token, word_id, freq_rank) VALUES (?,?,?)'
    insert_entry = 'INSERT OR IGNORE INTO word_book_entries(book_id, word_id, priority, freq_rank, source) VALUES (?,?,?,?,?)'

    with open(CSV_PATH, encoding='utf-8', newline='') as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            raw_word = (row.get('word') or '').strip()
            if not raw_word:
                continue
            norm = normalize(raw_word)
            if not norm or len(norm) > 160:
                continue
            tags = (row.get('tag') or '').split()
            rank = 0
            for key in ('frq', 'bnc'):
                value = (row.get(key) or '').strip()
                if value.isdigit() and int(value) > 0:
                    rank = int(value)
                    break
            # Keep exam words and frequent general words; skip the long tail of obscure entries.
            if not tags and (rank == 0 or rank > MAX_GENERAL_RANK):
                continue

            word_id = words + 1
            zh = (row.get('translation') or '').strip()
            forms = parse_exchange(row.get('exchange'))
            forms_text = ' '.join(f'{kind}:{form}' for form, kind in forms)
            db.execute(insert_word, (
                word_id, norm, raw_word, (row.get('phonetic') or '').strip(), zh,
                (row.get('definition') or '').strip(), (row.get('pos') or '').strip(),
                ' '.join(tags), forms_text,
                f'BNC {row.get("bnc", "")} / FRQ {row.get("frq", "")}'.strip(),
                SOURCE_NAME
            ))
            words += 1

            for ordinal, piece, confidence in split_senses(zh):
                db.execute(insert_sense, (word_id, SOURCE_NAME, 'zh', ordinal, piece, confidence))
                sense_rows += 1
            for ordinal, line in enumerate((row.get('definition') or '').split('\n')):
                line = line.strip()
                if line:
                    db.execute(insert_sense, (word_id, SOURCE_NAME, 'en', ordinal, line, 0.6))
                    sense_rows += 1
            for form, kind in forms:
                db.execute(insert_form, (form, word_id, kind))
                form_rows += 1
            # Chinese reverse tokens: single characters only. Bigrams were stored as well in the first
            # attempt, but they tripled the index for queries the two-character path already serves by
            # filtering the character token with instr(), so they are not worth the space.
            seen = set()
            for c in zh:
                if '\u3400' <= c <= '\u9fff' and c not in seen:
                    seen.add(c)
                    db.execute(insert_zh, (c, word_id, rank))
                    zh_tokens += 1

            for tag in tags:
                if tag in book_priority:
                    book_priority[tag] += 1
                    db.execute(insert_entry, (tag, word_id, book_priority[tag], rank, SOURCE_NAME))
                    book_counts[tag] += 1

            if words % 20000 == 0:
                print(f'  processed {words} kept words...', flush=True)

    db.execute('INSERT OR REPLACE INTO lex_sources(name, license, version, imported_at, note) VALUES (?,?,?,?,?)',
               (SOURCE_NAME, SOURCE_LICENSE, SOURCE_VERSION, import_time,
                '考试标签来自 ECDICT 的 tag 字段；不代表官方最新完整大纲。'))
    db.execute('INSERT OR REPLACE INTO lex_sources(name, license, version, imported_at, note) VALUES (?,?,?,?,?)',
               ('Qwerty Learner', 'GPL-3.0（未采用）', 'n/a', import_time,
                '仓库为 GPL-3.0，且其 README 声明词典数据来自第三方抓取项目；授权不明确，本版未打包其数据。'))

    for tag, book_id, name, desc, category in BOOKS:
        now = import_time
        db.execute(
            'INSERT OR REPLACE INTO word_books(id, name, description, exam_type, category, source_id, '
            'version, language, total_word_count, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)',
            (book_id, name, desc, tag, category, SOURCE_NAME, SOURCE_VERSION, 'en',
             book_counts.get(tag, 0), now, now)
        )

    print('building indexes...', flush=True)
    db.executescript('''
        CREATE INDEX idx_lex_words_normalized ON lex_words(normalized);
        CREATE INDEX idx_lex_words_word ON lex_words(word);
        CREATE UNIQUE INDEX idx_lex_words_norm_unique ON lex_words(normalized);
        CREATE INDEX idx_lex_forms_form ON lex_forms(form);
        CREATE INDEX idx_lex_zh_token ON lex_zh(token, freq_rank);
        CREATE INDEX idx_lex_senses_word ON lex_senses(word_id);
        CREATE INDEX idx_entries_book ON word_book_entries(book_id);
        CREATE INDEX idx_entries_word ON word_book_entries(word_id);
        CREATE VIRTUAL TABLE lex_fts USING fts4(lemma, definition, tokenize=unicode61);
        INSERT INTO lex_fts(docid, lemma, definition) SELECT id, normalized, en FROM lex_words;
    ''')
    db.execute("INSERT OR REPLACE INTO dictionary_meta(key, value) VALUES('schema_version', '1')")
    db.execute("INSERT OR REPLACE INTO dictionary_meta(key, value) VALUES('built_at', ?)", (str(import_time),))
    db.execute("INSERT OR REPLACE INTO dictionary_meta(key, value) VALUES('word_count', ?)", (str(words),))
    db.execute("INSERT OR REPLACE INTO dictionary_meta(key, value) VALUES('source', ?)", (SOURCE_VERSION,))
    db.execute("INSERT OR REPLACE INTO dictionary_meta(key, value) VALUES('license', ?)", (SOURCE_LICENSE,))
    print('computing token document frequencies...', flush=True)
    # Single-character tokens are coarse: one common character can index hundreds of words. Storing the
    # document frequency lets the app probe with the rarest character first and score candidates by how
    # much of the query they actually cover, which is what makes a looser "related meaning" search both
    # useful and cheap. Without it, a two-character query would have to scan a very wide candidate set.
    db.execute('CREATE TABLE lex_token_stats(token TEXT PRIMARY KEY, doc_freq INTEGER NOT NULL)')
    db.execute('INSERT INTO lex_token_stats(token, doc_freq) SELECT token, COUNT(*) FROM lex_zh GROUP BY token')
    db.commit()
    db.execute('VACUUM')
    db.commit()

    print()
    print(f'words kept      : {words}')
    print(f'sense rows      : {sense_rows}')
    print(f'form rows       : {form_rows}')
    print(f'chinese tokens  : {zh_tokens}')
    print('book sizes:')
    for tag, book_id, name, _, _ in BOOKS:
        print(f'   {name:14} {book_counts.get(tag, 0)}')
    print(f'database size   : {os.path.getsize(OUT_PATH) / 1024 / 1024:.1f} MB')
    db.close()


if __name__ == '__main__':
    main()
