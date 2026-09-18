import os
import re

old_header_regex = re.compile(
    r'/\*[\s\*]*PlayTube Project Original \(2026\)[\s\*]*arslandaim-hub \(GitHub\.com/arslandaim-hub\)[\s\*]*Licenced Under GPL-3\.0\+[\s\*]*/',
    re.MULTILINE
)

new_header = """/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */"""

def process_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    new_content = old_header_regex.sub(new_header, content)
    new_content = new_content.replace('com.arslandaim.playtube', 'com.rahul.vibetube')

    if content != new_content:
        with open(filepath, 'w') as f:
            f.write(new_content)
        print(f"Updated {filepath}")

for root, dirs, files in os.walk('app/src'):
    for file in files:
        if file.endswith('.kt') or file.endswith('.java') or file.endswith('.xml') or file.endswith('.pro'):
            process_file(os.path.join(root, file))
