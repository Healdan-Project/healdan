import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Works out the addresses Tomcat's RemoteIpValve believes X-Forwarded-For and X-Forwarded-Proto from (server.xml):
 * LOGICALDOC_TRUSTED_PROXIES when it's set, otherwise the Docker networks the container is on. Anything else that
 * sends the headers is ignored, so it can't claim another client's address to the sign-in throttle.
 */
public final class TrustedProxies {

    static final String VARIABLE = "LOGICALDOC_TRUSTED_PROXIES";

    /** Read by setenv.sh, which hands it to Tomcat as the healdan.trustedProxies system property. */
    static final File FILE = new File(ConfigPlan.HOME + "/run/trusted-proxies");

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    private TrustedProxies() {
    }

    /**
     * The comma-separated list Tomcat's internalProxies takes: the setting's entries, trimmed, or the given networks
     * when it has none. Each entry must be an IP address or a range in CIDR notation; Tomcat would read anything else
     * as a pattern that matches nothing, and it can't look up host names. Loopback is refused: a proxy never reaches
     * the app from inside its own container, and a trusted loopback would let a header claim 127.0.0.1, from which
     * LogicalDOC never blocks admin.
     */
    static String resolve(Map<String, String> env, List<String> dockerNetworks) {
        List<String> entries = new ArrayList<>();
        for (String entry : env.getOrDefault(VARIABLE, "").split(","))
            if (!entry.isBlank())
                entries.add(checked(entry.trim()));
        return String.join(",", entries.isEmpty() ? dockerNetworks : entries);
    }

    private static String checked(String entry) {
        String[] parts = entry.split("/", -1);
        String address = parts[0];
        boolean v4 = IPV4.matcher(address).matches();
        if (parts.length > 2 || !(v4 || IPV6.matcher(address).matches()))
            throw invalid(entry);
        byte[] bytes;
        try {
            // A literal, so no lookup
            InetAddress parsed = InetAddress.getByName(address);
            if (!v4 && parsed instanceof Inet4Address)
                throw invalid(entry);
            bytes = parsed.getAddress();
        } catch (UnknownHostException e) {
            throw invalid(entry);
        }
        int prefix = bytes.length * 8;
        if (parts.length == 2) {
            if (!parts[1].matches("\\d{1,3}") || Integer.parseInt(parts[1]) > prefix)
                throw invalid(entry);
            prefix = Integer.parseInt(parts[1]);
        }
        if (includesLoopback(bytes, prefix))
            throw new IllegalArgumentException(VARIABLE + ": '" + entry + "' includes a loopback address; a proxy never"
                    + " reaches the app from inside its own container");
        return entry;
    }

    private static IllegalArgumentException invalid(String entry) {
        return new IllegalArgumentException(VARIABLE + ": '" + entry + "' is not an IP address or a range such as"
                + " 192.168.1.0/24 (host names can't be used)");
    }

    /** Whether the range overlaps 127.0.0.0/8 or ::1. */
    private static boolean includesLoopback(byte[] address, int prefix) {
        byte[] loopback = new byte[address.length];
        if (loopback.length == 4)
            loopback[0] = 127;
        else
            loopback[15] = 1;
        int overlap = Math.min(prefix, loopback.length == 4 ? 8 : 128);
        return Arrays.equals(masked(address, overlap), masked(loopback, overlap));
    }

    /** The range of the given length that contains the address, with its host bits cleared, as Tomcat writes it. */
    static String network(InetAddress address, int prefix) {
        try {
            return InetAddress.getByAddress(masked(address.getAddress(), prefix)).getHostAddress() + "/" + prefix;
        } catch (UnknownHostException e) {
            // Only for an address of the wrong length
            throw new IllegalStateException(e);
        }
    }

    private static byte[] masked(byte[] address, int prefix) {
        byte[] network = address.clone();
        for (int i = 0; i < network.length; i++)
            network[i] &= (byte) (0xFF00 >> Math.max(0, Math.min(8, prefix - 8 * i)));
        return network;
    }

    /** The ranges of this container's network interfaces, leaving out loopback and link-local addresses. */
    static List<String> dockerNetworks() throws SocketException {
        Set<String> networks = new LinkedHashSet<>();
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback())
                continue;
            for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                InetAddress ip = address.getAddress();
                if (!ip.isLoopbackAddress() && !ip.isLinkLocalAddress())
                    networks.add(network(ip, address.getNetworkPrefixLength()));
            }
        }
        return new ArrayList<>(networks);
    }

    /** Replaces the file setenv.sh reads. */
    static void write(File file, String proxies) throws IOException {
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), proxies, StandardCharsets.US_ASCII);
    }
}
