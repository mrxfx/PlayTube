#!/bin/bash
awk '
/override suspend fun resumeAllPausedDownloads\(\)/ {
    in_resume_all = 1
}
in_resume_all && /val entity = downloadDao.getDownloadById/ {
    next
}
in_resume_all && /}/ {
    brace_count++
    if (brace_count == 3) {
        in_resume_all = 0
        brace_count = 0
    }
}
{ print }
' app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt > temp.kt && mv temp.kt app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt
y