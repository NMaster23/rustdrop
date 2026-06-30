# RustDrop

This is an airdrop clone built in Rust, supporting file sharing over __Wifi__ and __Bluetooth__.
This project is based on the Bluest crate for Bluetooth, and several different crates to get Wifi working. This project uses tokio for asynchronous timing and it uses thread management.

## Setup Instructions:
To set this up all you need to do is run the provided app for your platform. So for windows you would download the .exe and run it. For Android you would download the .apk and sideload it. For anything to show up you need to have two devices running the app in relatively close proximity or connected to the same network. Then for Android press the refresh buttonn, this is because scanning is a battery intensive task. On Windows there are no such limitations so it automatically scans.

APK Install Guide: https://www.androidauthority.com/how-to-install-apks-31494/
(The embedding was not allowing me to access the site)

## AI Use:

I used GitHub Copilot for some autocomplete and some AI-generated functions that I think were replaced. All the AI I used is listed in the GitHub commits. I also used it to generate all of the size keys. AI Guidance means that I used it to do one of these. Generate examples if there were bad docs, or do all of the research for me and compile it into a list of links.
