# Keep rules are added per feature (kotlinx.serialization models, SQLDelight) in later lots.

# LiteRT-LM (ADR-021): the native library calls back into these classes by name (JNI) and the AAR ships no
# consumer rules.
-keep class com.google.ai.edge.litertlm.** { *; }
