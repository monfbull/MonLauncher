# Keep file names and line numbers in crash traces, so diagnostics reports can be decoded with
# tools/retrace.sh and the mapping saved for that release in release-mappings/.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
