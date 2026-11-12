# youtubedl-android parses yt-dlp's JSON with Jackson databind, which is entirely
# reflective. Without these the R8 pass in Gramophone's release build strips the
# mapper targets and every getInfo() call dies with an InvalidDefinitionException.
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-keepclassmembers class com.yausername.youtubedl_android.mapper.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
