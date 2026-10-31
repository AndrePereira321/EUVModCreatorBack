# EU5 modifier reference

Builds a searchable page listing every modifier a **country** can use in Europa Universalis V, read from the
installed game and DLC files. Each modifier is a card with its in-game name and description, the value type, which
direction helps, and the lowest / most common / highest value the game itself uses. Expanding a card shows every
vanilla value on a ruler and in a list with where each one comes from, the limits its definition sets, a static
modifier ready to copy, and related modifiers. A second view lists the **sources** — advances, laws, privileges,
buildings, events… — and every modifier each one grants.

## Run it

Needs Python 3 (tested with 3.12), no packages. From this repo's root:

```bash
python scripts/eu5-modifier-reference/build_modifier_reference.py \
  "C:/Program Files (x86)/Steam/steamapps/common/Europa Universalis V/game" \
  "C:/Users/andre/Desktop/EU5 Country Modifiers.html" \
  "C:/Users/andre/Desktop/EU5 Country Modifiers.txt"
```

Arguments: the game's `game` folder, the HTML file to write, and optionally a plain-text version. It only reads
the game folder. Re-run it after a patch or a new DLC.

**The page needs an internet connection to open:** Vue, Floating UI, Tailwind's browser build and the fonts load
from jsDelivr and Google Fonts at pinned versions. The data itself is inside the HTML file.

## Using the page

- Search matches key, in-game name and description; the best matches come first. `/` jumps to the search box,
  ↑ ↓ move between cards, Enter opens or closes one, Esc clears.
- Topics in the sidebar come from the words in each key (`topics.py`), so a few modifiers sit in an odd one.
  "Used by" filters on what actually grants the modifier.
- Every source links to its card and every modifier back; following a link opens that card, and back / forward
  and bookmarks work.

## How values are counted

- **Fixed values** make up the ruler and the range. Named script values (`small_tax_income_efficiency_bonus`),
  `@variables` and `@[math]` are resolved to numbers; the page shows which name was used.
- **Scaled at runtime** values are listed apart: the game multiplies them (`scales_with`, bureaucracy `scale`,
  estate power and satisfaction, societal value sliders), so the written number is not the real effect.
- **Base values** from `country_base_values` apply to every country and are shown on their own, not as a bonus.
- Skipped: QA/debug modifiers, icon and text definitions, AI chance weights, `create_character` stats.
- Static modifiers the engine applies itself (difficulty, ruler skill, literacy…) may be scaled in ways the
  files don't show, so check the source of an extreme value before copying it.

Only `country`, `unit` (applies to all of the country's units) and `all` category modifiers are listed;
location, character and religion ones don't work in a country modifier.

## Files

| file                          | what it does                                                                         |
| ----------------------------- | ------------------------------------------------------------------------------------ |
| `build_modifier_reference.py` | reads definitions, localisation and every use, writes HTML and TXT                   |
| `pdxparse.py`                 | small parser for Paradox script (`key = value`, `{ }` blocks)                        |
| `topics.py`                   | sorts modifiers into sidebar topics by the words in their key                        |
| `modifier_reference.html`     | the page and its Vue templates; the script fills `/*@css*/`, `/*@js*/`, `/*@data*/`  |
| `modifier_reference.js`       | the Vue app: search, filters, routing, the ruler, tooltips                           |
| `modifier_reference.css`      | the mod creator's theme tokens (copied from the frontend's `index.css`) for Tailwind |
