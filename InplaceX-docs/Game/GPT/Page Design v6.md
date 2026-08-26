# Page Design v6 — owner contract, 2026-08-26

The owner's five-page specification supersedes earlier shell-page colors,
spacing and shape values. It does not replace FinalUiTokens gameplay geometry.
Scope: Home, Social/Friends/inbox, Company, Shop, Profile and shared HUD/nav.
Keep the bright room, native Compose and every existing action/state contract.

Canonical sources: `PageDesignTokens.kt`, `PageComponents.kt`, compatibility
entry points `SceneCard` and `SceneActionTile`. Shared components:
PageHeroCard, PageSectionHeader, PrimaryActionTile, ContentCard, StatusCard,
StatTile, SegmentedControl, StickyActionBar. Shared primary/secondary buttons
and status pills supplement these, without adding a new visual family.

Page margins16dp, block gaps12dp; scroll content above existing nav/ad reserves.
Hero/action radius24dp, content20dp, inner/button16dp, pill14dp. One4dp
Compose elevation mechanism; no stacked shadows. The CSS-style owner shadow
is mapped to native elevation rather than an additional raster blur.
Touch actions >=48dp; heights are minimums, not clipping limits for RU/EN or
large font. Page title28, section22, card20, body15, secondary13, button16,
navigation11sp. No new font assets. Opaque cream content surfaces prevent
unintentional text on the wooden background; BrandHero is the exception.

Palette: chrome173B5E/092A49, primary0B6EDB/2C9AEE,
creamFFF4DE/FFEBC7, borderD8B36D, text3B2818/786047.
Section accents: Social purple, Company gold, Shop orange, Profile blue.
Home retains its three mode colors; Company Play retains semantic green.
Success/error colors remain allowed as status signals, with text/icon cues.

Home is the existing reference, not a redesigned landing page. Social uses a
cream hero/status, compact inbox and three purple navigation tiles. Shop keeps
all balances, stock, exact prices, product IDs, billing/retry/entitlement and
rewarded callbacks. Profile keeps all provider visibility rules, guest/linked
states, editors, clipboard and subscriptions; overview is an equal2x2 grid.
Company keeps progression, energy, rewards, chapter/level selection, timeline
and history; only presentation and its existing sticky action placement change.
The timeline now runs in ascending level order within the single page scroll;
opening a page does not jump past the hero. The selected level remains the
progression-selected level and the sticky action continues to target it.
The shared shell does not add percentage-based side padding to menu pages
which already own their 16dp margins; game/ad screen padding is unchanged.

Validation entry points: `PageDesignTokensTest`, `FriendRequestInboxTest`,
`ShellSectionsSmokeTest`, `UnifiedPagesVisualTest`. The latter uses isolated
RU/EN fixtures (1.0/1.5 font scale), no real requests, account or purchases;
run instrumentation on an emulator, never connected tests on an owner's phone.
Actual Samsung captures remain a separate visual acceptance gate.

UX level: standard, native implementation. Owner specification plus existing
Home/gameplay are references. Catalog unavailable/not required as a source of
new components; project-native primitives are preferred. No web variants.
Acceptance: component contrast/size tests, existing behavior/unit/UI tests,
RU/EN large-font checks, lint/build, five actual Samsung screenshots reviewed
together, and no gameplay/network/storage/purchase-authority changes.
