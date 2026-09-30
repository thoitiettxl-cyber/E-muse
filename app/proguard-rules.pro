# E-Muse ProGuard rules (R8, release).
#
# App dùng org.json (framework) thay vì reflection-based serialization,
# không có custom Parcelable/Serializable class, reflection duy nhất là
# HiddenApi.kt gọi method của framework (ApplicationInfo) nên R8 không
# cần keep rule đặc biệt. Manifest components (Activity/Service/Receiver)
# được AGP keep tự động.

# Giữ tên class cho service/receiver tra cứu qua ComponentName.
-keep class io.github.thoitiet.emuse.MuseAccessibilityService { *; }
-keep class io.github.thoitiet.emuse.InstallReceiver { *; }

# androidx.security.crypto -> Tink thiếu annotation compileOnly (chuẩn khi bật R8).
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
