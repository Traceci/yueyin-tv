-keepattributes *Annotation*
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.yueyin.tv.core.data.api.**$$serializer { *; }

# 拼音库（pinyin4j，纯 Java）
-keep class net.sourceforge.pinyin4j.** { *; }
-dontwarn net.sourceforge.pinyin4j.**

# WebView JS 桥：nativeReady / nativeLog / nativeEvent / nativeRequest / onResult 等方法只被
# 隐藏 WebView 里的 JS 反射调用，full mode 下 R8 会当成「未引用」而移除或改名，
# 一旦移除音源运行时直接失效（取链 / 搜索 / 歌词全挂）。必须显式保留。
# 同时保留 @JavascriptInterface 注解类本身：WebView 靠这个注解在运行时反射定位桥方法，
# 注解被剥掉方法虽然还在，JS 照样调不动。
-keep @interface android.webkit.JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
