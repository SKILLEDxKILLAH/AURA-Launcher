# Setting up Gemini in AURA

AURA runs Google Gemini **inside the launcher**. You type or talk, and Gemini answers and can act on your phone: flashlight, alarms, timers, calls, messages, navigation, apps, settings, media and calendar. It never opens the separate Gemini app.

The first time you open Gemini, AURA asks how you'd like it to run. **None of the options needs Google Cloud.**

| | **On this phone** | **Free Gemini key** | **Continue with Google** |
|---|---|---|---|
| Setup | One tap | About a minute in AI Studio | Developer's Google Cloud setup (Option 3) |
| Account or key | None | Your own free key | Your Google account |
| Where Gemini runs | On the phone (Gemini Nano) | Google's servers | Google's servers |
| Answers | Shorter | Full Gemini | Full Gemini |
| Phone actions | Everyday ones, handled by AURA | Everything Gemini can do | Everything Gemini can do |
| Available | Phones that support Gemini Nano | Everyone | Accounts the developer added |

Change it any time in **Settings → Gemini → How Gemini runs**.

None of these use a Gemini Advanced / Google One AI subscription. Google doesn't let other apps use those.

---

## Option 1: On this phone (Gemini Nano)
If your phone supports Gemini Nano (recent Pixels and some other flagships), the first option is **On this phone**:
1. Tap **Use Gemini on this phone**, or **Download and use** if the model isn't on the phone yet. Wi-Fi is best for the download.
2. That's it. Nothing you ask leaves the phone, and no account or key is involved.

How it works:
- **Everyday commands** are understood by AURA itself and done right away. For example: "flashlight on", "set a timer for 10 minutes", "wake me up at 6:30", "open Spotify", "call Mom", "text Sam saying I'm late", "navigate home", "play jazz", "turn on Bluetooth" and "how much battery do I have".
- **Questions** are answered by Gemini Nano. Answers are shorter than cloud Gemini, and it can't look things up on the web.
- If your phone doesn't support Gemini Nano, this option isn't shown.

## Option 2: Free Gemini key (recommended for most people)
Google AI Studio gives anyone a free Gemini key. It creates everything it needs behind the scenes, so you never open the Google Cloud console and don't need a card.
1. In AURA's Gemini panel, tap **Open Google AI Studio** (or go to https://aistudio.google.com/apikey).
2. Sign in with your Google account and accept the terms if asked.
3. Tap **Create API key**, then **Copy**.
4. Go back to AURA and tap **Paste my key**.

AURA checks the key with Google straight away, and you're done. The key stays on your phone and is left out of cloud backups.

The free tier has daily limits that Google sets per model. If you hit one, pick **Fastest** in the model panel or wait a bit.

---

## Option 3: Continue with Google (for the developer and testers)

This one does need Google Cloud, but only **once, by whoever publishes AURA**. People using a copy signed with the same key then just tap **Continue with Google**. While the project is in Testing, only accounts listed under **Test users** can sign in; everyone else sees "Access blocked".

### 1. Create a project and turn on the Gemini API
1. Open **https://console.cloud.google.com** and sign in with the Google account you'll use in AURA.
2. Use the project picker at the top to choose **New project**. Name it (for example `AURA`) and click **Create**. Make sure the new project is selected.
3. Open **APIs & Services → Library**, search for **Generative Language API**, and click **Enable**.

### 2. Set up the consent screen
1. Open **Google Auth Platform**. The first time, it walks you through **Branding**: enter an app name (for example `AURA`) and your email as the support email, then save.
2. Go to **Audience**:
   - **User type:** **External**. ("Internal" only works for Google Workspace organizations, not regular Gmail.)
   - **Publishing status:** leave it on **Testing**.
   - **Test users:** click **+ Add users** and add **every Gmail address that will sign in to AURA**, including your own. Then **Save**.

### 3. Register AURA as an Android app
1. Go to **Google Auth Platform → Clients → + Create client**.
2. **Application type:** **Android**.
3. **Package name:** `com.aura.launcher`
4. **SHA-1 certificate fingerprint:** the fingerprint of the key that signed the AURA you installed:
   - For the APK from the **v1.0.0 GitHub release**, use
     `21:5E:10:55:71:4E:D8:77:73:F0:3C:85:73:0C:10:1A:FE:B4:96:D6`
   - In general, open AURA → **Settings → Google setup (one time, for the developer)** and tap the **SHA-1** row to copy the exact value for your installed copy.
