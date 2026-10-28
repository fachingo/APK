# Camera update 0.1.2

Install this APK over 0.1.0/0.1.1. Package and signing identity are unchanged,
versionCode is 3, Room remains version 2, and no captures, archives or API settings
are removed. Do not uninstall or clear Android storage to update.

## Resolution and zoom

Tap the resolution selector above the shutter, or use Settings → Photo resolution:

| Choice | Requested sensor dimensions |
| --- | --- |
| 1.2 MP · smaller files | 1280 × 960 |
| 3 MP · balanced (new default) | 2048 × 1536 |
| 6 MP · more detail | 2880 × 2160 |
| 12 MP · high detail | 4032 × 3024 |
| Maximum available | Highest supported CameraX capture size |

Camera hardware chooses the closest supported size, preferring a lower size before
a higher fallback. The actual negotiated dimensions appear in the preview.
The selection persists across launches. The old maximum-resolution switch is
replaced; upgrades start at 3 MP unless a new resolution has already been chosen.
Lower resolutions reduce bytes and save time, but cannot retain arbitrarily tiny
cover text or barcode bars. Use a higher resolution when those details are needed.
Existing photographs are never resized or recompressed.

Pinch the preview or drag its zoom slider. The numeric value follows the camera's
actual zoom state; 1× resets zoom. Limits come from the active lens, including
sub-1× zoom where supported. Tap still focuses; pinch gestures do not trigger focus.
Digital zoom cannot restore missing detail. The preview fits the whole camera
frame instead of filling the screen by cropping its sides.

## Capture response

Fast capture is on by default (Settings → Fast capture). It opts into CameraX’s
experimental zero-shutter-lag mode, which requests zero
shutter lag with a low-latency fallback on unsupported hardware. Flash/HDR can
disable zero shutter lag; HDR uses the low-latency capture mode. JPEG quality is
95 in fast mode. Turn fast capture off for quality-priority JPEG 100 capture.
The app retains the actual camera JPEG and EXIF in either mode.

Haptic feedback/optional tone acknowledge an accepted shutter press immediately.
The camera is available for another photo as soon as CameraX reports the JPEG
saved. SHA-256 verification and durable finalization run in the background, with a
maximum of three pending saves. Expensive file verification does not hold the
camera reservation lock. Photo IDs, roles and order are reserved in a durable
journal before capture. Next, Finish and edits remain blocked until saving ends,
so rapid captures cannot cross product boundaries. Recovery handles every pending
file after an interruption. No original deletion or weaker checksum rules are used.

Near-instant exposure cannot be guaranteed by software on all phones. Autofocus,
flash, HDR, maximum resolution, low light, camera processing and storage affect
actual latency. Physical Pixel timing and sustained capture must still be measured.

## Barcode detail

ZXing 3.5.4 remains the model-free open-source decoder. The scanner first tries the
full frame, then scans overlapping 1536-pixel regions of larger original JPEGs at
native resolution. This avoids shrinking a small barcode into indistinct bars
before attempting to read it. Edge tiles overlap and cover all four image edges;
only one tile is held at a time, and image scans are serialized to limit memory.
Both automatic back-photo scanning and explicit offline recognition use this path.
Orientation, inverted-print and local/global threshold attempts remain enabled.
Different decoded product codes still require review rather than silently choosing
one. Original JPEGs and EXIF remain untouched.

Barcode recognition is performed on saved photographs in the background. This
update does not add a continuous live scanner or automatic zoom. Already-saved
photos can be rescanned through Batches → barcode review → Rescan back photo.
Blur, glare, severe perspective distortion and too few recorded pixels can still
make a barcode unreadable.

## Landscape

The app uses a side navigation rail in landscape. The camera keeps its preview on
the left and capture/resolution/Next controls on the right; the control panel can
scroll on very short windows. Rotation updates camera output/preview rotation
without destroying the Activity or canceling an in-flight shutter. Long form and photo-review dialogs scroll within short windows. The active
screen, batch, selected lens and camera controls remain in place. Portrait uses
the familiar bottom navigation and controls below the preview.
