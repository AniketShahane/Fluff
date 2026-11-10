# The Anthropic SDK, OkHttp, kotlinx.serialization, Room and Health Connect all ship their own
# consumer R8 rules. These are the few extra rules Fluff needs.

# Keep line numbers for readable crash reports, but hide source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The Anthropic SDK builds models via Jackson + Kotlin reflection.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class kotlin.Metadata { *; }
-dontwarn com.fasterxml.jackson.databind.ext.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**

# OpenTelemetry (pulled in by the Anthropic SDK) references compile-only AutoValue annotations.
-dontwarn com.google.auto.value.**
