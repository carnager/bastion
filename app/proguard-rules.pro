# MINA SSHD finds ciphers, signatures, key parsers and NIO2 factories by reflection.
-keep class org.apache.sshd.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn sun.security.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
# BouncyCastle LDAP/DANE helpers reference JNDI, which Android lacks; never used here.
-dontwarn javax.naming.**

# The command line features read termlib's screen snapshot by reflection (see ScreenReader).
-keep class org.connectbot.terminal.** { *; }
