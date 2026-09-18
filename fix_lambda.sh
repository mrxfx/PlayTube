#!/bin/bash
for file in app/src/main/java/com/rahul/vibetube/ui/screens/home/HomeScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/search/SearchScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/channel/ChannelScreen.kt app/src/main/java/com/rahul/vibetube/ui/screens/subscriptions/SubscriptionFeedScreen.kt; do
sed -i 's/isAdaptive: Boolean ->/isAdaptive: Boolean, isAudioOnly: Boolean, saveToDevice: Boolean ->/g' "$file"
sed -i 's/isAdaptive)$/isAdaptive, isAudioOnly, saveToDevice)/g' "$file"
done
