package hu.peresztegi.zeneremote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Minimális mDNS válaszoló: a "<név>.local" A-rekord kérdésekre a telefon IP-jével válaszol
 * azon a hálózaton, ahonnan a kérdés jött (otthoni Wi-Fi vagy a saját hotspot).
 * Plain Java, Android nélkül is futtatható/tesztelhető.
 */
final class MdnsResponder implements Runnable {

    private static final int PORT = 5353;
    private static final InetAddress GROUP;

    static {
        try {
            GROUP = InetAddress.getByName("224.0.0.251");
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private final String fqdn;
    private final Set<String> joined = new HashSet<>();
    private volatile MulticastSocket sock;
    private volatile boolean stopped;

    MdnsResponder(String name) {
        this.fqdn = name.toLowerCase(Locale.ROOT) + ".local";
    }

    String hostName() {
        return fqdn;
    }

    void start() {
        Thread t = new Thread(this, "mdns");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        stopped = true;
        MulticastSocket s = sock;
        if (s != null) s.close();
    }

    @Override
    public void run() {
        while (!stopped) {
            try (MulticastSocket s = new MulticastSocket(null)) {
                s.setReuseAddress(true); // az Android saját mDNS-e is használhatja az 5353-at
                s.bind(new InetSocketAddress(PORT));
                s.setTimeToLive(255);
                s.setSoTimeout(15000);
                sock = s;
                joined.clear();
                joinAll(s);

                byte[] buf = new byte[9000];
                while (!stopped) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        joinAll(s); // pl. közben bekapcsoltad a hotspotot
                        continue;
                    }
                    try {
                        handle(s, p);
                    } catch (RuntimeException | IOException e) {
                        // hibás vagy idegen csomag: kihagyjuk
                    }
                }
            } catch (IOException e) {
                if (stopped) return;
                try {
                    Thread.sleep(5000); // nincs hálózat -> később újra
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** Csatlakozás a multicast csoporthoz minden aktív IPv4 interfészen (Wi-Fi, hotspot). */
    private void joinAll(MulticastSocket s) {
        Set<String> up = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast() || !hasIPv4(ni)) continue;
                up.add(ni.getName());
                if (joined.contains(ni.getName())) continue;
                try {
                    s.joinGroup(new InetSocketAddress(GROUP, PORT), ni);
                    joined.add(ni.getName());
                } catch (IOException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        joined.retainAll(up); // ami lement, az visszajövéskor újra csatlakozik
    }

    private void handle(MulticastSocket s, DatagramPacket p) throws IOException {
        byte[] d = p.getData();
        int len = p.getLength();
        if (len < 12) return;
        int id = u16(d, 0);
        int flags = u16(d, 2);
        if ((flags & 0x8000) != 0) return; // ez válasz, nem kérdés

        int qd = u16(d, 4);
        int off = 12;
        boolean match = false;
        boolean wantsUnicast = false;
        for (int i = 0; i < qd; i++) {
            int[] next = new int[1];
            String name = readName(d, len, off, next);
            off = next[0];
            if (off + 4 > len) return;
            int type = u16(d, off);
            int cls = u16(d, off + 2);
            off += 4;
            if (name.equalsIgnoreCase(fqdn) && (type == 1 || type == 255)
                    && ((cls & 0x7fff) == 1 || (cls & 0x7fff) == 255)) {
                match = true;
                if ((cls & 0x8000) != 0) wantsUnicast = true;
            }
        }
        if (!match) return;

        Inet4Address mine = localAddressFor(p.getAddress());
        if (mine == null) return;

        boolean legacy = p.getPort() != PORT; // egyszerű (nem mDNS) kliens
        byte[] resp = build(legacy ? id : 0, legacy, mine);

        if (legacy || wantsUnicast) {
            s.send(new DatagramPacket(resp, resp.length, p.getAddress(), p.getPort()));
        }
        if (!legacy) {
            synchronized (s) {
                NetworkInterface ni = NetworkInterface.getByInetAddress(mine);
                if (ni != null) s.setNetworkInterface(ni);
                s.send(new DatagramPacket(resp, resp.length, GROUP, PORT));
            }
        }
    }

    private byte[] build(int id, boolean legacy, Inet4Address ip) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w16(o, id);
        w16(o, 0x8400);            // válasz, hiteles
        w16(o, legacy ? 1 : 0);    // kérdések
        w16(o, 1);                 // válaszok
        w16(o, 0);
        w16(o, 0);
        if (legacy) {
            writeName(o);
            w16(o, 1);
            w16(o, 1);
        }
        writeName(o);
        w16(o, 1);                          // A
        w16(o, legacy ? 0x0001 : 0x8001);   // IN (+ cache-flush)
        w16(o, 0);
        w16(o, legacy ? 10 : 120);          // TTL másodperc
        w16(o, 4);
        byte[] a = ip.getAddress();
        o.write(a, 0, a.length);
        return o.toByteArray();
    }

    private void writeName(ByteArrayOutputStream o) {
        for (String label : fqdn.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
    }

    /** DNS név olvasása tömörítési mutatók támogatásával. next[0] = a név utáni pozíció. */
    private static String readName(byte[] d, int len, int off, int[] next) {
        StringBuilder sb = new StringBuilder();
        int pos = off;
        int after = -1;
        int jumps = 0;
        while (true) {
            if (pos >= len) throw new IllegalArgumentException("rövid csomag");
            int l = d[pos] & 0xff;
            if (l == 0) {
                pos++;
                break;
            }
            if ((l & 0xC0) == 0xC0) {
                if (pos + 1 >= len) throw new IllegalArgumentException("rövid mutató");
                if (after < 0) after = pos + 2;
                pos = ((l & 0x3F) << 8) | (d[pos + 1] & 0xff);
                if (++jumps > 20) throw new IllegalArgumentException("mutató hurok");
                continue;
            }
            pos++;
            if (pos + l > len) throw new IllegalArgumentException("rövid címke");
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, pos, l, StandardCharsets.ISO_8859_1));
            pos += l;
        }
        next[0] = after >= 0 ? after : pos;
        return sb.toString();
    }

    /** Az a saját IPv4 címünk, ami a kérdező hálózatán van. */
    private static Inet4Address localAddressFor(InetAddress src) {
        Inet4Address fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address a = (Inet4Address) ia.getAddress();
                    if (src instanceof Inet4Address
                            && sameSubnet(a, (Inet4Address) src, ia.getNetworkPrefixLength())) {
                        return a;
                    }
                    if (fallback == null && !ni.getName().startsWith("tun")) fallback = a;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static boolean sameSubnet(Inet4Address a, Inet4Address b, int prefix) {
        if (prefix <= 0 || prefix > 32) return false;
        int mask = prefix == 32 ? -1 : ~(-1 >>> prefix);
        return (toInt(a) & mask) == (toInt(b) & mask);
    }

    private static int toInt(Inet4Address a) {
        byte[] b = a.getAddress();
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    private static boolean hasIPv4(NetworkInterface ni) {
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a instanceof Inet4Address) return true;
        }
        return false;
    }

    private static int u16(byte[] d, int i) {
        return ((d[i] & 0xff) << 8) | (d[i + 1] & 0xff);
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xff);
        o.write(v & 0xff);
    }
}
