#!/bin/bash
for file in app/src/main/java/com/rahul/vibetube/ui/screens/search/SearchScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/channel/ChannelScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/subscriptions/SubscriptionFeedScreen.kt; do
sed -i 's/downloadDialogState.bundle/currentDownloadState.bundle/g' "$file"
sed -i 's/downloadDialogState.video/currentDownloadState.video/g' "$file"
done
