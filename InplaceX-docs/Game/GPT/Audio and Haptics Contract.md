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
creative assets. The looping `MediaPlayer` music channel is implemented but no
music resource is bound in this package. Adding final music requires only a
resource ID at Android composition time and must not change game screens.

## Safety

The runtime releases `ToneGenerator` and `MediaPlayer` with the Activity,
pauses music while backgrounded, and never logs media contents or user state.
