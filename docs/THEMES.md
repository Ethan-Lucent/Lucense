# Themes

Every theme in Lucense (desktop) is one variant of a **family**, and every
family has a **dark** and a **light** variant (Custom is a family of one).
A fresh install starts on **Black, dark**. This file records where each
colour comes from, the rules every theme is held to, the measured numbers, and
every colour that moved from its published value or from the previous release.

The rules are enforced by `desktop/src/test/.../ThemeBalanceTest.kt`;
`ThemeSheetTest` draws all of them on one contact sheet
(`/tmp/themes-sheet.png`) and writes these numbers to `/tmp/themes-report.txt`.

## Sources (read 2026-11-27)

| Family | Dark | Light | Source |
|---|---|---|---|
| Catppuccin | Mocha | Latte | https://github.com/catppuccin/palette (`palette.json`) |
| Dracula | Dracula | Alucard | https://draculatheme.com/spec (Dracula Classic and Alucard Classic tables), https://draculatheme.com/contribute |
| Tokyo Night | Tokyo Night | Tokyo Day | https://github.com/folke/tokyonight.nvim (`lua/tokyonight/colors/storm.lua`, `night.lua`); light: https://github.com/enkia/tokyo-night-vscode-theme (`themes/tokyo-night-light-color-theme.json`) |
| Rosé Pine | Rosé Pine | Rosé Pine Dawn | https://github.com/rose-pine/palette (`palette.json`), https://rosepinetheme.com/palette/ (highlight low/med/high) |
| Nord | Nord | Snow Storm | https://github.com/nordtheme/nord (`src/nord.css`), https://www.nordtheme.com/docs/colors-and-palettes |
| Gruvbox | Gruvbox | Gruvbox Light | https://github.com/morhetz/gruvbox (`colors/gruvbox.vim`) |
| Everforest | Everforest | Everforest Light | https://github.com/sainnhe/everforest (`palette.md`, hard backgrounds) |
| Solarized | Solarized | Solarized Light | https://ethanschoonover.com/solarized/ |
| One | One Dark | One Light | https://github.com/atom/atom (`packages/one-dark-syntax`, `one-light-syntax`, `one-light-ui` `styles/*.less`), https://github.com/joshdick/onedark.vim (`autoload/onedark.vim`) |
| Ayu | Ayu Mirage | Ayu Light | https://github.com/ayu-theme/ayu-colors (`themes/mirage.yaml`, `themes/light.yaml`) |
| Black, Slate, Ocean, Sunset | | | Radix Colors 12-step scales, https://github.com/radix-ui/colors (`src/dark.ts`, `src/light.ts`) and https://www.radix-ui.com/colors/docs/palette-composition/understanding-the-scale |

