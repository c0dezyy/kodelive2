# Copies the watch page out of WebPreview.java into index.html so the relay serves the exact same page.
# Run from the repo root after editing the page:  python3 relay/sync-page.py
import re, textwrap
src = open('plugin/src/main/java/com/codezyy/spectatorcamera/WebPreview.java', encoding='utf-8').read()
m = re.search(r'PAGE = """\n(.*?)\n\s*""";', src, re.S)
open('relay/index.html', 'w', encoding='utf-8').write(textwrap.dedent(m.group(1)) + '\n')
print('relay/index.html updated')
