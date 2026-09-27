# 默认不开启混淆（个人自用，便于排查问题）
# 若将来开启，需保留以下内容：
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.aiqu.watermark.data.model.** { *; }
-keep,includedescriptorclasses class com.aiqu.watermark.data.model.**$$serializer { *; }
-keepclassmembers class **$$serializer { *; }
