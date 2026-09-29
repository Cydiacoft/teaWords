"""Verify the built dictionary against the acceptance cases, with query plans and timings."""
import sqlite3
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
DB = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / 'app' / 'src' / 'main' / 'assets' / 'dictionary.db'
db = sqlite3.connect(DB.as_uri() + '?mode=ro', uri=True)
db.row_factory = sqlite3.Row


def timed(label, sql, args=()):
    start = time.perf_counter()
    rows = db.execute(sql, args).fetchall()
    ms = (time.perf_counter() - start) * 1000
    print(f'  {label:44} {ms:7.2f} ms  rows={len(rows)}')
    return rows


print('=== A: one word in several books, but only one base entry ===')
rows = timed('entries for "abolish"',
             "SELECT b.name, e.book_id FROM word_book_entries e JOIN word_books b ON b.id=e.book_id "
             "JOIN lex_words w ON w.id=e.word_id WHERE w.normalized='abolish'")
for r in rows:
    print('     ', r['name'])
print('     base word rows:', db.execute("SELECT COUNT(*) FROM lex_words WHERE normalized='abolish'").fetchone()[0])

print()
print('=== D: case-insensitive exact search ===')
for q in ('Apple', 'apple', 'APPLE'):
    rows = timed(f'exact {q!r}', 'SELECT word FROM lex_words WHERE normalized=? LIMIT 3', (q.lower(),))
    print('     ', [r['word'] for r in rows])

print()
print('=== E: prefix autocomplete (no full scan) ===')
rows = timed("prefix 'con'", "SELECT word FROM lex_words WHERE normalized>='con' AND normalized<'con\uffff' ORDER BY normalized LIMIT 12")
print('     ', [r['word'] for r in rows])
print('  plan:', [r['detail'] for r in db.execute(
    "EXPLAIN QUERY PLAN SELECT word FROM lex_words WHERE normalized>='con' AND normalized<'con\uffff' ORDER BY normalized LIMIT 12")])

print()
print('=== F: two-character Chinese reverse lookup ===')
for q in ('放弃', '问题', '动物园'):
    rows = timed(f'chinese {q!r}', "SELECT w.word FROM lex_zh z JOIN lex_words w ON w.id=z.word_id "
                                   "WHERE z.token=? AND instr(w.zh, ?)>0 LIMIT 8", (q[0], q))
    print('     ', [r['word'] for r in rows])
print('  plan:', [r['detail'] for r in db.execute(
    "EXPLAIN QUERY PLAN SELECT w.word FROM lex_zh z JOIN lex_words w ON w.id=z.word_id WHERE z.token=? AND instr(w.zh,?)>0 LIMIT 8",
    ('放', '放弃'))])

print()
print('=== G: irregular forms resolve to their base ===')
for form in ('went', 'running', 'better', 'perceived'):
    rows = timed(f'form {form!r}', "SELECT f.kind, w.word FROM lex_forms f JOIN lex_words w ON w.id=f.word_id WHERE f.form=? LIMIT 4", (form,))
    print('     ', [(r['word'], r['kind']) for r in rows])

print()
print('=== I: book-scoped search excludes other books ===')
rows = timed('cet4 words starting with "con"',
             "SELECT w.word FROM word_book_entries e JOIN lex_words w ON w.id=e.word_id "
             "WHERE e.book_id='cet4' AND w.normalized LIKE 'con%' LIMIT 5")
print('     ', [r['word'] for r in rows])
outside = timed('same query but not in cet4',
                "SELECT COUNT(*) c FROM lex_words w WHERE w.normalized LIKE 'con%' AND w.id NOT IN "
                "(SELECT word_id FROM word_book_entries WHERE book_id='cet4')")
print('     words outside cet4:', outside[0]['c'])

print()
print('=== K: re-importing the same book adds no duplicate links ===')
print('     unique index present:', bool(db.execute(
    "SELECT 1 FROM sqlite_master WHERE type='index' AND name='sqlite_autoindex_word_book_entries_1'").fetchone()))
print('     duplicate (book,word) pairs:', db.execute(
    "SELECT COUNT(*) FROM (SELECT book_id, word_id FROM word_book_entries GROUP BY book_id, word_id HAVING COUNT(*)>1)").fetchone()[0])

print()
print('=== size breakdown ===')
for table in ('lex_words', 'lex_senses', 'lex_forms', 'lex_zh', 'word_book_entries', 'lex_fts'):
    n = db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]
    print(f'  {table:20} rows={n}')
