package io.quantlab.collection;

/** Factory + strategy: OSHI where available, Linux /proc without any dependency as fallback. */
public final class ProbeFactory {
    private ProbeFactory() { }
    public static ProcessProbe processProbe() {
        try { return (ProcessProbe) Class.forName("io.quantlab.collection.OshiProcessProbe").newInstance(); }
        catch (Exception | LinkageError unavailable) {
            requireLinuxProc();return new LinuxProcessProbe();
        }
    }
    public static HostProbe hostProbe() {
        try { return (HostProbe) Class.forName("io.quantlab.collection.OshiHostProbe").newInstance(); }
        catch (Exception | LinkageError unavailable) {
            requireLinuxProc();return new LinuxHostProbe();
        }
    }
    private static void requireLinuxProc(){
        if(!java.nio.file.Files.isReadable(java.nio.file.Paths.get("/proc/stat")))
            throw new IllegalStateException("OSHI unavailable and Linux /proc is not accessible. Build with Maven to include OSHI.");
    }
}
