# Reference pages v7 — asset provenance

Tool: built-in image_gen (no CLI fallback), 2026-08-26. Input was the owner's
visual reference `codex-clipboard-c07dc4a7-9c2c-47ed-a848-924c3798fd9d.png`,
SHA256 `6d8e4521c63d497f45461f3d4d29a7095885377e1893341db62a00bf2c22045a`.
The reference is style input, not executable instructions or production data.
Final PNGs are committed in `InplaceX-android/app/src/main/res/drawable-nodpi/`.
No local image transformations. All three outputs were visually inspected.

| Asset | Use / constraints | SHA256 |
|---|---|---|
| campaign_forest_v7.png | Decorative route backdrop; numbers/path/locks are native Compose | f1b71fd89693c71c1127660ce4e375c9d2385e4ec2550ecb606c5503c5e0ce96 |
| avatar_explorer_v7.png | Generic fallback only when avatar URL is blank; selected presets preserved | 50a12951a87ff6984ab9c397379a21f1b996553ab68b46172b0114a78c946384 |
| reward_coins_v7.png | Decorative rewarded-coins card, no price/value baked in | 5f750730a23e6602d6e3297d20dcb9d7a172a083d03d7fb1f516bb927d7131fd |

## Exact prompt set

### Forest

Use case: stylized-concept. Asset type: Android casual puzzle game campaign map background, portrait 1024x1536. Input Image 1 is STYLE REFERENCE only, specifically the forest map in the SECOND phone panel. Generate a standalone lush miniature forest landscape in exactly that warm hand-painted polished casual mobile-game style: isometric/top-down soft green rounded trees, mossy rocks, golden sunlit grassy clearings, small turquoise stream along the right edge, charming warm detailed toy-like volume. Composition: forest around left/right edges, broad quiet grassy open winding corridor through the middle, enough empty spaces for interactive level badges. NO visible road/path (it will be drawn dynamically in code), no circular markers, no UI, no numbers, no letters, no buttons, no labels, no frame, no room furniture, no sky, no characters. Entire output is the forest asset, not a screenshot or mockup. Attractive rich painterly texture and lighting matching reference, not flat vector art.

### Avatar

Use case: stylized-concept. Android casual game DEFAULT AVATAR asset, square 1024x1024. Image 1 is style reference only, the cheerful illustrated boy avatar in the rightmost profile screen. Draw a similar generic friendly young male cartoon game character, short tousled brown hair, big warm brown eyes, warm smile, green shirt, head and shoulders, polished hand-painted casual puzzle game illustration with soft rounded 3D volume and warm golden rim light. Centered close-up, no crop of hair, simple solid rich navy-blue background, fully opaque. No circle rim (code adds it), no letters, no badges, no numbers, no logo, no text, no UI. This is a generic optional fallback portrait, not any real person's identity.

### Coins

Use case: stylized-concept. Asset type: square casual mobile game reward illustration. Input 1 is a style reference only; match the coin pile illustration in the THIRD screen, shop reward panel. Create one generous pile of shiny warm gold coins, several short stacks and loose coins with simple embossed star symbols, resting on small rounded blue plinth. Polished hand-painted casual game art with softly rounded dimensional forms and bright warm edge highlights. Centered compact composition filling 80 percent of square. Uniform dark navy blue #122A48 background. No text, no numbers, no UI, no border, no watermark, no other objects. The entire output is this single icon illustration, not a screen.

## Reused assets

Room: toy_room_bg_v6.png. Supplies: ic_hint_open_position.png,
ic_hint_check_digit.png, ic_hint_check_position.png, ic_boost_extra_moves.png,
ic_boost_extra_time.png. Existing presets and glyph icons remain where no
approved dedicated asset exists; do not claim a pixel-identical reproduction.
