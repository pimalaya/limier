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

# MailboxSink.onMailbox is the streaming callback invoked from native.
-keep class org.pimalaya.limier.client.MailboxSink {
    void onMailbox(java.lang.String);
}
