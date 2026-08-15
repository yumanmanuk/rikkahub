# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# keep kotlinx serializable classes
-keep @kotlinx.serialization.Serializable class * {*;}

# keep jlatexmath
-keep class org.scilab.forge.jlatexmath.** {*;}

-dontwarn com.google.re2j.**
-dontobfuscate

# Ktor 在 Android 上引用了仅 JVM 可用的 java.lang.management 类（IntellijIdeaDebugDetector）
# Android 不包含这些类，需要告知 R8 忽略
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# java.beans is not available on Android; Jackson references it only on JVM
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# auth0/jackson: TypeReference subclasses rely on runtime generic signatures.
# R8 strips Signature/InnerClasses/EnclosingMethod by default, and its class
# merging/inlining optimizations can destroy the anonymous class hierarchy that
# TypeReference.<init> depends on via getClass().getGenericSuperclass().
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class com.fasterxml.jackson.** { *; }
-keep class com.auth0.jwt.** { *; }

# R8 missing classes: suppressed warnings from transitive dependencies
# (log4j, Jetty ALPN/NPN, reactor-blockhound - not used at runtime on Android)
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.eclipse.jetty.alpn.ALPN$ClientProvider
-dontwarn org.eclipse.jetty.alpn.ALPN$Provider
-dontwarn org.eclipse.jetty.alpn.ALPN$ServerProvider
-dontwarn org.eclipse.jetty.alpn.ALPN
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ClientProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$Provider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# gRPC Netty shaded: 传递依赖引入，Android 运行时不使用，忽略缺失警告
-dontwarn io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
-dontwarn io.grpc.netty.shaded.io.grpc.netty.InternalNettyChannelCredentials
-dontwarn io.grpc.netty.shaded.io.grpc.netty.InternalProtocolNegotiator$ClientFactory
-dontwarn io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
-dontwarn io.grpc.netty.shaded.io.netty.channel.EventLoopGroup
-dontwarn io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup
-dontwarn io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel
-dontwarn io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
-dontwarn io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder
-dontwarn io.grpc.netty.shaded.io.netty.util.AsciiString
-dontwarn io.grpc.netty.shaded.io.netty.util.concurrent.DefaultThreadFactory
-dontwarn io.grpc.netty.shaded.io.netty.util.concurrent.Future
-dontwarn io.grpc.util.MultiChildLoadBalancer$AcceptResolvedAddrRetVal

