# Google Play store listing

Everything for **Play Console → Grow users → Store presence → Main store listing** (en-US).

## App name (30 max)
```
AURA
```

## Short description (76/80)
```
A calm, minimalist home screen with Gemini built in. No Google Cloud needed.
```

## Full description (2261/4000)
```
AURA is a calm, minimalist home screen for Android, with Gemini built right in.

GEMINI, RIGHT ON YOUR HOME SCREEN
Tap the Gemini card and ask anything, or tell your phone what to do: "set a timer for 10 minutes", "wake me up at 6:30", "call Mom", "text Sam I'm running late", "navigate home", "turn on the flashlight", "play some jazz". Replies appear as they're written, and every reply can be reported.

NO GOOGLE CLOUD SETUP
Choose how Gemini runs the first time you open it:
• On your phone: on phones that support Gemini Nano, Gemini runs on the device. No account, no key, and nothing you ask leaves the phone.
• Free Gemini key: open Google AI Studio, tap Create API key, copy it, and paste it into AURA. Takes about a minute.
• Continue with Google: for testers the developer has added.

AN APP DRAWER THAT'S A PLEASURE TO USE
• Swipe left for every app, A to Z, with a big letter that shrinks as you scroll.
• Slide along the letters and they curve toward your finger; slide left to pick an app without lifting.
• Suggested and Recent apps, learned on your phone from when you use them.
• List, compact or grid layouts.
• Hold any app for its shortcuts, favorites, hiding, App info and Uninstall.
• Search finds apps, settings and contacts, and answers sums like "15% of 80".

MAKE IT YOURS
• Widgets: press and hold the home screen and choose Widgets.
• Light, dark or match your phone, in AURA's sand colors or your wallpaper's colors.
• Themed icons, your own dock apps, and a custom line at the top of the home screen (or the weather).
• Use AURA's dune backdrop or your own wallpaper.

NEWS AND GESTURES
• Swipe right for a news feed with photos, today's weather and a one-tap Gemini briefing.
• Swipe down for notifications, or with two fingers for Quick Settings.

PRIVACY
AURA has no ads, no analytics and no accounts of its own. Your list of apps, how you use them and your contacts stay on your phone. Swipe-down gestures use an optional Accessibility service that only opens the notification shade and Quick Settings, and it reads nothing on screen. You'll see a clear explanation before you turn it on.

Requires Android 17.

AURA is an independent app and is not affiliated with or endorsed by Google. Gemini is a trademark of Google LLC.
```

## Graphics
| Field | File | Size |
|---|---|---|
| App icon | `icon_512.png` | 512×512 PNG |
| Feature graphic | `feature_graphic.png` | 1024×500 PNG |
| Phone screenshots | `phone/1_home.png` … `phone/8_news.png` (upload all 8, in order) | 1080×1920 (9:16) |

Foldable, tablet, desktop and Android XR screenshots and the video are optional. Leave them empty.

The icon is AURA's own mark: a glowing aura ring over sand dunes, the same as the app's launcher icon from 1.2.2 on. It was changed from a four-point sparkle, which looked too much like Google's Gemini logo for Play's impersonation policy.

## How these were made
- Screens are rendered from the real app by `StoreAssetsTest`, with generic placeholder apps and icons and no other brands.
- `tools/store_frames.py` adds the captions.
- The icon and feature graphic are rendered from the app's own launcher icon.

To regenerate:
```bash
./gradlew :app:testDebugUnitTest --tests '*StoreAssetsTest'
python3 tools/store_frames.py   # output: app/build/store/
```
