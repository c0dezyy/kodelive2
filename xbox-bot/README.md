# Xbox camera bot

A Bedrock bot that signs in with a real Microsoft/Xbox account and stays connected.
The SpectatorCamera plugin sees it in `camera-accounts` and turns it into the camera.

## Login (no password is ever entered here)
On first start the bot asks Microsoft for a device code. You get the link and code in:
1. the service logs, and
2. the status page (`https://YOUR-RAILWAY-URL/`), and
3. Discord, if `DISCORD_WEBHOOK` is set.
Open the link, enter the code, sign in with a **spare** Microsoft account. The login is cached in `AUTH_DIR`.

## Where to run it
See ../README.md, step 5.
