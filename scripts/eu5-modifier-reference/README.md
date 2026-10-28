# EU5 modifier reference

Builds a searchable page listing every modifier a **country** can use in Europa Universalis V, read from the
installed game and DLC files. For each modifier it shows the in-game name and description, the value type, which
direction helps, and the **min / most common / max** value the game itself uses, with where each one comes from.

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

## How values are counted

- **Fixed values** make up min / most common / max. Named script values (`small_tax_income_efficiency_bonus`),
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

| file                               | what it does                                                         |
| ---------------------------------- | -------------------------------------------------------------------- |
| `build_modifier_reference.py`      | reads definitions, localisation and every use, writes HTML and TXT   |
| `pdxparse.py`                      | small parser for Paradox script (`key = value`, `{ }` blocks)        |
| `modifier_reference_template.html` | the page; the script injects the data in place of `/*__DATA__*/null` |
