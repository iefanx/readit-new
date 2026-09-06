"""Run host-side probes after :app:assembleDebug. No Android device required."""
from pathlib import Path
import os
import sqlite3
import subprocess

here = Path(__file__).resolve().parent
root = here.parent.parent
java_home = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'))
cache = root / '.gradle-local/caches/modules-2/files-2.1'
classpath = [root / 'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes']
for pattern in ('org.jetbrains.kotlin/kotlin-stdlib/2.2.10/**/*.jar', 'org.jsoup/jsoup/1.15.4/**/*.jar'):
    classpath.extend(cache.glob(pattern))
import tempfile
with tempfile.TemporaryDirectory(prefix='readout-audit-') as temp:
    cp = ':'.join(map(str, classpath))
    subprocess.run([str(java_home/'bin/javac'), '-cp', cp, '-d', temp, str(here/'AuditProbe.java')], check=True)
    subprocess.run([str(java_home/'bin/java'), '-cp', cp+':'+temp, 'AuditProbe'], check=True)

# Reproduce the repository's SQL character / Kotlin UTF-16 offset mismatch.
original = '😀' + 'a'*799999 + 'BOUNDARY' + 'b'*800000
connection = sqlite3.connect(':memory:')
connection.execute('create table documents(content text)')
connection.execute('insert into documents values(?)', (original,))
length = connection.execute('select length(content) from documents').fetchone()[0]
offset, output = 1, ''
while offset <= length:
    chunk = connection.execute('select substr(content,?,800000) from documents', (offset,)).fetchone()[0]
    if not chunk:
        break
    output += chunk
    offset += len(chunk.encode('utf-16-le')) // 2
print('Repository actual-size chunk probe:', 'input', len(original), 'output', len(output), 'equal', original == output)
print('BOUNDARY retained:', 'BOUNDARY' in output, 'OUNDARY retained:', 'OUNDARY' in output)
