# Add project specific ProGuard rules here.
# Keep Chaquopy Python runtime
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**
# Keep MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