Balance: Radix's scale roles (steps 1-2 app backgrounds, 3-5 component
backgrounds, 6-8 borders, 9-10 solids, 11-12 text), Material 3's tonal surface
containers (https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md:
surfaces are tones of one neutral, and on a light scheme the containers step
*darker* from the page as they gain emphasis), the 60-30-10 idea (mostly
surface, some secondary surface, a little accent) and WCAG 2.1 contrast
(https://www.w3.org/TR/WCAG21/: 4.5:1 text, 7:1 enhanced).

## Roles

Each theme fills ten roles: `base` (the window, the back), `sidebar`,
`content` (the page), `raised` (bars, panels, cards), `hover`, `line`,
`text`, `dim` and `faint` text, and one `accent`. The soft accent, the selected
row and the main-button fill are mixed from the accent toward the page.

Published schemes are mapped role by role from their own hierarchy:

| Scheme | base | sidebar | content | raised | hover | line | text | dim | faint | accent |
|---|---|---|---|---|---|---|---|---|---|---|
| Catppuccin Mocha | crust | mantle | base | surface0 | surface1 | surface2 | text | subtext1 | subtext0 | mauve |
| Catppuccin Latte | crust | mantle | base | mantle | crust | surface0 | text | subtext1 | subtext0 | mauve |
| Dracula | bg darker | bg dark | background | bg light | bg lighter | selection | foreground | fg/comment mix | comment | purple |
| Alucard | bg light | bg lighter | background | floating | bg light | bg dark | foreground | fg/comment mix | comment | purple |
| Tokyo Night | bg_dark1 | bg_dark | bg | bg_highlight | fg_gutter | terminal_black | fg | fg_dark | dark5 | blue |
| Tokyo Day | sideBar | sideBar | editor | list hover | sideBar | border | editor fg | fg/description mix | description | link blue |
| Rosé Pine | base | surface | surface | overlay | highlight med | highlight high | text | subtle | muted | iris |
| Rosé Pine Dawn | overlay | base | surface | highlight low | highlight med | highlight high | text | subtle | muted | iris |
| Nord | nord0, 3 L* darker | nord0 | nord0 | nord1 | nord2 | nord3 | nord6 | nord4 | nord3 bright | nord8 |
| Snow Storm | nord4 | nord5 | nord6 | nord5 | nord4 | nord4 | nord0 | nord3 | nord3 bright | nord10 |
| Gruvbox | bg0_h | bg0 | bg0 | bg0_s | bg1 | bg2 | fg0 | fg1 | fg3 | bright yellow |
| Gruvbox Light | light1 | light0_soft | light0 | light0_soft | light1 | light2 | dark1 | dark2 | dark4 | faded yellow |
| Everforest (hard) | bg_dim | bg0 | bg0 | bg1 | bg2 | bg4 | fg | grey2 | grey1 | green |
| Everforest Light (hard) | bg_dim | bg1 | bg0 | bg1 | bg3 | bg5 | fg | grey2 | grey1 | green |
| Solarized | base03, 3 L* darker | base03 | base03 | base02 | base02 | base01 | base2 | base1 | base0 | blue |
| Solarized Light | base2 | base2 | base3 | base2 | base2 | base2 darkened | base02 | base01 | base00 | blue |
| One Dark | one-dark-ui level-3 | level-3 | syntax-bg | cursor_grey | visual_grey | gutter_fg_grey | mono-1 | mono-2 | mono-3 | hue-2 blue |
| One Light | one-light-ui level-3 | level-3 | syntax-bg | level-3 (lighter) | hover | ui-border | mono-1 | mono-2 | mono-3 | hue-2 blue |
| Ayu Mirage | surface.sunk | surface.base | surface.lift | ui.panel | selection over panel | lighter line | editor fg over page | ui fg over page | ui fg 0.45 | accent tint |
| Ayu Light | surface.sunk | surface.base | surface.lift | ui.panel | panel, darker | ui.line | editor fg over page | ui fg over page | ui fg 0.48 | accent tint |

The new families:

- **Black** (the default). Dark: true black window (`#000000`), near-black
  panes on Radix gray steps 1-3 and 6, gray 12/11 text, one restrained accent:
  Radix violet 11 (Lucense has always been purple). Light ("White"): white page
  on gray 2-4 chrome, gray 12 text, violet 11. Neutral: surface chroma 0.
- **Slate**: Radix slate 1-6 surfaces, slate 12/11/10 text, blue 11 accent.
- **Ocean**: Radix cyan 1-2 for the window and page; the component and text
  steps are Radix slate's lightness ladder tinted at cyan's hue (chroma 0.022
  dark, 0.014 light), because Radix's cyan 3-8 are too saturated for surfaces;
  cyan 11 accent (light: cyan 12 text).
- **Sunset**: Radix orange 1-2 for the window and page, Radix sand's ladder
  tinted at orange's hue for the rest, orange 11 accent.

## Rules (ThemeBalanceTest)

- Text on base, sidebar and page at least **10:1**; **7:1** only where the
  published text tone is lower: Latte, Tokyo Day, Rosé Pine Dawn, Snow Storm,
  Gruvbox Light, Everforest (dark and light), One Dark, One Light, Ayu Mirage,
  Ayu Light. Dim text **6:1**, faint **4.5:1** on the page. Accent **3.5:1** on
  the page; the label on the accent and on a main button **4.5:1**; text on a
  selected or hovered row 4.5:1.
- Layers. Both modes: base <= sidebar <= page in lightness, base darker than
  the page (the panes sit on the window). Dark: raised, hover and line step
  lighter from the page. Light: they step **darker** from the page, so the page
  reads as paper and bars and cards as shaded containers (Material 3's rule).
  Raised vs page and hover vs raised differ by **2 to 9 L\*** (CIELAB). A line
  is **1.25:1 to 2.4:1** against the page.
- Calm. Surfaces have OKLCH chroma <= **0.04** and lean one way (tinted
  surfaces within 40 degrees of hue); Black's are neutral (<= 0.006). Accents:
  chroma <= **0.17** on dark themes, <= **0.20** on light ones (a light theme's
  accent must be darker to read, which needs a little more chroma). A family's
  two accents are within 45 degrees of hue.
- Where a published tone broke a rule, only that role moved, by the smallest
  step that passes (lightness in OKLCH at the same hue and chroma, or chroma
  down at the same lightness). Those moves are listed below.

Two app-wide changes came with this: the ink on an accent (and on a cover
accent) is now whichever of near-black and near-white has the higher contrast
(it used to switch at a fixed brightness, which put white on mid-tone accents),
and a main button's fill stays nearer the accent when the usual 38% mix toward
the page lands on a mid tone no label can read on.

## Every theme, final values

| Theme | family | mode | accent | base | sidebar | content | raised | hover | line | text | dim | faint |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Black (`BLACK`) | Black | dark | `#BAA7FF` | `#000000` | `#0A0A0A` | `#111111` | `#191919` | `#222222` | `#3A3A3A` | `#EEEEEE` | `#B4B4B4` | `#7D7D7D` |
| White (`WHITE`) | Black | light | `#6550B9` | `#F0F0F0` | `#F9F9F9` | `#FFFFFF` | `#F0F0F0` | `#E8E8E8` | `#D9D9D9` | `#202020` | `#626262` | `#757575` |
| Slate (`SLATE`) | Slate | dark | `#70B8FF` | `#111113` | `#18191B` | `#18191B` | `#212225` | `#272A2D` | `#363A3F` | `#EDEEF0` | `#B0B4BA` | `#7E828B` |
| Slate Light (`SLATE_LIGHT`) | Slate | light | `#0772CB` | `#F0F0F3` | `#F9F9FB` | `#FCFCFD` | `#F0F0F3` | `#E8E8EC` | `#D9D9E0` | `#1C2024` | `#5D6169` | `#71747D` |
| Ocean (`OCEAN`) | Ocean | dark | `#4CCCE6` | `#0B161A` | `#101B20` | `#101B20` | `#17242B` | `#1E2C32` | `#2E3C43` | `#E0F1FA` | `#A6B7BF` | `#74858C` |
| Ocean Light (`OCEAN_LIGHT`) | Ocean | light | `#087A95` | `#E6F3F5` | `#F2FAFB` | `#FAFDFE` | `#E6F3F5` | `#DEEBED` | `#D0DDDE` | `#0D3C48` | `#586365` | `#6A7677` |
| Sunset (`SUNSET`) | Sunset | dark | `#FFA057` | `#17120E` | `#1E160F` | `#1E160F` | `#28201A` | `#302822` | `#403832` | `#F6ECE3` | `#BBB1A9` | `#897F77` |
| Sunset Light (`SUNSET_LIGHT`) | Sunset | light | `#C14A03` | `#F7EFE5` | `#FFF7ED` | `#FEFCFB` | `#F7EFE5` | `#EFE7DD` | `#E0D8CE` | `#251F18` | `#676058` | `#7B736A` |
| Mocha (`MOCHA`) | Catppuccin | dark | `#CBA6F7` | `#11111B` | `#181825` | `#1E1E2E` | `#303042` | `#424456` | `#54576B` | `#CDD6F4` | `#BAC2DE` | `#A6ADC8` |
| Latte (`LATTE`) | Catppuccin | light | `#8250D4` | `#DCE0E8` | `#E6E9EF` | `#EFF1F5` | `#E6E9EF` | `#DCE0E8` | `#CCD0DA` | `#42455F` | `#565971` | `#696C82` |
| Dracula (`DRACULA`) | Dracula | dark | `#BD93F9` | `#191A21` | `#21222C` | `#282A36` | `#343746` | `#424450` | `#44475A` | `#F8F8F2` | `#CBD0DB` | `#7F91C5` |
| Alucard (`ALUCARD`) | Dracula | light | `#644AC9` | `#DEDCCF` | `#ECE9DF` | `#FFFBEB` | `#EFEDDC` | `#DEDCCF` | `#CECCC0` | `#1F1F1F` | `#4D4A39` | `#6C664B` |
| Tokyo Night (`TOKYO`) | Tokyo Night | dark | `#7AA2F7` | `#0C0E14` | `#16161E` | `#1A1B26` | `#282D40` | `#3A4056` | `#434960` | `#C0CAF5` | `#A9B1D6` | `#7B82AA` |
| Tokyo Day (`TOKYO_DAY`) | Tokyo Night | light | `#2959AA` | `#D6D8DF` | `#D6D8DF` | `#E6E7ED` | `#E0E0E6` | `#D6D8DF` | `#C1C2C7` | `#343B59` | `#4F5369` | `#646674` |
| Rosé Pine (`ROSE`) | Rosé Pine | dark | `#C4A7E7` | `#191724` | `#1F1D2E` | `#1F1D2E` | `#262339` | `#39364A` | `#524F67` | `#E0DEF4` | `#9E9AB9` | `#8884A1` |
| Rosé Pine Dawn (`DAWN`) | Rosé Pine | light | `#907AA9` | `#F2E9E1` | `#FAF4ED` | `#FFFAF3` | `#F4EDE8` | `#DFDAD9` | `#CECACD` | `#464261` | `#605C79` | `#757182` |
| Nord (`NORD`) | Nord | dark | `#88C0D0` | `#282E39` | `#2E3440` | `#2E3440` | `#3B4252` | `#434C5E` | `#4C566A` | `#ECEFF4` | `#D8DEE9` | `#8F9DB8` |
| Snow Storm (`SNOW`) | Nord | light | `#5C7FAA` | `#D8DEE9` | `#E5E9F0` | `#ECEFF4` | `#E5E8F0` | `#D8DEE9` | `#CED3DE` | `#2E3440` | `#4C566A` | `#5F6C86` |
| Gruvbox (`GRUVBOX`) | Gruvbox | dark | `#FABD2F` | `#1D2021` | `#282828` | `#282828` | `#32302F` | `#3C3836` | `#504945` | `#FBF1C7` | `#EBDBB2` | `#BDAE93` |
| Gruvbox Light (`GRUVBOX_LIGHT`) | Gruvbox | light | `#B07108` | `#E6DBC0` | `#EEE5C9` | `#F8F1D4` | `#EEE5C9` | `#E6DBC0` | `#D1C4AA` | `#3C3836` | `#504945` | `#786B60` |
| Everforest (`FOREST`) | Everforest | dark | `#A7C080` | `#1E2326` | `#272E33` | `#272E33` | `#2E383C` | `#374145` | `#495156` | `#D3C6AA` | `#A3AFA6` | `#8B988F` |
| Everforest Light (`FOREST_LIGHT`) | Everforest | light | `#7B8D07` | `#F2EFDF` | `#F8F5E4` | `#FFFBEF` | `#F8F5E4` | `#EDEADA` | `#BEC5B2` | `#445159` | `#566455` | `#6B7669` |
| Solarized (`SOLAR`) | Solarized | dark | `#268BD2` | `#05252D` | `#0B2A33` | `#0B2A33` | `#17353E` | `#1D3B44` | `#4B6167` | `#EEE8D5` | `#99A7A7` | `#839496` |
| Solarized Light (`DAYLIGHT`) | Solarized | light | `#2187CE` | `#EEE8D5` | `#EEE8D5` | `#FDF6E3` | `#EEE8D5` | `#E7E2CF` | `#D2CCB9` | `#073642` | `#4C6168` | `#5E747C` |
| One Dark (`ONEDARK`) | One | dark | `#61AFEF` | `#21252B` | `#21252B` | `#282C34` | `#2C323C` | `#3E4452` | `#4B5263` | `#B2B9C6` | `#A3ABB9` | `#8C94A2` |
| One Light (`ONELIGHT`) | One | light | `#4078F2` | `#EAEAEB` | `#EAEAEB` | `#FAFAFA` | `#F0F0F1` | `#E5E5E6` | `#DBDBDC` | `#383A42` | `#5D5F6A` | `#717278` |
| Ayu Mirage (`AYU`) | Ayu | dark | `#FFCC66` | `#181C26` | `#1F2430` | `#242936` | `#282E3B` | `#343B4C` | `#3A4150` | `#CCCAC2` | `#9CA8BD` | `#8691A3` |
| Ayu Light (`AYU_LIGHT`) | Ayu | light | `#BF7507` | `#EBEEF0` | `#F8F9FA` | `#FCFCFC` | `#F6F5F6` | `#EDEFF1` | `#DCDFE3` | `#4A4F54` | `#536175` | `#6A7585` |
| Custom (`CUSTOM`) | Custom | dark | starts on Mocha's values; made in the editor | | | | | | | | | |

## Measured (WCAG contrast, CIELAB L\* steps, OKLCH chroma)

| Theme | text/base | text/content | dim | faint | accent | onAccent | onButton | line | dL\*raised | dL\*hover | accentC | surfaceC | text/selected |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `BLACK` | 18.10 | 16.28 | 9.11 | 4.59 | 9.05 | 9.05 | 4.62 | 1.66 | 3.69 | 4.47 | 0.125 | 0.000 | 12.45 |
| `WHITE` | 14.30 | 16.29 | 6.10 | 4.61 | 6.17 | 5.73 | 5.84 | 1.41 | 5.20 | 2.80 | 0.159 | 0.000 | 12.46 |
| `SLATE` | 16.25 | 15.15 | 8.45 | 4.57 | 8.37 | 8.99 | 4.55 | 1.54 | 4.51 | 3.64 | 0.126 | 0.010 | 11.22 |
| `SLATE_LIGHT` | 14.41 | 15.98 | 6.06 | 4.56 | 4.79 | 4.56 | 6.52 | 1.37 | 4.12 | 2.77 | 0.161 | 0.010 | 12.66 |
| `OCEAN` | 15.85 | 15.11 | 8.46 | 4.56 | 9.24 | 9.98 | 4.52 | 1.54 | 4.36 | 3.68 | 0.116 | 0.022 | 11.00 |
| `OCEAN_LIGHT` | 10.53 | 11.69 | 6.07 | 4.60 | 4.86 | 4.61 | 4.74 | 1.36 | 4.16 | 2.80 | 0.097 | 0.014 | 9.22 |
| `SUNSET` | 15.96 | 15.32 | 8.48 | 4.56 | 8.85 | 9.38 | 4.62 | 1.55 | 4.99 | 3.83 | 0.143 | 0.019 | 11.31 |
| `SUNSET_LIGHT` | 14.31 | 15.94 | 6.06 | 4.56 | 4.83 | 4.59 | 6.47 | 1.38 | 4.28 | 2.80 | 0.166 | 0.016 | 12.60 |
| `MOCHA` | 12.97 | 11.34 | 9.26 | 7.37 | 8.07 | 9.30 | 4.64 | 2.31 | 8.61 | 8.74 | 0.119 | 0.033 | 8.18 |
| `LATTE` | 7.06 | 8.26 | 6.06 | 4.57 | 4.59 | 4.82 | 7.00 | 1.37 | 2.82 | 3.19 | 0.194 | 0.014 | 6.57 |
| `DRACULA` | 16.27 | 13.36 | 9.21 | 4.58 | 5.90 | 7.83 | 4.57 | 1.56 | 6.02 | 5.73 | 0.149 | 0.032 | 9.87 |
| `ALUCARD` | 11.97 | 15.89 | 8.60 | 5.56 | 6.02 | 5.79 | 5.77 | 1.56 | 5.04 | 5.90 | 0.188 | 0.023 | 12.22 |
| `TOKYO` | 11.95 | 10.59 | 8.10 | 4.57 | 6.79 | 7.51 | 4.89 | 1.92 | 8.66 | 8.61 | 0.132 | 0.039 | 8.07 |
| `TOKYO_DAY` | 7.70 | 8.88 | 6.14 | 4.61 | 5.48 | 6.28 | 5.78 | 1.44 | 2.39 | 2.96 | 0.140 | 0.010 | 6.85 |
| `ROSE` | 13.39 | 12.50 | 6.11 | 4.60 | 7.88 | 9.03 | 4.55 | 2.10 | 3.27 | 8.67 | 0.095 | 0.040 | 9.10 |
| `DAWN` | 7.92 | 9.14 | 6.12 | 4.55 | 3.65 | 4.99 | 8.59 | 1.56 | 4.32 | 6.75 | 0.074 | 0.015 | 7.47 |
| `NORD` | 11.83 | 10.84 | 9.25 | 4.57 | 6.24 | 9.45 | 4.90 | 1.69 | 6.34 | 4.25 | 0.062 | 0.035 | 7.77 |
| `SNOW` | 9.25 | 10.84 | 6.40 | 4.58 | 3.59 | 4.56 | 5.17 | 1.30 | 2.37 | 3.67 | 0.077 | 0.016 | 8.86 |
| `GRUVBOX` | 14.45 | 12.99 | 10.75 | 6.77 | 8.69 | 11.15 | 4.51 | 1.67 | 3.92 | 3.82 | 0.159 | 0.012 | 8.99 |
| `GRUVBOX_LIGHT` | 8.43 | 10.23 | 7.78 | 4.55 | 3.55 | 4.69 | 4.90 | 1.52 | 4.02 | 3.35 | 0.128 | 0.039 | 8.37 |
| `FOREST` | 9.39 | 8.15 | 6.06 | 4.58 | 6.88 | 9.45 | 4.74 | 1.70 | 4.30 | 4.02 | 0.091 | 0.015 | 5.81 |
| `FOREST_LIGHT` | 7.09 | 7.91 | 6.06 | 4.59 | 3.59 | 5.09 | 8.77 | 1.72 | 2.27 | 3.82 | 0.140 | 0.027 | 6.49 |
| `SOLAR` | 13.09 | 12.30 | 6.06 | 4.77 | 4.10 | 5.14 | 5.23 | 2.30 | 5.04 | 2.67 | 0.139 | 0.039 | 9.88 |
| `DAYLIGHT` | 10.61 | 12.05 | 6.05 | 4.56 | 3.59 | 4.88 | 5.75 | 1.49 | 4.96 | 2.19 | 0.140 | 0.027 | 9.92 |
| `ONEDARK` | 7.81 | 7.10 | 6.06 | 4.58 | 5.92 | 8.00 | 4.63 | 1.79 | 2.69 | 8.21 | 0.121 | 0.029 | 5.27 |
| `ONELIGHT` | 9.43 | 10.86 | 6.08 | 4.59 | 3.88 | 4.67 | 5.00 | 1.33 | 3.45 | 3.85 | 0.193 | 0.001 | 8.84 |
| `AYU` | 10.38 | 8.85 | 6.05 | 4.56 | 9.74 | 12.67 | 5.16 | 1.42 | 2.25 | 6.00 | 0.133 | 0.031 | 5.91 |
| `AYU_LIGHT` | 7.10 | 8.07 | 6.14 | 4.55 | 3.55 | 5.19 | 8.88 | 1.30 | 2.33 | 2.29 | 0.138 | 0.006 | 6.64 |
| `CUSTOM` | 12.97 | 11.34 | 9.26 | 7.37 | 8.07 | 9.30 | 4.64 | 2.31 | 8.61 | 8.74 | 0.119 | 0.033 | 8.18 |

## Moved from the published value

| Theme | role | published | used | why |
|---|---|---|---|---|
| `BLACK` | faint | `#7B7B7B` | `#7D7D7D` | contrast raised to 4.5:1 |
| `WHITE` | dim | `#646464` | `#626262` | contrast raised to 6:1 |
| `WHITE` | faint | `#838383` | `#757575` | contrast raised to 4.5:1 |
| `SLATE` | faint | `#777B84` | `#7E828B` | contrast raised to 4.5:1 |
| `SLATE_LIGHT` | dim | `#60646C` | `#5D6169` | contrast raised to 6:1 |
| `SLATE_LIGHT` | faint | `#80838D` | `#71747D` | contrast raised to 4.5:1 |
| `SLATE_LIGHT` | accent | `#0D74CE` | `#0772CB` | accent kept readable on the page, and under its own label |
| `OCEAN` | faint | `#6E7E85` | `#74858C` | contrast raised to 4.5:1 |
| `OCEAN_LIGHT` | dim | `#5B6668` | `#586365` | contrast raised to 6:1 |
| `OCEAN_LIGHT` | faint | `#7A8687` | `#6A7677` | contrast raised to 4.5:1 |
| `OCEAN_LIGHT` | accent | `#107D98` | `#087A95` | accent kept readable on the page, and under its own label |
| `SUNSET` | faint | `#827971` | `#897F77` | contrast raised to 4.5:1 |
| `SUNSET_LIGHT` | dim | `#686159` | `#676058` | contrast raised to 6:1 |
| `SUNSET_LIGHT` | faint | `#888077` | `#7B736A` | contrast raised to 4.5:1 |
| `SUNSET_LIGHT` | accent | `#CC4E00` | `#C14A03` | accent kept readable on the page, and under its own label |
| `MOCHA` | raised | `#313244` | `#303042` | L* step from content kept within 2-9 |
| `MOCHA` | hover | `#45475A` | `#424456` | L* step from raised kept within 2-9 |
| `MOCHA` | line | `#585B70` | `#54576B` | line contrast kept within 1.25-2.4 |
| `LATTE` | text | `#4C4F69` | `#42455F` | contrast raised to 7:1 |
| `LATTE` | dim | `#5C5F77` | `#565971` | contrast raised to 6:1 |
| `LATTE` | faint | `#6C6F85` | `#696C82` | contrast raised to 4.5:1 |
| `LATTE` | accent | `#8839EF` | `#8250D4` | chroma capped at 0.20 |
| `DRACULA` | faint | `#6272A4` | `#7F91C5` | contrast raised to 4.5:1 |
| `TOKYO` | hover | `#3B4261` | `#3D4359` | surface chroma capped at 0.04 |
| `TOKYO` | line | `#414868` | `#434960` | surface chroma capped at 0.04 |
| `TOKYO` | raised | `#292E42` | `#282D40` | L* step from content kept within 2-9 |
| `TOKYO` | hover | `#3D4359` | `#3A4056` | L* step from raised kept within 2-9 |
| `TOKYO` | faint | `#737AA2` | `#7B82AA` | contrast raised to 4.5:1 |
| `TOKYO_DAY` | raised | `#E1E2E8` | `#E0E0E6` | L* step from content kept within 2-9 |
| `TOKYO_DAY` | dim | `#52566C` | `#4F5369` | contrast raised to 6:1 |
| `TOKYO_DAY` | faint | `#707280` | `#646674` | contrast raised to 4.5:1 |
| `ROSE` | raised | `#26233A` | `#262339` | surface chroma capped at 0.04 |
| `ROSE` | hover | `#403D52` | `#39364A` | L* step from raised kept within 2-9 |
| `ROSE` | dim | `#908CAA` | `#9E9AB9` | contrast raised to 6:1 |
| `ROSE` | faint | `#6E6A86` | `#8884A1` | contrast raised to 4.5:1 |
| `DAWN` | dim | `#797593` | `#605C79` | contrast raised to 6:1 |
| `DAWN` | faint | `#9893A5` | `#757182` | contrast raised to 4.5:1 |
| `NORD` | faint | `#616E88` | `#8F9DB8` | contrast raised to 4.5:1 |
| `SNOW` | raised | `#E5E9F0` | `#E5E8F0` | L* step from content kept within 2-9 |
| `SNOW` | line | `#D8DEE9` | `#CED3DE` | line contrast kept within 1.25-2.4 |
| `SNOW` | faint | `#616E88` | `#5F6C86` | contrast raised to 4.5:1 |
| `SNOW` | accent | `#5E81AC` | `#5C7FAA` | accent kept readable on the page, and under its own label |
| `GRUVBOX_LIGHT` | base | `#EBDBB2` | `#E6DBC0` | surface chroma capped at 0.04 |
| `GRUVBOX_LIGHT` | sidebar | `#F2E5BC` | `#EEE5C9` | surface chroma capped at 0.04 |
| `GRUVBOX_LIGHT` | content | `#FBF1C7` | `#F8F1D4` | surface chroma capped at 0.04 |
| `GRUVBOX_LIGHT` | raised | `#F2E5BC` | `#EEE5C9` | surface chroma capped at 0.04 |
| `GRUVBOX_LIGHT` | hover | `#EBDBB2` | `#E6DBC0` | surface chroma capped at 0.04 |
| `GRUVBOX_LIGHT` | line | `#D5C4A1` | `#D1C4AA` | surface chroma capped at 0.04 |
| `GRUVBOX_LIGHT` | faint | `#7C6F64` | `#786B60` | contrast raised to 4.5:1 |
| `GRUVBOX_LIGHT` | accent | `#B57614` | `#B07108` | accent kept readable on the page, and under its own label |
| `FOREST` | dim | `#9DA9A0` | `#A3AFA6` | contrast raised to 6:1 |
| `FOREST` | faint | `#859289` | `#8B988F` | contrast raised to 4.5:1 |
| `FOREST_LIGHT` | text | `#5C6A72` | `#445159` | contrast raised to 7:1 |
| `FOREST_LIGHT` | dim | `#829181` | `#566455` | contrast raised to 6:1 |
| `FOREST_LIGHT` | faint | `#939F91` | `#6B7669` | contrast raised to 4.5:1 |
| `FOREST_LIGHT` | accent | `#8DA101` | `#7B8D07` | accent kept readable on the page, and under its own label |
| `SOLAR` | base | `#02252E` | `#05252D` | surface chroma capped at 0.04 |
| `SOLAR` | sidebar | `#002B36` | `#0B2A33` | surface chroma capped at 0.04 |
| `SOLAR` | content | `#002B36` | `#0B2A33` | surface chroma capped at 0.04 |
| `SOLAR` | raised | `#073642` | `#17353E` | surface chroma capped at 0.04 |
| `SOLAR` | hover | `#073642` | `#17353E` | surface chroma capped at 0.04 |
| `SOLAR` | hover | `#17353E` | `#1D3B44` | L* step from raised kept within 2-9 |
| `SOLAR` | line | `#586E75` | `#4B6167` | line contrast kept within 1.25-2.4 |
| `SOLAR` | dim | `#93A1A1` | `#99A7A7` | contrast raised to 6:1 |
| `DAYLIGHT` | hover | `#EEE8D5` | `#E7E2CF` | L* step from raised kept within 2-9 |
| `DAYLIGHT` | dim | `#586E75` | `#4C6168` | contrast raised to 6:1 |
| `DAYLIGHT` | faint | `#657B83` | `#5E747C` | contrast raised to 4.5:1 |
| `DAYLIGHT` | accent | `#268BD2` | `#2187CE` | accent kept readable on the page, and under its own label |
| `ONEDARK` | text | `#ABB2BF` | `#B2B9C6` | contrast raised to 7:1 |
| `ONEDARK` | dim | `#828997` | `#A3ABB9` | contrast raised to 6:1 |
| `ONEDARK` | faint | `#5C6370` | `#8C94A2` | contrast raised to 4.5:1 |
| `ONELIGHT` | dim | `#696C77` | `#5D5F6A` | contrast raised to 6:1 |
| `ONELIGHT` | faint | `#A0A1A7` | `#717278` | contrast raised to 4.5:1 |
| `AYU` | dim | `#97A3B8` | `#9CA8BD` | contrast raised to 6:1 |
| `AYU` | faint | `#737D8F` | `#8691A3` | contrast raised to 4.5:1 |
| `AYU_LIGHT` | raised | `#FAFAFA` | `#F6F5F6` | L* step from content kept within 2-9 |
| `AYU_LIGHT` | hover | `#F0F2F4` | `#EDEFF1` | L* step from raised kept within 2-9 |
| `AYU_LIGHT` | line | `#E0E4E8` | `#DCDFE3` | line contrast kept within 1.25-2.4 |
| `AYU_LIGHT` | text | `#5C6166` | `#4A4F54` | contrast raised to 7:1 |
| `AYU_LIGHT` | dim | `#667489` | `#536175` | contrast raised to 6:1 |
| `AYU_LIGHT` | faint | `#8490A0` | `#6A7585` | contrast raised to 4.5:1 |
| `AYU_LIGHT` | accent | `#F29718` | `#BF7507` | accent kept readable on the page, and under its own label |

Also derived rather than published (no such tone exists in the scheme):
Dracula's and Alucard's dim text (foreground mixed toward comment), Tokyo
Day's dim text (editor fg mixed toward description), Nord's and Solarized's
base (their background 3 L\* darker, so the window sits behind the page),
Solarized Light's line (base2 darkened to L\* 82, in base2's hue: base1 is a
cool grey on a cream page), Ayu's hover and line, and Ayu's text tones
(the published foregrounds are translucent; these are them composited on the
page).

## What changed for existing saved themes

The enum names are what is saved, so every stored name still means its theme;
`fromName` sends anything else (including the old default name "Amethyst",
which never was a theme) to Mocha, which is what it always showed. Someone
updating who never picked a theme keeps Mocha (stored once on first start, the
same way as "advanced mode"); a fresh install gets Black.

| Theme | role | before | now |
|---|---|---|---|
| `MOCHA` | raised | `#313244` | `#303042` |
| `MOCHA` | hover | `#45475A` | `#424456` |
| `MOCHA` | line | `#585B70` | `#54576B` |
| `LATTE` | accent | `#8839EF` | `#8250D4` |
| `LATTE` | hover | `#CCD0DA` | `#DCE0E8` |
| `LATTE` | line | `#BCC0CC` | `#CCD0DA` |
| `LATTE` | text | `#3C3F54` | `#42455F` |
| `LATTE` | dim | `#5C5F77` | `#565971` |
| `LATTE` | faint | `#7C7F93` | `#696C82` |
| `DRACULA` | base | `#1E1F29` | `#191A21` |
| `DRACULA` | sidebar | `#242531` | `#21222C` |
| `DRACULA` | hover | `#44475A` | `#424450` |
| `DRACULA` | line | `#525569` | `#44475A` |
| `DRACULA` | dim | `#D5D6E0` | `#CBD0DB` |
| `DRACULA` | faint | `#A8AEC8` | `#7F91C5` |
| `TOKYO` | base | `#16161E` | `#0C0E14` |
| `TOKYO` | sidebar | `#1A1B26` | `#16161E` |
| `TOKYO` | content | `#1F2130` | `#1A1B26` |
| `TOKYO` | raised | `#292E42` | `#282D40` |
| `TOKYO` | hover | `#343A52` | `#3A4056` |
| `TOKYO` | line | `#3B4261` | `#434960` |
| `TOKYO` | faint | `#8A93B8` | `#7B82AA` |
| `ROSE` | base | `#16141F` | `#191724` |
| `ROSE` | sidebar | `#191724` | `#1F1D2E` |
| `ROSE` | raised | `#26233A` | `#262339` |
| `ROSE` | hover | `#302C4A` | `#39364A` |
| `ROSE` | line | `#403C5C` | `#524F67` |
| `ROSE` | dim | `#C7C4DE` | `#9E9AB9` |
| `ROSE` | faint | `#9E9ABA` | `#8884A1` |
| `NORD` | base | `#272C36` | `#282E39` |
| `NORD` | content | `#333B4A` | `#2E3440` |
| `NORD` | faint | `#AEB8C8` | `#8F9DB8` |
| `GRUVBOX` | sidebar | `#232728` | `#282828` |
| `FOREST` | content | `#2D353B` | `#272E33` |
| `FOREST` | raised | `#343F44` | `#2E383C` |
| `FOREST` | hover | `#3D484D` | `#374145` |
| `FOREST` | line | `#4F585E` | `#495156` |
| `FOREST` | dim | `#BEC5AE` | `#A3AFA6` |
| `FOREST` | faint | `#9DA9A0` | `#8B988F` |
| `SOLAR` | accent | `#4FA3DB` | `#268BD2` |
| `SOLAR` | base | `#002B36` | `#05252D` |
| `SOLAR` | sidebar | `#04303B` | `#0B2A33` |
| `SOLAR` | content | `#073642` | `#0B2A33` |
| `SOLAR` | raised | `#0E4451` | `#17353E` |
| `SOLAR` | hover | `#17505E` | `#1D3B44` |
| `SOLAR` | line | `#2C5D68` | `#4B6167` |
| `SOLAR` | text | `#FDF6E3` | `#EEE8D5` |
| `SOLAR` | dim | `#EEE8D5` | `#99A7A7` |
| `SOLAR` | faint | `#A9B5B5` | `#839496` |
| `DAYLIGHT` | accent | `#1C6FA5` | `#2187CE` |
| `DAYLIGHT` | sidebar | `#F3EDDC` | `#EEE8D5` |
| `DAYLIGHT` | hover | `#E0D9C0` | `#E7E2CF` |
| `DAYLIGHT` | line | `#CFC8AF` | `#D2CCB9` |
| `DAYLIGHT` | dim | `#3F5B63` | `#4C6168` |
| `DAYLIGHT` | faint | `#6C7D7D` | `#5E747C` |
| `ONEDARK` | sidebar | `#23272E` | `#21252B` |
| `ONEDARK` | raised | `#2F343D` | `#2C323C` |
| `ONEDARK` | hover | `#3A3F4B` | `#3E4452` |
| `ONEDARK` | line | `#474C55` | `#4B5263` |
| `ONEDARK` | text | `#DCDFE4` | `#B2B9C6` |
| `ONEDARK` | dim | `#ABB2BF` | `#A3ABB9` |
| `ONEDARK` | faint | `#8B93A1` | `#8C94A2` |
| `AYU` | base | `#1A1F29` | `#181C26` |
| `AYU` | raised | `#2C3242` | `#282E3B` |
| `AYU` | hover | `#343B4D` | `#343B4C` |
| `AYU` | line | `#434A5C` | `#3A4150` |
| `AYU` | text | `#D9D7CF` | `#CCCAC2` |
| `AYU` | dim | `#C0BEB5` | `#9CA8BD` |
| `AYU` | faint | `#9AA1AB` | `#8691A3` |
