import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

public class TrustedProxiesTest {

    private static final List<String> NETWORKS = List.of("172.18.0.0/16", "172.19.0.0/16");

    public static void main(String[] args) throws Exception {
        unsetTrustsTheDockerNetworks();
        blankTrustsTheDockerNetworks();
        noNetworksTrustsNothing();
        settingReplacesTheDockerNetworks();
        entriesAreTrimmedAndEmptyOnesSkipped();
        ipv6Accepted();
        hostNamesRefused();
        malformedAddressesRefused();
        badPrefixesRefused();
        loopbackRefused();
        rangesBesideLoopbackAccepted();
        networkClearsHostBits();
        dockerNetworksAreValidEntries();
        Check.done("TrustedProxiesTest");
    }

    private static String resolve(String setting) {
        return TrustedProxies.resolve(setting == null ? Map.of() : Map.of("LOGICALDOC_TRUSTED_PROXIES", setting),
                NETWORKS);
    }

    private static void refused(String setting, String fragment) {
        Check.throwsWith(fragment, () -> resolve(setting), "'" + setting + "' refused");
        Check.throwsWith("LOGICALDOC_TRUSTED_PROXIES", () -> resolve(setting), "'" + setting + "' names the setting");
    }

    private static void unsetTrustsTheDockerNetworks() {
        Check.eq("172.18.0.0/16,172.19.0.0/16", resolve(null), "unset");
    }

    private static void blankTrustsTheDockerNetworks() {
        Check.eq("172.18.0.0/16,172.19.0.0/16", resolve("  "), "blank");
        Check.eq("172.18.0.0/16,172.19.0.0/16", resolve(" , "), "only commas");
    }

    private static void noNetworksTrustsNothing() {
        Check.eq("", TrustedProxies.resolve(Map.of(), List.of()), "no networks");
    }

    private static void settingReplacesTheDockerNetworks() {
        Check.eq("192.168.1.10", resolve("192.168.1.10"), "one address");
        Check.eq("192.168.1.10,10.8.0.0/24", resolve("192.168.1.10,10.8.0.0/24"), "address and range");
    }

    private static void entriesAreTrimmedAndEmptyOnesSkipped() {
        Check.eq("192.168.1.10,10.8.0.0/24", resolve(" 192.168.1.10 , ,10.8.0.0/24, "), "spaces and empty entries");
    }

    private static void ipv6Accepted() {
        Check.eq("fd00::/8,fd12:3456::7", resolve("fd00::/8, fd12:3456::7"), "IPv6");
    }

    private static void hostNamesRefused() {
        refused("npm", "'npm'");
        refused("192.168.1.10,proxy.example.org", "'proxy.example.org'");
    }

    private static void malformedAddressesRefused() {
        refused("192.168.1.300", "'192.168.1.300'");
        refused("192.168.1", "'192.168.1'");
        refused("fd00::zz", "'fd00::zz'");
        refused("192.168.1.0/24/8", "'192.168.1.0/24/8'");
        // Java reads it as 192.168.1.10, but Tomcat never matches it
        refused("::ffff:192.168.1.10", "'::ffff:192.168.1.10'");
    }

    private static void badPrefixesRefused() {
        refused("10.0.0.0/33", "'10.0.0.0/33'");
        refused("10.0.0.0/", "'10.0.0.0/'");
        refused("10.0.0.0/x", "'10.0.0.0/x'");
        refused("fd00::/129", "'fd00::/129'");
    }

    private static void loopbackRefused() {
        refused("127.0.0.1", "loopback");
        refused("127.0.0.0/8", "loopback");
        refused("127.5.0.0/16", "loopback");
        refused("192.168.1.10,::1", "loopback");
        // Ranges that contain a loopback address
        refused("0.0.0.0/0", "loopback");
        refused("100.64.0.0/2", "loopback");
        refused("::/0", "loopback");
    }

    private static void rangesBesideLoopbackAccepted() {
        Check.eq("126.0.0.0/8,128.0.0.0/8,::2", resolve("126.0.0.0/8, 128.0.0.0/8, ::2"), "next to loopback");
    }

    private static void networkClearsHostBits() throws UnknownHostException {
        Check.eq("172.18.0.0/16", TrustedProxies.network(InetAddress.getByName("172.18.0.5"), 16), "IPv4 /16");
        Check.eq("10.1.2.3/32", TrustedProxies.network(InetAddress.getByName("10.1.2.3"), 32), "IPv4 /32");
        Check.eq("fd00:1:2:3:0:0:0:0/64",
                TrustedProxies.network(InetAddress.getByName("fd00:1:2:3:4:5:6:7"), 64), "IPv6 /64");
    }

    /** Whatever machine runs the tests: its networks are ranges the setting would accept, loopback left out. */
    private static void dockerNetworksAreValidEntries() throws Exception {
        List<String> networks = TrustedProxies.dockerNetworks();
        for (String network : networks) {
            Check.isTrue(network.contains("/"), network + " is a range");
            Check.isTrue(!network.startsWith("fe80:") && !network.startsWith("169.254."),
                    network + " is not link-local");
        }
        String joined = String.join(",", networks);
        Check.eq(joined, TrustedProxies.resolve(Map.of("LOGICALDOC_TRUSTED_PROXIES", joined), List.of()),
                "the networks pass the setting's checks");
    }
}
