# Audio and Haptics Contract

## Ownership

Android presentation emits typed feedback cues and does not reference concrete
audio files. `AppFeedbackRuntime` owns sound effects, vibration, foreground
lifecycle, music looping, and resource release. Game rules and shared core do
not depend on Android audio APIs.

## User settings

Vibration, sound effects, and music are independent switches persisted in
private application preferences. All default to enabled. A disabled channel
must stop producing new output immediately; backgrounding the Activity pauses
music, and foregrounding resumes it only when music remains enabled.

## Current asset stage

Short `ToneGenerator` cues temporarily represent tap, confirmation, success,
failure, and invitation events. They are replaceable test sounds, not final
creative assets. The looping `MediaPlayer` music channel is implemented, but no music asset is bound in this package. Sound effects and haptics remain independent of music availability.

The music row and switch remain visible but disabled while no music asset is bound, with a localized explanation. Preserve the saved music preference (`true` or `false`) unchanged. Future approved resource binding must also explicitly re-enable the UI and update its explanatory copy; game screens remain decoupled.

## Safety

The runtime releases `ToneGenerator` and `MediaPlayer` with the Activity,
pauses music while backgrounded, and never logs media contents or user state.
