# A Flasher

Run adb and fastboot commands on another Android phone straight from your phone over an OTG cable. No PC, no Termux.

## Build the APK (no computer needed)
1. Upload every file of this folder (including the `.github` folder) to a new GitHub repository.
2. Open the repository's **Actions** tab. "Build APK" runs automatically (or press **Run workflow**).
3. When it finishes, open the run and download **A-Flasher-APK** under Artifacts.

You can also open the folder in Android Studio and choose Build > Build APK(s).

## Use
- Plug the OTG adapter into this phone and connect the cable to the target phone. The app checks the connection every 2 seconds and tells you what is missing (OTG, USB debugging, authorization).
- Commands tab: tap the play button to run a command, tap the text to edit it in the terminal first.
- Attach a file with the paperclip, then run any command marked "needs file".
- Terminal tab: type any command. Type `help` for the full list.

## Limits
- Only the adb and fastboot protocols (no Samsung Odin, Qualcomm EDL or MTK modes).
- Images larger than the bootloader's max-download-size must be sparse images.
- It controls another phone over OTG, not the phone it is installed on.
