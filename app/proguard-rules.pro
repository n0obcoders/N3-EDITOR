# Keep the serializable backup model so field names stay stable in release builds.
-keepclassmembers class com.nitinshukla.notebook.core.backup.** { *; }
-keep,includedescriptorclasses class com.nitinshukla.notebook.core.backup.**$$serializer { *; }
-keepclassmembers class com.nitinshukla.notebook.core.backup.** { *** Companion; }
-keepclasseswithmembers class com.nitinshukla.notebook.core.backup.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
