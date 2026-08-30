# Online input and invitation recovery integration

## Status

The recovery work originated in PR #96, commit
`bc927282c562dff8a1501770773fad3b77d21c8d`, against `b5a4d507`. On
2026-08-30 its still-unique behavior was adapted into the combined
`feature/reference-pages-v9` candidate intended for PR #99.

This is not a verbatim cherry-pick. The current Social/Profile reference UI and
the newer connection contract from PR #100 own the overlapping presentation and
authentication behavior.

## Included behavior

- `GameFieldInputState` owns digit entry and Backspace across local and online
  play. Automatically confirmed positions do not consume keypad presses, and
  turning off automatic deduction also turns off hidden inferred substitutions.
- A pending outgoing invitation is stored by Platform `gamePlayerId`, validated
  against the server invite alphabet, and restored after Activity/process or
  Social screen recreation.
- Waiting, matched, expired, locally cancelled, offline and temporarily
  unavailable states update the local recovery pointer without treating it as
  authorization or adding a nonexistent server-side cancellation API.
- Accepting an incoming invite takes precedence over restoring an older active
  session during the same screen initialization.
- Recovery intents are route-scoped: a retained owner invitation cannot hijack
  a new quick match or a challenge to another friend, and retry repeats the
  failed incoming-accept operation before considering durable recovery pointers.
- A profile-scoped pending code that appears after asynchronous identity
  hydration launches one invitation recovery attempt instead of leaving Social
  on its root screen.
- Android Social notifications open the Social section without accepting an
  invitation automatically.
- A confirmed switch to another server profile clears the old active-session
  route and stale incoming list. Device-local Google sign-out still preserves
  the Mirkori account and active online session, as required by PR #100.

The current reference invitation callbacks were adapted explicitly: creation
persists the code, retry restores it, and both legacy and illustrated cancel
controls clear the local pointer.

## Deliberately excluded source behavior

PR #96 also allowed a linked LOCAL/TELEGRAM session to initiate Google linking.
That policy is not copied into the combined candidate. The newer PR #100
contract and its tests limit the client action to guest or already Google-linked
states; server/profile ownership is never inferred from email or local progress.
Changing this policy again requires a separate owner decision and canonical
contract update.

## Evidence

The source PR had green CI and focused tests for profile-isolated persistence,
screen recreation after HTTP 503, confirmed-position input and a mixed-principal
two-client Ktor duel. The combined candidate keeps those tests and adds route
isolation, late identity hydration, exact incoming-invite retry and a delayed
post-accept session-read regression.

On the integrated tree, all of the following passed on 2026-08-30:

- `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` and
  `:app:assembleDebugAndroidTest`;
- the four focused recovery instrumentation tests on Galaxy S24+ API 35;
- the complete instrumentation suite, `OK (94 tests)`, on OnePlus 9 Pro API 33
  and Galaxy S24+ API 35;
- a final read-only P0/P1 review of the route and coroutine ordering.

The current APKs were installed with replacement semantics and without clearing
application data. Evidence logs are stored under
`build/visual-qa/reference-v11-device-captures/`.

| Artifact | SHA-256 |
| --- | --- |
| `app-debug.apk` | `0A2D513140C295037765E6B0D7CD209E91B5BF79D828BB6BA7D2F377F63603ED` |
| `app-debug-androidTest.apk` | `2964B40C3130463B94506A86CCEE53DEDAEDEEB0359E8FAAB7987BFDE39AAF56` |

Remote CI remains separate evidence and is recorded only after the combined
branch is pushed; source-branch results are not reused as proof of PR #99.

## PR lifecycle

PR #99 becomes the combined PR against `develop`. After the integrated commit is
pushed and its CI is green, PR #96 can be closed as superseded. This report does
not authorize merge, release or production changes.
