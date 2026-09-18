import os

def process_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    new_content = content.replace('com.arslandaim.playtube', 'com.rahul.vibetube')
    new_content = new_content.replace('arslandaim', 'rahul') # For namespaces or internal IDs if any

    if content != new_content:
        with open(filepath, 'w') as f:
            f.write(new_content)
        print(f"Updated {filepath}")

process_file('app/build.gradle.kts')
