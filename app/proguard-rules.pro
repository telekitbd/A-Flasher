# Standard R8/ProGuard rules for A Flasher.
# Shrinks, optimizes and renames classes/methods in release builds so the
# compiled code is harder to read than the raw Kotlin source. This is
# ordinary app hardening, not a way to hide malicious behaviour.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Activities/receivers declared in AndroidManifest.xml are kept
# automatically by the Android Gradle Plugin's built-in rules; no extra
# -keep is needed here, so MainActivity's own code gets fully renamed
# and shrunk like everything else.

-dontwarn kotlin.**
