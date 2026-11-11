# SmartTube — Arabic-first fork

Fork of [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube) (Android TV YouTube client).

What this fork adds on top of upstream:

- **Family control** — PIN-protected restrictions per account: hidden sections/search/account
  switcher, limited player buttons, daily and per-channel watch-time limits, an app access
  schedule, and a parent-granted temporary pause.
- **Arabic-first UI** — Arabic strings for everything the fork adds, RTL-correct PIN entry and
  layouts.
- **Restyled settings UI** — floating rounded panels, accent-tinted focus highlights that follow
  the selected color scheme.
- **Self-updating disabled** — upstream update manifests are removed; releases and sources point
  at this fork (upstream APKs are signed with a different key and cannot replace this build).

## Remotes

| Remote     | URL                                              |
| ---------- | ------------------------------------------------ |
| `origin`   | https://github.com/mahmoudmardini-sw/SmartTube    |
| `yuliskov` | https://github.com/yuliskov/SmartTube (upstream)  |

## Merging an upstream release

```bash
git fetch yuliskov --tags
git log --oneline -5 $(git describe --tags --abbrev=0)   # sanity-check the newest tag
git checkout -b merge/<version> master
git merge <tag>                                          # e.g. 32.47s
```

Then work through these spots, which are the ones that have conflicted before:

| Path | Why |
| ---- | --- |
| `.gitignore` | this fork adds `.zcode/`, upstream keeps adding its own entries |
| `common/.../playback/controllers/ErrorFixerController.java` | fork's SABR client-fallback budget + terminal state vs upstream's `SabrReloadPlayerError`/`SabrBackoffError` actions. Keep both; reset `mSabrExhausted` in `onNewVideo`, not `onPlay` |
| `common/.../playback/controllers/VideoLoaderController.java` | this fork gates `onNextClicked()` behind family control and tracks `mIsSabrActive`; upstream keeps moving code (sleep timer, live SABR) out of this class. Re-apply `mIsSabrActive` to the live-SABR branch |
| `common/.../settings/GeneralSettingsPresenter.java` | fork removed child mode, settings/master password and update-check options; upstream keeps reorganizing categories (network settings moved into a single sub-dialog in 32.70+) |
| `smarttubetv/.../dialogs/AppPreferenceManager.java` | upstream switched single switches to `CheckBoxPreference`; fork adds `TYPE_HEADER` + accent color |
| `common/src/main/res/values-ar/strings.xml` | take upstream's file, then re-add the fork-only Arabic keys (the list is whatever is missing from `values/strings.xml`) |
| `MediaServiceCore`, `SharedModules` | submodule pointers — see below |
| `smarttubetv/build.gradle` | version numbers; keep upstream's (see “Version policy”) |

After resolving, grep for leftovers of any code upstream relocated
(`grep -rn "mSleepTimerStartMs" common/src` was the check for the 32.47 merge) — a conflict-free
merge can still leave a stale copy of moved code.

## Submodules

Both submodules point at forks under `mahmoudmardini-sw` because they carry local fixes that
upstream still does not have:

| Submodule | Fix |
| --------- | --- |
| `MediaServiceCore` | `YouTubeAccount.equals` treats a matching email as the same account, so re-fetched accounts merge instead of duplicating (the "konto reset" duplicate-account bug); plus scoped equality with `hashCode` and walking the client list on repeated switches |
| `SharedModules` | `SharedPreferencesBase.getData` writes the migrated file before clearing the `SharedPreferences` entry, so an account reset can no longer lose data; plus atomic file writes, exact change detection and a `parseLong` overflow guard |

They are stored on the `fix/hardening-<version>` branches in the forks (the app release pins the
exact commits). A fresh clone needs `git submodule update --init` after the branch is pushed.

**After an upstream submodule bump** (the app release pins new submodule commits), re-apply the
fixes on top of the new pin:

```bash
# SharedModules
git -C SharedModules fetch origin
git -C SharedModules switch -c fix/hardening-<version> <new pinned commit>
git -C SharedModules cherry-pick <MOD: prefs migration commit> <hardening commit>
git -C SharedModules push fork fix/hardening-<version>

# MediaServiceCore (its fix also carries a nested-submodule pointer change)
git -C MediaServiceCore fetch origin
git -C MediaServiceCore switch -c fix/hardening-<version> <new pinned commit>
git -C MediaServiceCore cherry-pick <MOD: account equality commit> <hardening commit>
# VideoInfoService was renamed upstream (VideoInfoType -> Client, mActualClient/mNextClient,
# CLIENT_LIST, switchNextClientInt). Adapt the hardening commit, then:
git -C MediaServiceCore/SharedModules fetch "C:\...\SmartTube\SharedModules" fix/hardening-<version>
git -C MediaServiceCore/SharedModules checkout <new SharedModules commit>
git -C MediaServiceCore add SharedModules && git -C MediaServiceCore commit -m "chore: update SharedModules"
git -C MediaServiceCore push fork fix/hardening-<version>

# record the new pointers in the superproject
git add MediaServiceCore SharedModules && git commit
```