5. Click **Create**. You don't need to download anything.

> If you build AURA yourself with a different signing key, it has a different SHA-1. Add another Android client with that SHA-1; a project can have several.

### 4. Sign in on your phone
1. Tap the Gemini card on the home screen.
2. Tap **Continue with Google** and choose your account.
3. Google shows **"Google hasn't verified this app."** That's expected for a personal project in Testing. Tap **Continue**.
4. Review the access AURA asks for and tap **Allow**.

You're signed in when your email appears under "Gemini" in the panel.

**What AURA asks for, and why:**
- `generative-language.retriever` is Google's permission for using the Gemini API with your own account.
- `userinfo.email` is used only to show which account is signed in.
- If Google ever says that isn't enough, AURA asks once more for `cloud-platform`, the broader permission in Google's command-line instructions.

You can sign out at any time in **Settings → Gemini → Sign out**. That revokes AURA's access.

---

## Choosing a model and thinking level
In the Gemini panel, tap **Change** on the bar under your email:
- **Best / Balanced / Fastest**: Gemini Pro Latest, Flash Latest and Flash-Lite Latest. These always point at Google's current versions.
- **All models**: every chat model your account can use, fetched live from Google.
- **Thinking:** Auto, Minimal, Low, Medium or High.
  - Higher levels give better answers on hard requests, but they're slower.
  - If a model doesn't support the level you picked, AURA uses the closest one it does and shows a short note.

## Check that everything works
Open the model panel and tap **Run Gemini check**, or go to **Settings → Gemini → Run Gemini check**. It tests, step by step:
1. Sign-in.
2. Getting an access token.
3. Loading your models.
4. A real reply.
5. The thinking level actually used.
6. Whether Gemini correctly chooses a phone action. Nothing is actually done on your phone.
7. Permissions.

With **On this phone**, the check instead confirms Gemini Nano is ready, gets a real on-device reply, and tests that AURA understands a sample command.

Tap **Copy report** to share the results. The report contains no passwords, tokens or keys.

## Phone permissions
Android asks for each permission the first time Gemini needs it. You can also grant them all at once in **Settings → Gemini → Grant phone permissions**:

| Permission | Used for |
|---|---|
| Contacts | Finding who to call or message |
| Phone | Placing calls you ask for. Without it, AURA opens the dialer instead. |
| Microphone | Talking to Gemini |
| Location (approximate) | Weather |

Messages and calendar events always open as **drafts for you to confirm**. Gemini never sends a text on its own.

---

## Troubleshooting

**"Access blocked: AURA has not completed the Google verification process" (Error 403: access_denied)**
The account you chose isn't a test user. Add that exact Gmail under **Google Auth Platform → Audience → Test users**, wait a minute, and try again. Also check that you're editing the same project that holds the Android client.

**"Google sign-in isn't set up for this build yet"**
Google doesn't recognize this copy of AURA. The Android client's **package name** (`com.aura.launcher`) or **SHA-1** doesn't match. Copy the SHA-1 from AURA's **Settings → Google setup** and add it as an Android client.

**"The Gemini API isn't enabled in your Google Cloud project yet"**
Enable **Generative Language API** in that project (Option 3, step 1.3). With a free key from AI Studio this is already done for you.

**"Gemini is busy or your quota is used up"**
You've hit the project's rate or free-tier limit. Wait a moment, choose a lighter model (Fastest), or enable billing on the project.

**"That Gemini model isn't available to your account"**
Pick a different model in the model panel. "Balanced" is a safe default.

**"Your Google sign-in expired — sign in again" or "Please sign in with Google again"**
Access was revoked or expired. Tap **Continue with Google** again.

**"Google hasn't verified this app" every time**
This is normal while the project is in Testing. Don't switch to "In production": that requires Google's formal app verification for these permissions.

Still stuck? Run the **Gemini check**, tap **Copy report**, and include it when you ask for help.
