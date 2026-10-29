package io.ensomi.android.model

/**
 * Where the chart model comes from: the `onnx/` directory of `sed-i/ensomi-r2` on Hugging Face, at one tag, downloaded
 * by the app or copied from files the player picks (the same package, by its manifest's pinned SHA-256).
 */
object ModelSource {
    /** The package tag the app installs; the one place it is named. The app never reads `main`. */
    const val PINNED_REVISION = "r2-1.0.1"

    /**
     * The SHA-256 of the pinned tag's manifest (`ensomi-package.json`). Both ways of installing, the download and the
     * files the player picks, accept only the package whose manifest has exactly this hash, so every file the manifest
     * lists, checked by its own size and SHA-256, is that package's. It changes with [PINNED_REVISION] and only with it.
     */
    const val PINNED_MANIFEST_SHA256 = "6e05317427cc24f3d46c0e8dcdd7a44078954437129c60f8797acb80bea6a1fb"

    const val BASE_URL = "https://huggingface.co/sed-i/ensomi-r2/resolve/$PINNED_REVISION/onnx/"

    /** The model card, which says what each part of the licence covers. */
    const val MODEL_CARD = "https://huggingface.co/sed-i/ensomi-r2"

    /** The package's size by its manifest at the pinned tag (30 files), shown before the manifest is fetched. */
    const val PACKAGE_BYTES = 120_377_244L
}
