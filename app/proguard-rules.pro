# sherpa-onnx JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.simplenarrator.**$$serializer { *; }
-keepclassmembers class app.simplenarrator.** { *** Companion; }
-keepclasseswithmembers class app.simplenarrator.** { kotlinx.serialization.KSerializer serializer(...); }
# PDFBox
-dontwarn org.bouncycastle.**
-dontwarn org.apache.**
# Hörbuch-Bereich: kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-dontnote kotlinx.serialization.**
# PDFBox-Android lädt Schriften und Filter teils über Namen
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
# MP3-Encoder (jump3r, reiner Java-Port von LAME)
-keep class de.sciss.jump3r.** { *; }
-dontwarn de.sciss.jump3r.**
-dontwarn javax.sound.**
# jsoup: optionale Regex-Bibliothek
-dontwarn com.google.re2j.**
# Commons Compress: optionale Kompressionsbibliotheken
-dontwarn org.tukaani.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
# Tesseract4Android: JNI greift über Namen auf Felder und Methoden zu
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
