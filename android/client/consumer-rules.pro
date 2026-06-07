# limier client: JNI-facing symbols R8 must not rename or remove.

# Native.search is matched by its fully-qualified name from Rust.
-keep class org.pimalaya.limier.client.Native { *; }

# Transport.read / Transport.write are called only from native code,
# so R8 sees them as unused without this rule.
-keep class org.pimalaya.limier.client.Transport {
    byte[] read();
    void write(byte[]);
}
