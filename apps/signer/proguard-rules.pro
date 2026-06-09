-optimizationpasses 20
-overloadaggressively

#-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-allowaccessmodification
-repackageclasses ''
-renamesourcefileattribute SourceFile
-dontskipnonpubliclibraryclasses

-dontwarn com.fasterxml.jackson.databind.ext.Java7SupportImpl
-keep class com.fasterxml.jackson.databind.ext.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.w3c.dom.**
-dontwarn com.fasterxml.jackson.databind.ext.DOMSerializer