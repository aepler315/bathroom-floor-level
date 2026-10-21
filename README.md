# Floor Level

A small Android app that maps how a bathroom floor slopes, one square foot at
a time, using the phone's accelerometer. It then works out where water runs and
explains in plain words why it is or isn't reaching the drains.

It was written to diagnose a new patient-bathroom floor that doesn't drain:
after a shower, up to 3.5 gallons of water ends up soaked into towels.

## Using it

1. Measure the room and round up to whole feet. Width = left wall to right
   wall, length = back wall to door, as seen standing in the doorway.
2. Enter the size and press **START**.
3. Starting at the back-left corner, lay the phone **face up in the middle of
   each square, top edge pointing at the back wall**. Let go. When it beeps,
   pick it up and move to the yellow square. Rows snake back and forth.
4. **First square only:** after the first beep, set it down on the same square
   again, turned around (top pointing at you). This measures the phone's own
   tilt (camera bump, case, sensor offset) so it can be removed from every
   other reading.
5. **Skip** squares you can't reach (toilet, cabinet). **Redo last** if you
   bumped it. **Finish now** to stop early.
6. On the results screen, tap the drain squares (for example the round drain
   at the shower and the square drain in the room), then read the findings.
   **Share report** sends a text report with a height table and per-square CSV.

The scan is saved as you go. If the app is closed, **Resume** picks up where
you left off.

## What it reports

- **Map:** colour = height relative to the lowest drain (blue below,
  orange above). Arrows point downhill (red = away from the nearest drain).
  Blue circles = dips where water stands. Red outline = water from this square
  never reaches a drain.
- **Findings**, worst first:
  - a drain set higher than the floor beside it;
  - the floor's lowest spot not being a drain;
  - what share of the floor drains, and where the rest of the water ends up;
  - dips that hold water, with an estimated volume in gallons;
  - the whole floor tilted one way (e.g. toward the door) instead of toward
    the drains;
  - squares that are nearly flat (< 1/8 in per ft) or slope away from the
    nearest drain;
  - squares steeper than ~1/4 in per ft (1:48), which is a common
    accessibility limit.
  - If the floor shape looks fine, it says so and points at the drains
    themselves (clog, capacity, grate) or at shower spray.

## How it works

- `PlacementDetector` watches raw accelerometer samples (~50 Hz). It detects
  a lift (a sustained change, so screen taps don't count) and a flat, still
  set-down. Then it averages 1.5 s of readings. A bump while measuring
  restarts the measurement.
- `ScanState` turns each averaged gravity vector into floor slope (inches of
  rise per foot, rightward and toward the door). It removes the phone's own
  tilt using the 180° turn on the first square: `bias = (A + B) / 2`.
- `FloorAnalysis` fits a height map to the slopes by least squares. It follows
  water downhill (8 directions) from every square, floods from the drains to
  find dips, fits a plane to spot an overall tilt, and writes the findings.

## Accuracy

Phone accelerometers, averaged and calibrated this way, are good to roughly
±0.05 in per ft per square. Heights are added up from slopes, so treat
differences under about 1/16 in as noise. Tile lippage and grout under the
phone affect single squares. One-foot squares miss dips and humps smaller
than that. Volumes are estimates.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. CI builds it on
every push and attaches it to the run as the `floor-level-debug-apk`
artifact. `ScreenRenderTest` (Robolectric) renders the three screens to
`app/build/screens/` for a visual check.

Installing a debug APK means allowing "install unknown apps" for whatever
opens the file (Files, Chrome, Drive).
