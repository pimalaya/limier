# limier client: JNI-facing symbols R8 must not rename or remove.

# Native.listMailboxes / searchMailboxes are matched by their
# fully-qualified names from Rust.
-keep class org.pimalaya.limier.client.Native { *; }

# Transport.read / Transport.write are called only from native code,
# so R8 sees them as unused without this rule.
-keep class org.pimalaya.limier.client.Transport {
    byte[] read();
    void write(byte[]);
}

# NativeSink is driven from native code, one method per mailbox.
-keep class org.pimalaya.limier.client.NativeSink {
    void onMailbox(java.lang.String);
    void onProgress();
    boolean shouldStop();
}
