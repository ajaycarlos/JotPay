# Preserve WorkManager workers instantiated by class name from persisted work requests.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room locates the generated database implementation by its conventional class name.
-keep class com.example.moneylog.AppDatabase { *; }
-keep class com.example.moneylog.AppDatabase_Impl { *; }

# Keep runtime annotation and generic metadata used by AndroidX/Kotlin libraries.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
