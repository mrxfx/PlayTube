#!/bin/bash
awk '
/override suspend fun deleteDownload/ {
    in_del = 1
}
in_del && /val entity = downloadDao.getDownloadById/ {
    count++
    if (count > 1) next
}
in_del && /}/ {
    brace_count2++
    if (brace_count2 == 2) {
        in_del = 0
        count = 0
        brace_count2 = 0
    }
}
{ print }
' app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt > temp.kt && mv temp.kt app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt
