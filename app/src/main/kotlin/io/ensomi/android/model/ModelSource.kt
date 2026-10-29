package io.ensomi.android.model

/** Where the chart model comes from: the `onnx/` directory of `sed-i/ensomi-r2` on Hugging Face, at one tag. */
object ModelSource {
    /** The package tag the app installs; the one place it is named. The app never reads `main`. */
    const val PINNED_REVISION = "r2-1.0.1"

    const val BASE_URL = "https://huggingface.co/sed-i/ensomi-r2/resolve/$PINNED_REVISION/onnx/"

    /** The model card, which says what each part of the licence covers. */
    const val MODEL_CARD = "https://huggingface.co/sed-i/ensomi-r2"

    /** The package's size by its manifest at the pinned tag (30 files), shown before the manifest is fetched. */
    const val PACKAGE_BYTES = 120_377_244L
}
