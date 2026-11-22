# Common consumer rules for GTRadio
# Automatically applied to any consumer module (e.g. :app, :automotive)

# Preserve line numbers and source file names for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod,Signature

# Keep all native JNI classes and methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.sc.gtradio.media.gen1.At3AudioArchive {
    *;
}
-keep class com.sc.gtradio.media.gen1.At3AudioArchive$* {
    *;
}

-keep class com.sc.gtradio.media.rpf7.NativeMp3Decoder {
    *;
}
-keep class com.sc.gtradio.media.rpf7.NativeMp3Decoder$* {
    *;
}

# Media3 / ExoPlayer reflection & audio processor pipeline
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

-keep class com.sc.gtradio.media.RadioAudioProcessor {
    *;
}
-keep class com.sc.gtradio.media.RadioAudioProcessor$* {
    *;
}

-keep class com.sc.gtradio.media.gen1.AdfXorDataSource {
    *;
}
-keep class com.sc.gtradio.media.gen1.AdfXorDataSource$* {
    *;
}

-keep class com.sc.gtradio.media.GTRadioMusicService {
    *;
}
-keep class com.sc.gtradio.media.GTRadioMusicService$* {
    *;
}
