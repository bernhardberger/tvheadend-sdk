# Root the Kotlin consumer calls in this compile-only app with no manifest entry point.
-keep,allowobfuscation class at.bernhardberger.tvheadend.sdk.consumer.StagedSdkConsumer {
    public *;
}
# Root top-level consumer factories, including the real session and metadata cache path.
-keep,allowobfuscation class at.bernhardberger.tvheadend.sdk.consumer.StagedSdkConsumerKt {
    public *;
}
# Exercise the Java consumer calls while allowing all SDK implementations to be optimized.
-keep,allowobfuscation class at.bernhardberger.tvheadend.sdk.consumer.StagedSdkJavaConsumer {
    public *;
}
