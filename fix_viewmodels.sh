#!/bin/bash
for file in app/src/main/java/com/rahul/vibetube/ui/screens/player/PlayerViewModel.kt app/src/main/java/com/rahul/vibetube/ui/screens/home/HomeViewModel.kt app/src/main/java/com/rahul/vibetube/ui/screens/search/SearchViewModel.kt app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistViewModel.kt app/src/main/java/com/rahul/vibetube/ui/screens/channel/ChannelViewModel.kt app/src/main/java/com/rahul/vibetube/ui/screens/subscriptions/SubscriptionsFeedViewModel.kt; do
awk '
/fun download\(video: VideoItem, bundle:/ {
    sub(/Boolean\) \{/, "Boolean, isAudioOnly: Boolean = false, saveToDevice: Boolean = false) {")
}
/downloadVideoUseCase\(/ {
    in_block = 1
    print "            downloadVideoUseCase("
    print "                videoId = video.id,"
    print "                url = url,"
    print "                title = video.title,"
    print "                thumbnailUrl = video.thumbnailUrl,"
    print "                uploaderName = video.uploaderName,"
    print "                quality = quality,"
    print "                format = format,"
    print "                audioUrl = audioUrl,"
    print "                isAudioOnly = isAudioOnly,"
    print "                saveToDevice = saveToDevice"
    print "            )"
    next
}
in_block && /^            \)/ {
    in_block = 0
    next
}
in_block { next }
{ print }
' "$file" > temp.kt && mv temp.kt "$file"
done
