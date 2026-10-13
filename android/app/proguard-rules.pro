# App-specific R8 rules. Library rules come bundled with each dependency.
#
# Nothing in the app reflects on its own classes: JSON goes through kotlinx.serialization's
# generated serializers and Firestore documents are read and written field by field, so no
# keep rules are needed for app classes. If you add Firestore toObject()/data-class mapping or
# reflection, add rules here.

# Keep line numbers in crash reports; Play Console deobfuscates them with the uploaded mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
