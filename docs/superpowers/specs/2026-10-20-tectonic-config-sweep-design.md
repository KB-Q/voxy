# Tectonic config sweep: terrain shape and coherence

Find a Tectonic 3.0.17 (Minecraft 1.20.1) config with the best terrain shape and coherence. Each config generates the same world area with the same seed. The autotest harness records 8 screenshots, terrain metrics and performance metrics per config. Each config receives a visual score (assigned by hand, independent of performance) and a performance score computed from the metrics; the final score is a weighted average of the two.

The first stage has 20 runs. A second stage of up to 20 runs refines around the best levels found in the first.

## Fixed setup

| Item | Value |
|---|---|
| Seed | `-495328105946124347` (Better Adventures+ world `New World (16)`, spawn at y=227) |
| Generated area | Chunky circle, radius 1000, centre (0,0) |
| Time per world | 10 minutes (see open decision 2) |
| Tectonic options not varied | Mod defaults, except biome noise: temperature and vegetation scale 0.06, multiplier 1.1 (the Large Biomes preset, kept constant in every run). `max_y` 640, `min_y` −64 |
| Game settings | Same Sodium, Voxy and video settings in every run, same render distance, clouds off, time fixed at noon, weather clear |
| Mods | Tectonic 3.0.17, Lithostitched 1.4.11 (Tectonic dependency), Sodium, Lithium, FerriteCore, ModernFix, C2ME, Chunky and the Voxy GL 4.1 build. Jars copied from the Better Adventures+ instance. Terralith is an open decision (1) |
| JVM | Java 25, same flags as the Better Adventures+ instance (ZGC, 8 GB heap) |
| Failures | A run that crashes or exceeds the memory limit is recorded as failed and not retried |

## Varied options

Six options, three levels each. Levels were chosen around the mod default.

| Option | Default | Levels | Effect (Tectonic tooltip) |
|---|---|---|---|
| `global_terrain.vertical_scale` | 1.125 | 1.0 / 1.25 / 1.5 | Vertically stretches terrain above sea level |
| `global_terrain.elevation_boost` | 0.0 | 0.0 / 0.1 / 0.2 | Increases the effect of vertical scale on mountains and plateaus faster than on lowlands |
| `continents.continents_scale` | 0.13 | 0.08 / 0.13 / 0.2 | Horizontal scale of continents; lower = larger continents and oceans between them |
| `continents.erosion_scale` | 0.25 | 0.15 / 0.25 / 0.35 | Horizontal scale of erosion, mainly mountains; lower = thicker mountain ranges |
| `continents.ridge_scale` | 0.25 | 0.15 / 0.25 / 0.35 | Horizontal scale of ridge noise, mainly rivers and the plateaus around them; lower = wider rivers |
| `continents.ocean_offset` | −0.8 | −1.0 / −0.8 / −0.6 | Skews terrain towards or away from oceans; lower = more oceans |

A full grid is 3^6 = 729 configs. Runs T01–T18 use six 3-level columns of the Taguchi L18 orthogonal array. Each level of each option appears in 6 runs, and every pair of options has each of its 9 level combinations exactly twice, so the mean score per level estimates each option's main effect. Interactions between options are not separable with 18 runs. The seventh 3-level column of the array is unused.

## Run matrix

| Run | vertical_scale | elevation_boost | continents_scale | erosion_scale | ridge_scale | ocean_offset | Status | Visual score |
|---|---|---|---|---|---|---|---|---|
| T01 | 1.0 | 0.0 | 0.08 | 0.15 | 0.15 | −1.0 | pending | |
| T02 | 1.0 | 0.1 | 0.13 | 0.25 | 0.25 | −0.8 | pending | |
| T03 | 1.0 | 0.2 | 0.2 | 0.35 | 0.35 | −0.6 | pending | |
| T04 | 1.25 | 0.0 | 0.08 | 0.25 | 0.25 | −0.6 | pending | |
| T05 | 1.25 | 0.1 | 0.13 | 0.35 | 0.35 | −1.0 | pending | |
| T06 | 1.25 | 0.2 | 0.2 | 0.15 | 0.15 | −0.8 | pending | |
| T07 | 1.5 | 0.0 | 0.13 | 0.15 | 0.35 | −0.8 | pending | |
| T08 | 1.5 | 0.1 | 0.2 | 0.25 | 0.15 | −0.6 | pending | |
| T09 | 1.5 | 0.2 | 0.08 | 0.35 | 0.25 | −1.0 | pending | |
| T10 | 1.0 | 0.0 | 0.2 | 0.35 | 0.25 | −0.8 | pending | |
| T11 | 1.0 | 0.1 | 0.08 | 0.15 | 0.35 | −0.6 | pending | |
| T12 | 1.0 | 0.2 | 0.13 | 0.25 | 0.15 | −1.0 | pending | |
| T13 | 1.25 | 0.0 | 0.13 | 0.35 | 0.15 | −0.6 | pending | |
| T14 | 1.25 | 0.1 | 0.2 | 0.15 | 0.25 | −1.0 | pending | |
| T15 | 1.25 | 0.2 | 0.08 | 0.25 | 0.35 | −0.8 | pending | |
| T16 | 1.5 | 0.0 | 0.2 | 0.25 | 0.35 | −1.0 | pending | |
| T17 | 1.5 | 0.1 | 0.08 | 0.35 | 0.15 | −0.8 | pending | |
| T18 | 1.5 | 0.2 | 0.13 | 0.15 | 0.25 | −0.6 | pending | |
| R1 | 1.125 | 0.0 | 0.13 | 0.25 | 0.25 | −0.8 | pending | |
| R2 | 1.2 | 0.0 | 0.08 | 0.35 | 0.2 | −0.8 | pending | |

