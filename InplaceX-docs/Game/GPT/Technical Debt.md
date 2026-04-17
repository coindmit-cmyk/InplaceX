# Technical Debt

## Known Gaps

- physical Gradle module split is not done yet
- some UI still uses legacy match models
- `GameFieldScreen` still contains local game orchestration and should later be aligned with canonical match contracts
- strings are only partially centralized
- platform config is still static code, not external assets/resources

## Why This Is Acceptable Now

This iteration establishes canonical contracts and documentation without pretending the refactor is finished.