Find the fix commits with `git -C <submodule> log --grep="^MOD:"` and `--grep="Hardening"`.
The 32.72 merge used upstream pins `MediaServiceCore 4be5193d` and `SharedModules 5b6b5d1d`.

## Version policy

`smarttubetv/build.gradle` keeps upstream's `versionCode`/`versionName` unchanged. That keeps the
version line conflict-free on every merge and lets an install upgrade in place. `versionCode` must
never go down, so never reuse an older number for a new build.

## Building

```bash
./gradlew :smarttubetv:assembleStstableDebug     # debug (unsigned)
./gradlew :smarttubetv:assembleStstableRelease   # release, signed only if keystore.properties exists
```

Release builds are signed from `keystore.properties` in the repo root (not committed):

```properties
storeFile=../path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file `assembleStstableRelease` produces an unsigned APK. APKs land in
`smarttubetv/build/outputs/apk/<flavor>/<buildType>/` as `SmartTube_<flavor>_<versionName>_<arch>.apk`.

## Preference file formats

`FamilyControlData`, `MasterPasswordData` and `TimeLimitData` each write a `v2` marker as the first
field of their stored data and read fields through an offset, so data written by older builds keeps
loading. **When adding a field, keep the marker first and document the field order** in the class —
the previous layout relied on positional indices only and was easy to break.

The PIN is stored as a salted, iterated hash (`pbkdf2$<iterations>$<salt>$<hash>`, see
`Utils.hashPin`). Older values (unsalted SHA-256 or plaintext) keep working and are upgraded to the
current format on the next successful PIN entry. Five wrong PINs lock further attempts for a minute.
New PINs must be at least 4 digits and not trivial (`MasterPasswordData.isPinAcceptable`).
Security state (PIN hash, lockout counter, enable/disable/pause) is written synchronously, so a
force-kill can't roll it back; the lockout uses the monotonic clock.

## Self-update

Currently **fully disabled**: `update_urls` arrays are empty in every flavor, there is no
automatic check on startup (`SplashPresenter`), and no update UI (About screens, player menus).
`AppUpdatePresenter.start()` is a guarded no-op, kept alive (not deleted) for the future.

To enable automatic updates from this fork's own releases:

1. Sign every distributed build with the **same** key (`keystore.properties`) — otherwise the
   update can't install over the existing app.
2. Publish a manifest JSON as a release asset, e.g. `smarttube_stable.json`:
   ```json
   {
     "package": { "downloadUrl": "https://github.com/mahmoudmardini-sw/SmartTube/releases/download/<tag>/SmartTube_stable_<ver>_arm64-v8a.apk" },
     "versionName": { "versionCode": 2447, "changelog": ["..."] }
   }
   ```
   (`AppVersionChecker` compares `versionCode` against the installed build.)
3. Point `common/src/ststable/res/values/update_urls.xml` (and `stbeta` for beta) at
   `https://github.com/mahmoudmardini-sw/SmartTube/releases/download/latest/smarttube_stable.json`.
4. Re-enable the call sites: the `SplashPresenter` startup check, the About screens'
   check buttons/auto-update switches, and `BaseMenuPresenter.appendUpdateCheckButton`.
   (`MENU_ITEM_UPDATE_CHECK = 1<<23` is still reserved in persisted profiles.)

## Security hardening (2026-10)

Batch review fixed: PIN change requires the current PIN + confirmation; account passwords are
hashed at rest (no universal backdoor); `parseLong` overflow guard; atomic file writes with
profile-key snapshotting; `YouTubeAccount` email equality scoped to page/channel + `hashCode`;
SABR fallback budget resets per video with client rotation and a terminal no-retry state;
family-control Next goes through the `onNextClicked` chain (incl. remote control).

## Merge history

| Branch | Upstream base | Notes |
| ------ | ------------- | ----- |
| `merge/32.47s` | `32.47s` | first fork merge |
| `merge/32.56s` | `32.56s` | self-update disabled |
| `merge/32.72` | `yuliskov/master` @ `32.72` (versionCode 2462) | SABR live support, DIAL casting, add-to-queue intent, settings reorg; submodules re-pinned to `MediaServiceCore 4be5193d` / `SharedModules 5b6b5d1d` |

