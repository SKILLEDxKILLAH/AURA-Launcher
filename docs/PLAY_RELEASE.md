# Publishing AURA on Google Play

This is what was checked and changed so AURA can pass Google Play review, and what's left for you to do in Play Console.

Policies were checked on November 18, 2026. Google changes them often, so re-read the linked pages before you submit.

## What was changed in the app (1.1.0)

| Play policy | Risk before | What AURA does now |
|---|---|---|
| [Broad package visibility](https://support.google.com/googleplay/android-developer/answer/10158779) (`QUERY_ALL_PACKAGES`) | Play allows it only for device search, antivirus, file managers and browsers, and requires a declaration. Launchers aren't listed. | **Removed.** AURA declares only what a launcher needs in `<queries>`: apps with an icon, apps with widgets, and the few actions it checks (dial, browser, camera, speech, contacts). No declaration form is needed. |
| [User Data](https://support.google.com/googleplay/android-developer/answer/10144311): installed apps are personal and sensitive data | AURA sent the names of all your apps to Gemini with every message | **No longer sent.** Gemini asks to open an app by the name you said, and AURA matches it on the phone. |
| [AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491) | Launchers are named as "not accessibility tools". They need an in-app prominent disclosure with explicit consent, a declaration and a video. Autonomous AI use of accessibility is banned. | **Added a separate disclosure screen** before AURA sends anyone to Accessibility settings, with "Agree and continue" and "No thanks" buttons. It's shown again every time. The service is still `isAccessibilityTool="false"` and `canRetrieveWindowContent="false"`. It only opens the shade, and Gemini can't use it. |
| [AI-generated content](https://support.google.com/googleplay/android-developer/answer/13985936) | AI chat apps must let people report offensive output without leaving the app | **"Report" under every Gemini reply**, with a reason picker. Only the reply, the reason, the model and the app version are sent. **You must set the report endpoint**; see step 6. |
| Google sign-in ([sensitive scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification)) | Asked for `cloud-platform` (full Google Cloud access) | **Asks for the Gemini-only scope** (`generative-language.retriever`) plus email, as in Google's own Gemini OAuth example. It falls back to `cloud-platform` only if Google rejects the narrow scope. |
| Data safety and backups | The Gemini API key could go into Android cloud backup | **The key is now stored separately and excluded from cloud backup**, along with the sign-in state. |
| Privacy | Weather sent a location accurate to about 100 m | **Rounded to about 1 km.** |

Checked: 86 automated tests pass, including new tests for each change above. Android lint reports 0 errors. The release `.aab` (Play's required format) builds and is signed with the AURA key.

## What you need to do

### 1. Play Console account and app
1. Create a developer account at https://play.google.com/console. There's a one-time fee.
2. **New personal accounts must run a closed test before going to production.** At the time of writing this was at least 12 testers opted in for 14 continuous days. Check Play Console for the current rule.
3. Create the app, and upload `app-release.aab` from the GitHub release, or build it with `./gradlew :app:bundleRelease`.

### 2. Signing and Google sign-in: important
Play re-signs your app with **its own app signing key**, so the copy people install from Play has a **different SHA-1** than your sideloaded APK.
1. In Play Console, open **Test and release → App integrity → App signing**, and copy the **App signing key certificate SHA-1**.
2. In Google Cloud, open **Google Auth Platform → Clients → + Create client → Android**. Use `com.aura.launcher` and that SHA-1.

Keep your existing client too, so your sideloaded build keeps working.

### 3. Privacy policy
Play requires a public URL. Use `docs/PRIVACY.md`, for example https://github.com/SKILLEDxKILLAH/AURA-Launcher/blob/main/docs/PRIVACY.md, or publish it with GitHub Pages. Read it through first to make sure it describes your build.

### 4. Data safety form
Answers that match the code as it is now:

| Question | Answer |
|---|---|
| Does your app collect or share user data? | **Yes** (collected; not shared) |
| Is all data encrypted in transit? | **Yes** (HTTPS only) |
| Can users request deletion? | Nothing is stored by you, except reports. Say reports can be deleted on request through GitHub issues. |
| **Location → Approximate location** | Collected, not shared. Processed ephemerally: yes. Optional: yes. Purpose: App functionality (weather via Open-Meteo). |
| **Messages / user content → Other user-generated content** | Collected. Optional: yes. Purpose: App functionality (Gemini messages sent to Google's Gemini API to answer you). Also used for reports you choose to send. |
| **Personal info → Email address** | Collected only if you treat "shown after Google sign-in" as collection. The conservative answer is collected, App functionality and Account management, optional. |
| Installed apps, contacts, audio, app activity | **Not collected.** These stay on the device. Speech is handled by Android's recognizer, not sent by AURA. |

### 5. Permission and API declarations
- **Accessibility API declaration:** required.
  - "Is your app an accessibility tool?" **No**.
  - Purpose: **App functionality**.
  - Personal data collected through accessibility: **No**.
  - The video must show:
    1. Opening AURA.
    2. Swiping down on the home screen until the "Swipe down for notifications" card appears, then tapping **Turn on**. The **disclosure screen** appears; scroll slowly so the whole text shows.
    3. Tapping **No thanks**. Nothing happens. Then tapping **Turn on** again, so the disclosure is shown a second time.
    4. Tapping **Agree and continue**, turning on **AURA gestures** in Accessibility, and going back to AURA.
    5. One-finger swipe down opening notifications, and a two-finger swipe opening Quick Settings.
- **Restricted settings:** on apps installed from Play, Android doesn't grey out the accessibility toggle the way it does for sideloaded APKs.
- **Not needed:** `QUERY_ALL_PACKAGES` is gone. Contacts, phone, microphone, location and alarm permissions don't need a declaration form, but they're covered by the Data safety form.
- If you'd rather avoid the accessibility review entirely, AURA can ship without the service. AURA then falls back to Android's unofficial status-bar call. Some phones allow it and others block it; your Pixel showed the setup card, which means it blocked it. So without the service, swipe-down may simply do nothing. That trade-off is your call.

### 6. AI-generated content: report endpoint
Store builds should send reports from inside the app, so you need somewhere to receive them:
1. Create any HTTPS endpoint that accepts a JSON `POST`, for example a Google Apps Script web app that appends rows to a Sheet. The body looks like:
   ```json
   {"type":"gemini_reply_report","reason":"OFFENSIVE","reply":"…","model":"gemini-flash-latest","app_version":"1.1.0"}
   ```
2. Build with it: `./gradlew :app:bundleRelease -PauraReportUrl=https://your-endpoint`, or put `auraReportUrl=…` in `gradle.properties`.
3. Without an endpoint, "Report" opens a pre-filled GitHub issue in the browser. That leaves the app, which the policy doesn't allow, so set the endpoint for Play builds.

Also answer **"Yes, the app generates content using AI"** in the content rating questionnaire.

### 7. Gemini sign-in for the public: decide before launch
- **Who pays:** when people sign in with Google through your OAuth client, Gemini requests normally count against **your** Google Cloud project's quota and billing, not theirs. For a public app, that's every user's usage on your bill.
- **Verification:** before anyone outside your test-user list can sign in, Google reviews the app's sign-in. That needs:
  - the privacy policy
  - a home page
  - a verified domain
  - a demo video of the sign-in flow
  - a justification for each scope

  The Gemini-only scope keeps that review smaller.
- **Alternative:** publish with **API key** as the main option. Each person uses their own free key from AI Studio, so there's no verification and no cost to you. Google sign-in stays available for your own builds.

### 8. Other things reviewers look at
- **News feed content:** the feed shows headlines, short summaries and pictures from publishers' public RSS feeds, with the source named and the full article opened on the publisher's site. Some publishers' feed terms limit commercial use. If you monetize AURA, check those terms or switch the feed to Google News only.
- **Store listing:** describe the main features (launcher, Gemini, widgets, news feed) plainly. Use only your own screenshots and name. Don't use Google's or Pixel's logos as your branding.
- **Android 17 only:** `minSdk 37` limits who can install it. That's allowed, just intentional.