- R1: Tectonic defaults for the six options (the current Better Adventures+ config).
- R2: the six options from `BA+ 1.21/config/tectonic.json` (Oct 13), the closest surviving copy of the custom config that the Large Biomes preset overwrote. Its cave and ocean depth values are not used, so R2 differs from the other runs only in the six options.

## Procedure per run

1. Write `config/tectonic.json`, launch the client, create a world with the fixed seed and settings.
2. At world load, start Chunky (circle, radius 1000, centre 0,0) and the 10-minute window. Performance sampling starts at the same time.
3. After the window, take the 8 screenshots below. Each viewpoint waits for Voxy to finish loading LoDs in view before capturing.
4. Collect terrain metrics from the server, write the report, quit.

Yaw follows Minecraft's convention (0 = south, 90 = west, 180 = north, −90 = east); positive pitch looks down. P6–P8 were drawn once from a seeded random generator and are identical in every run.

| Viewpoint | Position | Yaw | Pitch | Facing |
|---|---|---|---|---|
| P1 | (0, 600, 0) | 0 | 90 | straight down |
| P2 | (0, 250, 600) | 180 | 0 | north |
| P3 | (600, 250, 0) | 90 | 0 | west |
| P4 | (0, 250, −600) | 0 | 0 | south |
| P5 | (−600, 250, 0) | −90 | 0 | east |
| P6 | (109, 250, −713) | 114 | 10 | west-northwest |
| P7 | (741, 250, 306) | −2 | 9 | south |
| P8 | (−151, 250, 308) | −91 | 7 | east |

At FOV 70, P1 covers about ±420 blocks around the origin.

## Terrain metrics

| Metric | Source and definition |
|---|---|
| Surface height: max, median, p95, p5 | `MOTION_BLOCKING_NO_LEAVES` heightmap of every generated chunk in the circle |
| Relief | p95 − p5 of surface height |
| High terrain share | Share of surface columns above y=200 and above y=300 |
| Height cap reached | Whether any column reaches `max_y` (terrain clipped at 640) |
| Water share | Share of surface columns whose top block is water |
| C, E, W, PV, D distributions | Mean, standard deviation and percentiles of the world's noise router functions `continents`, `erosion`, `ridges` (W) and `depth`, sampled on a 32-block grid at the surface; PV is derived from W with vanilla's peaks-and-valleys formula. These are the values on F3's "NoiseRouter" and "Biome builder" lines, after Tectonic's config is applied |
| Cave volume | Air blocks below the surface as a share of underground volume (y from −64 to the surface), from every 4th chunk |
| Mean slope | Mean absolute surface height difference between neighbouring columns |
| Cliff share | Share of neighbouring column pairs with a height difference above 8 blocks |
| Overhang share | Share of columns with an air gap of at least 2 blocks within 64 blocks below the top solid block (arches, overhangs, floating terrain) |
| Generation | Chunks generated within the window, time until Chunky finished (if it did), chunks per second |

## Performance metrics

Reported separately for the generation phase (Chunky running) and the post-generation phase (after Chunky finishes, including the screenshot tour).

| Metric | Definition |
|---|---|
| FPS avg, min, max, 1% low | From client frame times; 1% low is the mean FPS of the slowest 1% of frames |
| Frame time median, p99 | Client frame time in ms |
| Frame drops | Frames longer than 50 ms |
| Lag spikes | Frames longer than 250 ms |
| Server tick time avg, p95, max | Integrated server tick duration in ms |
| Slow ticks | Server ticks longer than 50 ms |
| Heap used peak, avg | JVM heap after each sample, sampled once per second |
| GC pauses | Count and total pause time from the JVM garbage collector beans |
| Process memory peak | Resident memory of the game process, sampled once per second outside the JVM |

## Scoring

- Visual score: 1–10 per run, from the screenshots only.
- Performance score: 0–10, the mean of normalised performance metrics across runs (best run = 10, worst = 0 per metric), weighting to be fixed before scoring.
- Final score = w × visual + (1 − w) × performance. Proposed w = 0.7.
- Main effects: mean final score per level of each option over T01–T18. The best level per option forms the predicted best config, which the second stage tests together with neighbouring levels.

## Open decisions

| # | Decision | Recommendation |
|---|---|---|
| 1 | Include Terralith | Yes. With Terralith installed, Tectonic applies its "terratonic" overlay, which replaces the continents, ridges and roughness functions. Without it, the seed does not reproduce the Better Adventures+ terrain and results may not transfer |
| 2 | Meaning of "10 minutes per world" | Window starts at world load; generation and post-generation idle both fall inside it; screenshots are taken after it. Incomplete generation is recorded as a percentage |
| 3 | Viewpoint inside terrain | If the camera position is below the surface, raise it to surface + 20 and record the change |
| 4 | FOV | 70 for all viewpoints |
| 5 | Score weight w | 0.7, fixed before visual scoring |
