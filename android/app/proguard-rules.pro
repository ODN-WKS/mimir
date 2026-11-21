# The Rust side (phone-core) calls into nothing, but Kotlin calls it by these names over JNI.
-keep class io.github.odnwks.mimir.link.Native { *; }

# WorkManager makes its database by reflection (WorkDatabase_Impl's empty constructor). Without
# this the shrunk app crashed as it started, before any screen showed (found on the S25).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }

# Standalone mode: Kotlin calls the model runner by these names, and the runner calls back
# PieceSink.onPiece by name, so neither may be renamed.
-keep class io.github.odnwks.mimir.standalone.Llm { *; }
-keep interface io.github.odnwks.mimir.standalone.PieceSink { *; }
-keep class * implements io.github.odnwks.mimir.standalone.PieceSink { boolean onPiece(byte[]); }

# The natural voices (sherpa-onnx): its native side reads the config classes' fields and makes
# GeneratedAudio by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
