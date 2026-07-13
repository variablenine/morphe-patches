# morphe-patches

A personal [Morphe](https://morphe.software) patch source. Not a community bundle — it just
isn't submitted anywhere; the repo can stay public and Morphe consumes it from this repo's own
GitHub releases.

Add as a source in Morphe (after a release exists): `https://github.com/variablenine/morphe-patches`

## `BrainrotDetector` — smart YouTube comment spam detection

`extensions/extension/src/main/java/app/variablenine/extension/youtube/patches/components/BrainrotDetector.java`

Detects "brainrot" spam comments — the endless obfuscated reshuffles of a tiny meme vocabulary
like `anti spiral viral`, `anti viral`, `anti anti spiral`, `fix mojang bedrock` — **without** a
flat keyword blacklist and **without** hiding legitimate comments that merely mention those words.

1. **De-obfuscate**: Unicode NFKD, leetspeak/homoglyph folding (`f1x m0jang → fix mojang`),
   zero-width strip, letter-spacing collapse (`s p i r a l → spiral`), concatenation splitting
   (`fixmojangbedrock → fix mojang bedrock`), repeat collapse (`spiraaaal → spiral`).
2. **Score density, not phrases**: match each token against a weighted meme lexicon, then measure
   how meme-dominated the comment is vs. how much real content it has.
3. **Hide only short, meme-dominated comments**; keep substantive prose that mentions the words.

| Input | Verdict |
|---|---|
| `anti spiral viral`, `anti viral`, `anti anti spiral` | **HIDE** |
| `f1x m0jang b3dr0ck`, `s p i r a l`, `anti-spiral!!!` | **HIDE** |
| `the anti-spiral arc was peak animation` | keep |
| `fix bedrock please mojang, the redstone is broken` | keep |
| `this antiviral medication actually worked` | keep |

Tuning knobs (lexicon, weights, density/length thresholds) are constructor parameters.

### Verify the detector (no Gradle needed)

```sh
cd extensions/extension/src
javac main/java/app/variablenine/extension/youtube/patches/components/BrainrotDetector.java -d /tmp/bd
javac -cp /tmp/bd test/java/app/variablenine/extension/youtube/patches/components/BrainrotDetectorSelfTest.java -d /tmp/bd
java  -cp /tmp/bd app.variablenine.extension.youtube.patches.components.BrainrotDetectorSelfTest
# -> RESULT: 22 passed, 0 failed
```

### Status: the patch that runs it against live comments is not wired yet

The detector is complete and verified. The remaining piece is the Morphe patch that feeds live
YouTube comment text into it and hides the component. That requires Morphe's Litho filter
pipeline; wiring it as a standalone add-on bundle (running alongside the official Morphe patches)
is the open design decision and needs on-device validation against the recommended YouTube version.

<!-- PATCHES_START EXPANDED -->
<!-- Do not modify this section by hand. The patch list is generated when release.yml creates a new release. -->

#### A list of your patches will automatically be shown here after your first patches release is created.

<!-- PATCHES_END -->

## Build & release

- Develop on a feature branch; open a PR into `dev`. Use semantic commits (`feat:` / `fix:` create
  releases, `chore:` does not).
- Build locally (requires a GitHub token with `read:packages` for Morphe's registry):
  `./gradlew buildAndroid` → `patches/build/libs/patches-*.mpp`.
- Merging `dev` → `main` runs `release.yml` (semantic-release): it builds the `.mpp`, publishes a
  GitHub release, and updates `patches-list.json` / `patches-bundle.json` that Morphe reads.
- One-time: enable **Settings → Actions → General → "Allow GitHub Actions to create and approve
  pull requests"** so the release automation can run.

## License

GPLv3 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
