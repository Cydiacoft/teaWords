"""Add the derived token-frequency table to an already built dictionary.

This is the incremental path: `lex_token_stats` is derived from `lex_zh`, so it can be added without
re-reading the 63 MB source CSV or rebuilding the word tables. The same statement lives in the build
tool for a from-scratch build, so a full rebuild and an incremental update produce the same file.
"""
import sqlite3
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
DB = sys.argv[1] if len(sys.argv) > 1 else str(Path(__file__).resolve().parent / 'out' / 'dictionary.db')

db = sqlite3.connect(DB)
start = time.time()

exists = db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name='lex_token_stats'").fetchone()[0]
if exists:
    db.execute('DROP TABLE lex_token_stats')
db.execute('CREATE TABLE lex_token_stats(token TEXT PRIMARY KEY, doc_freq INTEGER NOT NULL)')
db.execute('INSERT INTO lex_token_stats(token, doc_freq) SELECT token, COUNT(*) FROM lex_zh GROUP BY token')
db.execute("INSERT OR REPLACE INTO dictionary_meta(key, value) VALUES('schema_version', '2')")
db.commit()

print(f'token stats rows: {db.execute("SELECT COUNT(*) FROM lex_token_stats").fetchone()[0]}')
print(f'elapsed: {time.time() - start:.1f}s')
print('sample doc frequencies:')
for token in ('动', '物', '兽', '问', '题', '发', '行'):
    row = db.execute('SELECT doc_freq FROM lex_token_stats WHERE token=?', (token,)).fetchone()
    print(f'   {token}: {row[0] if row else 0}')
db.execute('VACUUM')
db.commit()
import os
print(f'database size: {os.path.getsize(DB) / 1024 / 1024:.1f} MB')
db.close()
