# Add New Game

## Expected Future Shape

A new game should reuse:

- platform config concepts
- localization layer
- service interfaces
- shell/navigation patterns
- screen adaptation rules

while providing its own:

- game core implementation
- mode catalog
- client screens

## Rule

If a second game requires editing existing shell code in many unrelated places, the platform boundary is still too weak.
