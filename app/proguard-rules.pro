# libmpv 的 JNI 入口必须保留，否则运行时 UnsatisfiedLinkError / 回调丢失
-keep class dev.jdtech.mpv.MPVLib { *; }
-keepclassmembers class dev.jdtech.mpv.MPVLib$EventObserver { *; }
