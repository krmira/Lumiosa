# Lumiosa

A fast, light, dark-green PDF reader. Pages are drawn straight from the PDF with
Android's built-in renderer, so files open instantly and keep their real layout.

- Android 5.0+ (minSdk 21), one universal APK; pages are drawn by Android's built-in renderer
- Pinch, A-/A+ or double-tap to zoom (starts at 2x so body text is easy to read, up to 5x); the zoom level is remembered
- Reflow mode (the ¶ button): text is re-wrapped to your screen so lines never run off the edge. It loads in the background and prefetches nearby pages. Scanned (image-only) pages have no text and show a hint
- Swipe left/right to flip pages
- Night mode recolours pages to the dark green reading panel (toggle with the half-circle button)
- Side margins are trimmed automatically so text is larger on small screens
- History of recent PDFs with thumbnails, and it remembers your page

Build: push to GitHub, run the "Build Lumiosa APK" action, download the `Lumiosa-apk` artifact.
