# The JNI bridge is linked by name: the C++ symbols encode the fully-qualified Java class and
# method (Java_gopesh_kibitz_engine_stockfish_Stockfish_nativeStart and friends). If R8 renames
# the class or its native methods, the linkage silently breaks and the engine fails to start in
# release builds only — one of the easiest release-only crashes to ship by accident.
-keepclasseswithmembernames,includedescriptorclasses class gopesh.kibitz.engine.stockfish.Stockfish {
    native <methods>;
}
-keep class gopesh.kibitz.engine.stockfish.Stockfish { *; }

# Room generates its implementations at compile time and needs no reflection, but the entities
# are referenced by generated code and by column name, so keep their members intact.
-keep class gopesh.kibitz.data.GameRecord { *; }
-keep class gopesh.kibitz.data.MoveRecord { *; }
-keep class gopesh.kibitz.data.DrillAttempt { *; }
-keep class gopesh.kibitz.data.QualityCount { *; }
-keep class gopesh.kibitz.data.AccuracySummary { *; }
-keep class gopesh.kibitz.data.DrillProgress { *; }
