-keep class com.radio.app.models.** { *; }

# v2.4.102: Keep ONNX Runtime classes (used for Silero VAD)
-keep class ai.onnxruntime.** { *; }

# v2.4.102: Keep TFLite classes (used for YAMNet)
-keep class org.tensorflow.lite.** { *; }
