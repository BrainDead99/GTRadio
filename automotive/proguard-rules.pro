# Automotive ProGuard / R8 rules

# Preserve line numbers and source file names for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod,Signature

# Keep Android components
-keep public class * extends android.app.Activity {
    public <init>();
    *;
}
-keep public class * extends android.app.Service {
    public <init>();
    *;
}
