# Mozilla GeckoView Bug 1838031: SnakeYAML 2.2 references JavaBeans types that
# Android does not provide. These declarations do not add runtime classes; GeckoView
# reads YAML only when explicitly configured for debugging.
# https://searchfox.org/firefox-main/source/mobile/android/geckoview/proguard-rules.txt#365-374
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
