# Drive Bridge release rules for R8.

# Keep class and method names: the diagnostic log and crash traces the driver
# shares print exception and class names, and an obfuscated trace is useless
# without the mapping file. Unused code is still removed and the rest optimized.
-dontobfuscate

# Line numbers in crash traces.
-keepattributes SourceFile,LineNumberTable

# The app does not use reflection, serialization or JNI; Android components in
# the manifest and AndroidX fragments/preferences are kept by the rules AAPT
# and the libraries supply.
