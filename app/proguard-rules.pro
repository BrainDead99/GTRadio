# App ProGuard / R8 rules for GTRadio

# Preserve line numbers and source file names for readable crash stack traces in Diagnostic Reports
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod,Signature

# Custom Views referenced from layout XML
-keep class com.sc.gtradio.VolumeBarView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    *;
}

-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Navigation Fragments referenced via NavGraph / XML reflection
-keep class com.sc.gtradio.GroupList {
    public <init>();
    *;
}
-keep class com.sc.gtradio.RadioList {
    public <init>();
    *;
}
-keep class com.sc.gtradio.NowPlayingFragment {
    public <init>();
    *;
}
-keep public class * extends androidx.fragment.app.Fragment {
    public <init>();
    *;
}

# Activities
-keep class com.sc.gtradio.MainActivity {
    public <init>();
    *;
}
-keep class com.sc.gtradio.TracklistActivity {
    public <init>();
    *;
}
-keep class com.sc.gtradio.SettingsActivity {
    public <init>();
    *;
}
-keep public class * extends android.app.Activity {
    public <init>();
    *;
}

# Music Service & Media Components
-keep class com.sc.gtradio.media.GTRadioMusicService {
    public <init>();
    *;
}
-keep class com.sc.gtradio.media.RadioAudioProcessor { *; }
-keep class com.sc.gtradio.media.gen1.AdfXorDataSource { *; }

# Media3 / ExoPlayer reflection
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Native JNI methods and classes
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.sc.gtradio.media.gen1.At3AudioArchive** { *; }
-keep class com.sc.gtradio.media.rpf7.NativeMp3Decoder** { *; }
