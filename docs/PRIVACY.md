# AURA Launcher privacy policy

_Last updated: November 18, 2026_

AURA Launcher ("AURA") is a home-screen app for Android. This policy explains what AURA does with information on your phone. In short:
- AURA has no servers and no accounts of its own.
- AURA shows no ads, has no analytics, and does not sell or share your data.

## What stays on your phone
These never leave your device:
- **Your apps and how you use them.** AURA reads your installed apps to show them in the app drawer. It keeps its own record of when you open apps, and with your permission (Usage access) reads Android's usage history, to suggest apps. This list and history are never sent anywhere, including to Gemini.
- **Settings, favorites, hidden apps, widgets and themes.**
- **Contacts.** When you ask Gemini to call or text someone, or search for a person in the app drawer, AURA looks the name up in your contacts on the phone. Your contact list is not sent anywhere.
- **Clipboard.** AURA reads the clipboard only when you tap **Paste my key**, to fill in your Gemini key.
- **Voice.** Talking to Gemini uses Android's built-in speech recognition, which works on the device when it can. AURA receives only the resulting text.
- **Accessibility service ("AURA gestures"), if you turn it on.** It only opens the notification shade or Quick Settings when you swipe down on AURA's home screen. It cannot read the screen and collects nothing.

## What is sent, and to whom
| When | What is sent | To |
|---|---|---|
| You use Gemini **on this phone** (Gemini Nano) | Nothing. Questions are answered on the device, and AURA carries out commands itself | Nobody |
| You use Gemini with a **free key** or **Google sign-in** | Your message, earlier messages in the same chat, and results of phone actions Gemini asked for (for example "battery 80%") | Google's Gemini API, using your own key or your Google account |
| You set up a free key | The key, to check it works | Google's Gemini API |
| You sign in with Google | A sign-in request; Google returns your email address, which AURA shows in the app | Google |
| Weather is on | Your approximate location, rounded to about 1 km | Open-Meteo (open-meteo.com) |
| You open the news feed | Requests for public news feeds and their pictures | BBC, The New York Times, The Guardian, Ars Technica, The Verge, or Google News |
| You report a Gemini reply | The reply, the reason you chose, the model name and AURA's version (no account and no message from you) | The developer |

Every connection uses HTTPS.

Gemini requests are covered by Google's terms and privacy policy for the Gemini API. They are not stored by AURA's developer. A new chat starts each time you close and reopen Gemini, and AURA does not keep old conversations.

## Permissions
Android asks for each permission when it's first needed, and you can turn any of them off in Settings → Apps → AURA. AURA keeps working without them.
- **Location (approximate):** local weather.
- **Contacts:** finding who to call or text when you ask Gemini.
- **Phone:** placing calls you ask for. Without it, the dialer opens instead.
- **Microphone:** talking to Gemini.
- **Usage access** (optional): better app suggestions.
- **Accessibility** (optional): swipe-down gestures, as described above.

## Backups
Android may back up AURA's settings with your phone's backup. Your Gemini API key and Google sign-in are excluded from cloud backups.

## Children
AURA is not directed at children under 13.

## Changes and contact
Changes to this policy will be posted here with a new date. For questions, open an issue at https://github.com/SKILLEDxKILLAH/AURA-Launcher/issues.
