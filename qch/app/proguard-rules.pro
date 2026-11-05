# Keep the metadata used by Gson, Kotlin, ServiceLoader and TUIKit annotations.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# TUIRouter indexes manifest activities by their simple class name. Component
# names therefore must stay stable even though their implementation can shrink.
-keepnames class * extends android.app.Activity
-keepnames class * extends android.app.Service
-keepnames class * extends android.content.BroadcastReceiver
-keepnames class * extends android.content.ContentProvider
-keepnames class * extends android.app.Application

# TUIKit cannot directly depend on the app module; its authenticated business
# client resolves this field by name. Keep the field in minified QA/release APKs.
-keepclassmembers class com.qchm.xyz.DemoApplication {
    public static java.lang.String NewAddress;
}

# Android inflates custom views and XML click handlers reflectively.
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void *(android.view.View);
}
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# TUIChat stores message ViewHolder classes in a registry and invokes their
# View constructors reflectively. R8 cannot infer this call from the Class map.
-keepclassmembers class * extends androidx.recyclerview.widget.RecyclerView$ViewHolder {
    public <init>(android.view.View);
}

# Message and quote bean implementations are also instantiated from registered
# Class objects while parsing custom, reply and quote messages.
-keepclassmembers class com.tencent.qcloud.tuikit.tuichat.bean.message.** {
    public <init>();
}

# TUIKit discovers module initializers through META-INF/services and reads their
# runtime dependency annotations. Preserve provider names and constructors.
-keep class * implements com.tencent.qcloud.tuicore.interfaces.TUIInitializer {
    public <init>();
}
-keep @com.google.auto.service.AutoService class * { public <init>(); }
-keep @interface com.tencent.qcloud.tuicore.annotations.**

# Gson uses field reflection. Business DTOs and TUIKit message payloads keep
# their wire field names while their class and method names may still shrink.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.qchm.xyz.bean.** { <fields>; }
-keep class com.qchm.xyz.bean.CaptchaBean {
    public <init>();
    <fields>;
}
-keepclassmembers class com.qchm.xyz.push.OfflinePushParamBean { <fields>; }
-keep class com.qchm.xyz.imaccess.ImSessionRepository$SessionEnvelope {
    <init>();
    <fields>;
}
-keep class com.qchm.xyz.imaccess.ImSessionRepository$SessionData {
    <init>();
    <fields>;
}
-keepclassmembers class com.tencent.qcloud.tuicore.push.** { <fields>; }
-keepclassmembers class com.tencent.qcloud.tuikit.**.bean.** { <fields>; }
-keepclassmembers class com.tencent.qcloud.tuikit.**.model.** { <fields>; }

# Android serialization contracts.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
}

# JNI entry points and the private IM SDK field read by ChatMessageParser.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers class com.tencent.imsdk.v2.V2TIMMessage {
    *** message;
}

# WebView JavaScript bridges and Glide generated integration points.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Vendor SDKs with native or reflective entry points. Their own consumer rules
# do most of the work; these rules protect the documented public callbacks.
-keep class com.tencent.imsdk.** { *; }
-keepnames class com.tencent.imsdk.**
-dontwarn com.tencent.imsdk.**
-keep class com.tencent.liteav.** { *; }
-keepnames class com.tencent.liteav.**
-dontwarn com.tencent.liteav.**
# LiteAV native code resolves these UI and TRTC types by their original JNI
# signatures. Renaming AndroidViewEventListener makes video startup abort.
-keep class com.tencent.rtmp.** { *; }
-keepnames class com.tencent.rtmp.**
-dontwarn com.tencent.rtmp.**
-keep class com.tencent.trtc.** { *; }
-keepnames class com.tencent.trtc.**
-dontwarn com.tencent.trtc.**
-keep class com.tencent.bugly.** { *; }
-dontwarn com.tencent.bugly.**
-dontwarn com.tencent.tinker.**
