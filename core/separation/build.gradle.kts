// Karaoke vocal removal with Deezer's Spleeter model (MIT) run by ONNX Runtime. Plain Kotlin, so the
// same code runs in the app (with onnxruntime-android) and in unit tests on a PC (desktop runtime).
plugins {
    alias(libs.plugins.spotineil.jvm.library)
}

dependencies {
    // The Android app supplies the runtime (onnxruntime-android); its Java API is identical.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
}
