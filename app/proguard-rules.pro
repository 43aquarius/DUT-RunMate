# RunMate proguard (minify disabled by default; rules kept for safety)
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class com.dut.runmate.data.** { *; }
