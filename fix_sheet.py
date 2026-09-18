with open("app/src/main/java/com/rahul/vibetube/ui/components/SelectionSheets.kt", "r") as f:
    content = f.read()

# Fix the duplicate annotations
content = content.replace("@OptIn(ExperimentalMaterial3Api::class)\n@OptIn(ExperimentalMaterial3Api::class)", "@OptIn(ExperimentalMaterial3Api::class)")
content = content.replace("@Composable\n@Composable", "@Composable")

# Fix the missing components (SaveLocationChip, TabChip, StreamQualityRow)
# Ah wait, I used them in my new sheet but I forgot to implement them!
