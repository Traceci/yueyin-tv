-keepattributes *Annotation*
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.fnmusic.tv.core.data.api.**$$serializer { *; }

# 拼音库（pinyin4j，纯 Java）
-keep class net.sourceforge.pinyin4j.** { *; }
-dontwarn net.sourceforge.pinyin4j.**
