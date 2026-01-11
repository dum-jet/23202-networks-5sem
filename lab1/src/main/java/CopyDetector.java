import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CopyDetector {

    private static final int INTERVAL = 1;
    private static final int TIMEOUT = 2;

    private final DatagramSocket socket = new DatagramSocket();
    private final MulticastSocket multicastSocket;

    private final InetAddress group;
    private final int port;

    private final Map<String, Long> liveNodes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    public CopyDetector(String multicastAddrStr) throws IOException {
        this.group = InetAddress.getByName(multicastAddrStr);
        this.port = 4446;
        this.multicastSocket = new MulticastSocket(port);

        if (group instanceof Inet6Address) {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            loop_1:
            for (NetworkInterface nif: Collections.list(interfaces)) {
                if (nif.isUp() && nif.supportsMulticast() && !nif.isLoopback() && !nif.getDisplayName().startsWith("VirtualBox")) {
                    Enumeration<InetAddress> addresses = nif.getInetAddresses();
                    for (InetAddress addr : Collections.list(addresses)) {
                        if (addr instanceof  Inet6Address) {
                            multicastSocket.joinGroup(new InetSocketAddress(this.group, this.port), nif);
                            System.out.println(nif.getDisplayName());
                            break loop_1;
                        }
                    }
                }
            }
        } else {
            multicastSocket.joinGroup(group);
        }
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::sendMessage, 0, INTERVAL, TimeUnit.SECONDS);
        scheduler.execute(this::listenForMessages);
        scheduler.scheduleAtFixedRate(this::checkForTimeouts, 0, TIMEOUT, TimeUnit.SECONDS);
    }

    private void sendMessage() {
        try {
            String message = "HELLO " + socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
            //System.out.println("Sending " + message);
            byte[] data = message.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, group, port);
            socket.send(packet);
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }

    private void listenForMessages() {
        byte[] buffer = new byte[256];
        while (!Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                String senderIP = packet.getAddress().getHostAddress();
                String senderFullAddress = senderIP + ":" + packet.getPort();
                //System.out.println("Received from " + senderFullAddress);

                if (message.startsWith("HELLO ")) {

                    Long retValue = liveNodes.put(senderFullAddress, System.currentTimeMillis());
                    if (retValue == null)
                        printLiveNodes();
                }
            } catch (IOException e) {
                if (!multicastSocket.isClosed())
                    System.err.println(e.getMessage());
                break;
            }
        }
    }

    private void checkForTimeouts() {
        long now = System.currentTimeMillis();
        boolean retValue = liveNodes.entrySet().removeIf(entry -> (now - entry.getValue()) > TIMEOUT * 1000L);
        if (retValue)
            printLiveNodes();
    }

    private void printLiveNodes() {
        List<String> sorted = new ArrayList<>(liveNodes.keySet());
        Collections.sort(sorted);
        System.out.println("Текущий список живых узлов:");
        for (String addr : sorted)
            System.out.println("  - " + addr);
        System.out.println();
    }

    public void stop() {
        scheduler.shutdown();
        multicastSocket.close();
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Использование: java CopyDetector {multicast-адрес}");
            return;
        }
        String multicastAddr = args[0];

        try {
            CopyDetector app = new CopyDetector(multicastAddr);
            app.start();

            Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }
}