# Rules for the release build, which is minified and shrunk (see build.gradle.kts).
# Everything here exists because a reference is invisible to R8: a registry keyed by
# class NAME, or a platform class the JVM only touches reflectively.

# --- JSch ---------------------------------------------------------------------
# The SSH transport is configured by NAME: JSch keeps tables of algorithm names and
# resolves them with Class.forName ("aes256-ctr" -> a Cipher class, "ssh-ed25519" ->
# a KeyPair class). Shrinking those classes out compiles fine and then fails on the
# first connection, which is the worst possible place to discover it.
-keep class com.jcraft.jsch.** { *; }

# --- BouncyCastle -------------------------------------------------------------
# Registered as a JCA provider, and a JCA provider is a registry of class NAMES, not
# of code references: BouncyCastle puts "Cipher.AES/GCM/NoPadding" ->
# "org.bouncycastle.jcajce.provider.symmetric.AES$GCMBCELECTRIC" into a
# java.security.Provider table, and the platform instantiates it later by name.
# So the provider itself and every engine class it can name must survive; R8 may
# still shrink the parts of BC that nothing reaches.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
# Optional platform dependencies BC probes for (and Android does not have).
-dontwarn java.lang.management.ManagementFactory
-dontwarn org.bouncycastle.**

# --- optional platform pieces JSch was built against --------------------------
# JSch is a desktop library: it carries code for Windows named-pipe agents (JNA),
# Kerberos/GSSAPI, Unix domain sockets (junixsocket) and two log facades. None of
# those exist on Android, and none of that code is reachable here, so the missing
# references are suppressed rather than pulled in. Each line is a package JSch
# touches only from a class this app never selects.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
