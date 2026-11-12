# youtubedl-android parses yt-dlp's JSON with Jackson databind, which is entirely
# reflective. Without these the R8 pass in Gramophone's release build strips the
# mapper targets and every getInfo() call dies with an InvalidDefinitionException.
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-keepclassmembers class com.yausername.youtubedl_android.mapper.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry

# youtubedl-android ships no consumer rules of its own. It maps yt-dlp's JSON
# with Jackson by reflection and executes CPython through a small Kotlin
# facade, both of which R8 would otherwise strip or rename.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn com.yausername.**

# youtubedl-android unpacks its CPython payload with commons-compress, whose
# ExtraFieldUtils registers extra-field classes by reflection and needs them
# concrete and constructible. Seen in a release build: "AsiExtraField is not
# a concrete class" from ExtraFieldUtils.<clinit>, every download dead.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
