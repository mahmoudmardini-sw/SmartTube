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
| `common/.../playback/controllers/VideoLoaderController.java` | this fork gates `onNextClicked()` behind family control; upstream keeps moving code (e.g. the sleep timer) out of this class |
| `MediaServiceCore`, `SharedModules` | submodule pointers — see below |
| `smarttubetv/build.gradle` | version numbers; keep upstream's (see “Version policy”) |

After resolving, grep for leftovers of any code upstream relocated
(`grep -rn "mSleepTimerStartMs" common/src` was the check for the 32.47 merge) — a conflict-free
merge can still leave a stale copy of moved code.

## Submodules

Both submodules point at forks under `mahmoudmardini-sw` because they carry two local fixes that
upstream still does not have:

| Submodule | Fix |
| --------- | --- |
| `MediaServiceCore` | `YouTubeAccount.equals` treats a matching email as the same account, so re-fetched accounts merge instead of duplicating (this is the "konto reset" duplicate-account bug) |
| `SharedModules` | `SharedPreferencesBase.getData` writes the migrated file before clearing the `SharedPreferences` entry, so an account reset can no longer lose data |

They are stored as commits on `master` in the forks, so a fresh clone gets them via
`git submodule update --init`.

**After an upstream submodule bump** (the app release pins new submodule commits), re-apply the
fixes on top of the new pin:

```bash
# SharedModules
git -C SharedModules fetch origin
git -C SharedModules checkout <new pinned commit>
git -C SharedModules cherry-pick <the MOD: prefs migration commit>
git -C SharedModules push fork HEAD:master

# MediaServiceCore (its fix also carries a nested-submodule pointer change — keep upstream's)
git -C MediaServiceCore fetch origin
git -C MediaServiceCore checkout <new pinned commit>
git -C MediaServiceCore cherry-pick <the MOD: account equality commit>
git -C MediaServiceCore push fork HEAD:master

# record the new pointers
git add MediaServiceCore SharedModules && git commit
```

Find the fix commits with `git -C <submodule> log --grep="^MOD:"`.

Note: the `MediaServiceCore` pin currently also includes one upstream commit that is newer than
the app release (the fix that stopped printing OAuth refresh tokens to logcat). To stay exactly on
the release, reset the submodule to the commit the release tag pins, then re-apply the fix.

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
