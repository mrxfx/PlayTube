import re

with open("app/src/main/java/com/rahul/vibetube/ui/components/SelectionSheets.kt", "r") as f:
    content = f.read()

# We want to replace everything from `fun DownloadSelectionSheet(` to the end of the block.
# Finding the block:
start_str = "fun DownloadSelectionSheet("
end_str = "fun SubtitleSelectionSheet("

if start_str in content and end_str in content:
    start_idx = content.find(start_str)
    end_idx = content.find(end_str)
    # also remove the @OptIn just above SubtitleSelectionSheet if we need, but we'll just stop at @OptIn
    end_idx = content.rfind("@OptIn", start_idx, end_idx)
    if end_idx == -1:
        end_idx = content.find(end_str)
        
    print(f"Start: {start_idx}, End: {end_idx}")

