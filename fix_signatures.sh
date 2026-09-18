#!/bin/bash
for file in app/src/main/java/com/rahul/vibetube/ui/screens/home/HomeScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/search/SearchScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/channel/ChannelScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/subscriptions/SubscriptionFeedScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/player/PlayerScreen.kt; do
sed -i 's/onDownloadConfirm: (VideoItem, StreamBundle, String?, String?, String?, Boolean) -> Unit/onDownloadConfirm: (VideoItem, StreamBundle, String?, String?, String?, Boolean, Boolean, Boolean) -> Unit/g' "$file"
done
