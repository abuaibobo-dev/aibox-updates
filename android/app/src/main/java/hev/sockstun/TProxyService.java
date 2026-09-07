package hev.sockstun;

/** JNI bridge. The class/package and static signatures must match the prebuilt native library. */
public final class TProxyService {
    private TProxyService() {}

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}
