# EuWiFy: Google Play Console texts and answers

Copy and paste from here into the Play Console. Limits are noted in brackets.

---

## 1. App details

- **App name** [30]: `EuWiFy: Home & Away by Wi-Fi`
- **Default language:** English (United States)
- **App or game:** App
- **Free or paid:** Free
- **Category:** House & Home
- **Contact email:** iletisim@tayfunmutlu.com.tr
- **Website** (optional): the GitHub page, once it is public
- **Privacy policy URL:** `https://github.com/n0br4n/euwify/blob/main/PRIVACY.md`
  (it only works after the repository is public; or put PRIVACY.md on your own website)

## 2. Store listing

**Short description** [80]:

```
Switches your Eufy camera to Home or Away automatically, based on your Wi-Fi.
```

**Full description** [4000]:

```
EuWiFy switches your Eufy security camera between Home and Away mode for you, based on the Wi-Fi network your phone is connected to.

• Arrive home: as soon as your phone joins your home Wi-Fi, the camera switches to Home.
• Leave home: when your phone has been away from your home Wi-Fi for 75 seconds, the camera switches to Away.
• Confirmed: every change is checked with the camera itself, and you get a notification such as "Bedroom → AWAY ✓".
• Reliable: runs quietly in the background, retries if the camera is briefly unreachable, and starts again after your phone restarts.
• More than one home network: add every Wi-Fi that counts as "home".
• Manual buttons: switch to Home or Away by hand at any time.
• Recent activity: see exactly what happened and when.

PRIVACY FIRST
• Your Eufy login is stored only on your phone, encrypted with the Android Keystore, and is only sent to Eufy.
• The Wi-Fi name is read on your phone and never sent anywhere. GPS is not used.
• No ads, no analytics, no tracking, no account with us.

WHY LOCATION PERMISSION?
Android only tells apps the name of your Wi-Fi network if they have location permission. EuWiFy needs "Allow all the time" so it can notice when you leave or arrive while the app is closed. It does not use or store your location.

REQUIREMENTS
• An Eufy camera and your main Eufy account (guest accounts cannot change the guard mode).
• Tested with the Eufy Indoor Cam Pan & Tilt 2K (T8410) without a HomeBase. Other models may work.

IMPORTANT
EuWiFy is an independent app. It is not made by, endorsed by or affiliated with Anker or Eufy. It uses an unofficial interface, so a change by Eufy could stop it from working.

Free and open source. If it helps you, you can support development with a tip; nothing in the app is locked.
```

**Graphics** (files in the `design` folder):
- App icon: `play-icon-512.png`
- Feature graphic: `feature-graphic.png`
- Phone screenshots: at least 2 (real screenshots from your phone)

## 3. App content (Policy → App content)

### Privacy policy
The URL from section 1.

### Ads
"No, my app does not contain ads."

### App access
Choose **"All or some functionality is restricted"** and add instructions:

```
EuWiFy controls the user's own Eufy security camera, so it needs a real Eufy account that owns a camera. We cannot share such an account, because it would give access to a private camera in a private home.

Without signing in you can see: the setup checklist, the permission explanations, the home Wi-Fi list (you can add and remove networks), and the sign-in screen.

A video showing the full flow (sign in, choose camera, add home Wi-Fi, leave home → Away, come back → Home) is available here: <VIDEO-LINK>
```

(Google may still ask for a login. Ask Claude about a "demo mode" if the review asks for one.)

### Content rating (questionnaire)
- Category: **All other app types** (utility)
- Violence, sexual content, language, controlled substances, gambling: **No** to all
- Users can interact or exchange content: **No**
- Shares user's location with other users: **No**
- Allows purchases of digital goods: **No**
- Expected result: rated for everyone (PEGI 3 / Everyone)

### Target audience
- Age groups: **18 and over** (simplest: the app needs an Eufy account and controls a security camera)
- Appeals to children: **No**

### News app: No
### Government app: No
### Financial features: none
### Health: none

### Data safety
**Does your app collect or share any of the required user data types?** Yes

**Is all of the user data collected by your app encrypted in transit?** Yes

**Do you provide a way for users to request that their data is deleted?** Yes. In the app, "Sign out and delete login from this phone" (or uninstall). The developer stores no data.

Data types (everything else: not collected):

| Data type | Collected | Shared | Optional? | Purposes |
|---|---|---|---|---|
| Personal info → Email address | Yes (sent to Eufy to sign in) | No* | Required | App functionality, Account management |
| Device or other IDs | Yes (a random app ID that Eufy's sign-in requires) | No* | Required | App functionality |

\* Sent only to Eufy, at the user's request, to use their own Eufy account. Google counts a transfer that the user starts as **not** "sharing".

Notes:
- **Location: not collected.** The Wi-Fi name never leaves the phone, and GPS is not used.
- The password is sent only to Eufy. Google's form has no separate "password" type; it is covered by "Account management".
- Processed ephemerally: No.

### Permissions declarations

**Background location** (Play Console asks this because of ACCESS_BACKGROUND_LOCATION):

- Core feature that uses it:
```
EuWiFy switches the user's security camera to Home when the phone joins one of the user's home Wi-Fi networks, and to Away when the phone has left them. Android only reveals the name (SSID) of the connected Wi-Fi network to apps with location permission. To notice arriving and leaving while the app is closed (the whole purpose of the app), the Wi-Fi name must be readable in the background. The app does not use GPS and does not store, log remotely or transmit location or Wi-Fi names.
```
- Prominent disclosure: the in-app dialog "Location all the time" shown before the request.
- **Video** (required, 30-60 s, upload to YouTube as "Unlisted"): see the script below.

**Foreground service type: special use**:
```
The service keeps a connection to the user's own Eufy camera and watches for Wi-Fi changes, so the camera's guard mode can be switched within seconds when the user arrives or leaves home. A persistent notification shows the current mode. No other foreground service type fits: it is not location tracking (GPS), not data sync, and not media.
```

## 4. Video script (background location, about 45 seconds)

Record with the phone's built-in screen recorder.

1. Open EuWiFy. Show the setup card.
2. Tap "Allow location". Show the "Why location?" dialog, then Android's prompt.
3. Tap "Allow location all the time". Show the "Location all the time" dialog, then Android's settings page, and choose "Allow all the time".
4. Show the Home Wi-Fi card with your network added.
5. Close the app (swipe it away). Turn Wi-Fi off.
6. Wait for the notification "… → AWAY ✓" and show it.
7. Turn Wi-Fi on. Show the notification "… → HOME ✓".

Hide your email: start the recording after signing in, or blur the email later.

## 5. Testing before release (new personal developer accounts)

1. Testing → **Closed testing** → create a track, upload `app\release\app-release.aab`.
2. Add at least **12 testers** (their Google account emails, or a Google Group).
3. They must install the app from the test link and keep it for **14 days**.
4. Then Production → **Apply for production access**.
