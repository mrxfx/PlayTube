#!/bin/bash
for file in app/src/main/java/com/rahul/vibetube/ui/screens/home/HomeScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/search/SearchScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/channel/ChannelScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/subscriptions/SubscriptionFeedScreen.kt; do
awk '
/onDownloadConfirm: \(VideoItem, StreamBundle, String\?, String\?, String\?, Boolean\) -> Unit/ {
    sub(/Boolean\) -> Unit/, "Boolean, Boolean, Boolean) -> Unit")
}
/DownloadSelectionSheet\(/ {
    in_block = 1
    print "                DownloadSelectionSheet("
    print "                    videoStreams = downloadDialogState.bundle.videoStreams,"
    print "                    audioStreams = downloadDialogState.bundle.audioStreams,"
    print "                    onDismiss = { onDismissDownload() },"
    print "                    onDownload = { stream, isAudioOnly, saveToDevice ->"
    print "                        onDownloadConfirm("
    print "                            downloadDialogState.video,"
    print "                            downloadDialogState.bundle,"
    print "                            stream.url,"
    print "                            stream.quality,"
    print "                            stream.format,"
    print "                            stream.isAdaptive,"
    print "                            isAudioOnly,"
    print "                            saveToDevice"
    print "                        )"
    print "                    }"
    print "                )"
    next
}
in_block && /^                \)/ {
    in_block = 0
    next
}
in_block { next }
{ print }
' "$file" > temp.kt && mv temp.kt "$file"
done
