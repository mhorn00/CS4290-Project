package project;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.json.JSONException;

import project.App.LogLevel;
import project.PacketHelper.Packet;
import project.PacketHelper.PacketType;

public class Server {
    public final int PORT;
    public final String HOST;
    private final ServerSocketChannel serverSocket;
    private final Selector selector;
    private final Map<SelectionKey, ClientStatus> clients;
    private final List<MathRequest> requests;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final int HEARTBEAT_TIMEOUT = 5;

    public Server(String host, int port) throws IOException {
        this.HOST = host;
        this.PORT = port;
        this.selector = Selector.open();
        this.serverSocket = java.nio.channels.ServerSocketChannel.open();
        this.clients = new ConcurrentHashMap<SelectionKey, ClientStatus>();
        this.requests = new ArrayList<MathRequest>();
    }

    /**
     * Initializes the server by configuring the server socket, binding it to the specified host and port,
     * and registering it with the selector for accepting incoming connections.
     * 
     * @throws IOException if an I/O error occurs when binding the socket or registering it with the selector
     */
    private void init() {
        try {
            serverSocket.configureBlocking(false);
            serverSocket.bind(new InetSocketAddress(HOST, PORT));
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            App.log("Exception initializing server", LogLevel.ERROR);
            e.printStackTrace();
            System.exit(-1);
            return;
        }
        App.log("Starting on address " + HOST + ":" + PORT, LogLevel.INFO);
    }

    /**
     * Starts the server and initializes the server. Sends a heartbeat to all clients every HEARTBEAT_TIMEOUT seconds.
     * Contains the main server loop that listens for incoming connections and reads incoming data from clients.
     * Handles math requests from clients and sends back the result or an error message if the expression is invalid.
     */
    public void start() {
        // Server init
        init();

        // Send HEARTBEAT to all clients every HEARTBEAT_TIMEOUT seconds
        scheduler.scheduleAtFixedRate(() -> handleSendHeartbeat(), 0, 1, TimeUnit.SECONDS);

        // Main Server Loop
        while (true) {
            try {
                selector.select();
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    if (key.isAcceptable()) {
                        addClient(key);
                        iterator.remove();
                    } else if (key.isReadable()) {
                        read(key);
                        iterator.remove();
                    }
                }
            } catch (IOException e) {
                App.log("Exception in selector", LogLevel.ERROR);
            }
            Iterator<MathRequest> iterator = requests.iterator();
            while (iterator.hasNext()) {
                MathRequest req = iterator.next();
                try {
                    double result = MathHelper.parse(req.getPacket().getContent());
                    try {
                        req.getClient().getSocket().write(PacketHelper.RESULT(this, ""+result).toBuffer());
                    } catch (IOException e) {
                        App.log(packetSendExceptionMessage(PacketType.RESULT, PacketType.MATH, req.getClient().getName()), LogLevel.WARN);
                        clients.remove(req.getClient().getKey());
                        req.getClient().getKey().cancel();
                        req.getClient().tryCloseSocket();
                    }
                } catch (IllegalArgumentException e) {
                    App.log("MATH request from '" + req.getClient().getName() + "' contains invalid expression!", LogLevel.WARN);
                    try {
                        req.getClient().getSocket().write(PacketHelper.RESULT(this, "Expression Invalid").toBuffer());
                    } catch (IOException e1) {
                        App.log(packetSendExceptionMessage(PacketType.RESULT, PacketType.MATH, req.getClient().getName()), LogLevel.WARN);
                        clients.remove(req.getClient().getKey());
                        req.getClient().getKey().cancel();
                        req.getClient().tryCloseSocket();
                    }
                }
                iterator.remove();
            }
        }
    }

    /**
     * Accepts a new client connection and registers it with the selector for read operations.
     *
     * @param key the selection key for the server socket channel
     */
    private void addClient(SelectionKey key) {
        try {
            ServerSocketChannel server = (ServerSocketChannel) key.channel();
            SocketChannel client = server.accept();
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            App.log("Exception adding client", LogLevel.ERROR);
        }
    }

    /**
     * Reads data from the client associated with the given SelectionKey.
     * If an exception occurs while reading, the client is assumed to have disconnected and is removed from the list of clients.
     * If the received packet is empty, it is ignored.
     * If the received packet is not a valid JSON, the client is assumed to have sent an invalid packet and is removed from the list of clients.
     * If the received packet is not from a known client and is not a CONNECT packet, the client is assumed to be unknown and is disconnected.
     * If the received packet is from a known client but has an invalid name or timestamp, the client is disconnected.
     * Otherwise, the packet is handled according to its type.
     *
     * @param key The SelectionKey associated with the client to read from.
     */
    private void read(SelectionKey key) {
        ClientStatus cs = clients.get(key);
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(2048);
        try {
            client.read(buffer);
        } catch (IOException e) {
            if (cs != null) {
                App.log("Exception reading from client '" + cs.getName() + "''. Assuming disconnect... Client was connected for " + cs.getTimeConnected().until(Instant.now(), ChronoUnit.SECONDS)
                        + " seconds", LogLevel.WARN);
                clients.remove(key);
            } else {
                App.log("Exception reading from unknown client. Dropping...", LogLevel.WARN);
            }
            key.cancel();
            tryCloseSocket(client);
            return;
        }

        // ignore empty packets
        if (new String(buffer.array()).trim().isEmpty()) { return; }

        // parse packet
        Packet p;
        try {
            p = PacketHelper.parse(buffer);
        } catch (JSONException e) {
            if (cs != null) {
                App.log(invalidPacketExceptionMessage(null, cs), LogLevel.WARN);
                clients.remove(key);
            } else {
                App.log(invalidPacketExceptionMessage(null, null), LogLevel.WARN);
            }
            key.cancel();
            tryCloseSocket(client);
            return;
        }

        // check if client is known
        if (cs == null && p.getType() != PacketType.CONNECT) {
            App.log("Received packet from unknown client '" + p.getSender() + "'! Sending DISCONNECT...", LogLevel.WARN);
            try {
                client.write(PacketHelper.DISCONNECT(this, "Client has not connected. Dropping client...").toBuffer());
            } catch (IOException e) {
                App.log(packetSendExceptionMessage(PacketType.DISCONNECT, null, p.getSender()), LogLevel.WARN);
            }
            key.cancel();
            tryCloseSocket(client);
            return;
        }

        // check if packet name is valid (except for CONNECT)
        if (cs != null && p.getType() != PacketType.CONNECT) {
            if (!p.getSender().equals(cs.getName())) {
                App.log("Received packet from '" + p.getSender() + "' but expected packet from '" + cs.getName() + "'! Dropping client... Client was connected for "
                        + cs.getTimeConnected().until(Instant.now(), ChronoUnit.SECONDS) + " seconds", LogLevel.WARN);
                try {
                    client.write(PacketHelper.DISCONNECT(this, "Client sent packet with invalid name. Dropping client...").toBuffer());
                } catch (IOException e) {
                    App.log(packetSendExceptionMessage(PacketType.DISCONNECT, null, cs.getName()), LogLevel.WARN);
                }
                clients.remove(key);
                key.cancel();
                tryCloseSocket(client);
                return;

                // check if packet timestamp is valid
            } else if (p.getTimestamp().isAfter(Instant.now())) {
                App.log("Received packet from '" + p.getSender() + "' with invalid timestamp! Dropping client... Client was connected for "
                        + cs.getTimeConnected().until(Instant.now(), ChronoUnit.SECONDS) + " seconds", LogLevel.WARN);
                try {
                    client.write(PacketHelper.DISCONNECT(this, "Client sent packet with invalid timestamp. Dropping client...").toBuffer());
                } catch (IOException e) {
                    App.log(packetSendExceptionMessage(PacketType.DISCONNECT, null, cs.getName()), LogLevel.WARN);
                }
                clients.remove(key);
                key.cancel();
                tryCloseSocket(client);
                return;
            }
        }

        // handle packet
        // cs shouldn't be null here...
        assert cs != null;
        
        switch (p.getType()) {
        case CONNECT:
            handleConnect(p, key, client);
            return;
        case DISCONNECT:
            handleDisconnect(p, cs);
            return;
        case HEARTBEAT:
            handleHeartbeat(p, cs);
            return;
        case ACK:
            handleAck(p, cs);
            return;
        case MATH:
            handleMath(p, cs);
            return;
        case RESULT:
            handleResult(p, cs);
            return;
        }
    }

    /**
     * Sends a heartbeat to all connected clients and handles the response.
     * If a client does not respond to the heartbeat within a certain timeout, it is dropped.
     */
    private void handleSendHeartbeat() {
        for (ClientStatus cs : clients.values()) {
            if (!cs.isConnectionAck())
                continue;
            if (!cs.needsHeartbeat()) {
                cs.incHeartbeatTimeout();
            } else if (cs.needsHeartbeat() && !cs.getHeartbeatSent()) {
                App.log("Sending HEARTBEAT to " + cs.getName(), LogLevel.INFO);
                cs.setHeartbeatSent();
                try {
                    cs.getSocket().write(PacketHelper.HEARTBEAT(this).toBuffer());
                } catch (IOException e) {
                    App.log(packetSendExceptionMessage(PacketType.HEARTBEAT, null, cs.getName()), LogLevel.WARN);
                    clients.remove(cs.getKey());
                    cs.getKey().cancel();
                    cs.tryCloseSocket();
                }
            } else if (cs.needsHeartbeat() && cs.getHeartbeatSent()) {
                App.log("Client '" + cs.getName() + "' has not responded to HEARTBEAT after " + HEARTBEAT_TIMEOUT + " seconds. Dropping client. Client was connected for "
                        + clients.get(cs.getKey()).getTimeConnected().until(Instant.now(), ChronoUnit.SECONDS) + " seconds", LogLevel.INFO);
                try {
                    cs.getSocket().write(PacketHelper.DISCONNECT(this, "Client has not responded to HEARTBEAT after " + HEARTBEAT_TIMEOUT + " seconds. Dropping client...").toBuffer());
                } catch (IOException e) {
                    App.log(packetSendExceptionMessage(PacketType.DISCONNECT, null, cs.getName()), LogLevel.WARN);
                }
                clients.remove(cs.getKey());
                cs.getKey().cancel();
                cs.tryCloseSocket();
            }
        }
    }

    /**
     * Handles a CONNECT packet received from a client.
     * If a client with the same name is already connected, sends a DISCONNECT packet to the new client and closes the connection.
     * Otherwise, adds the client to the list of connected clients and sends an ACK packet back to the client.
     * 
     * @param p The CONNECT packet received from the client.
     * @param key The SelectionKey associated with the client's SocketChannel.
     * @param client The client's SocketChannel.
     */
    private void handleConnect(Packet p, SelectionKey key, SocketChannel client) {
        if (clients.values().stream().anyMatch(cs -> cs.getName().equals(p.getSender()))) {
            App.log("Received CONNECT from client '" + p.getSender() + "' but client with same name already connected. Ignoring...", LogLevel.WARN);
            try {
                client.write(PacketHelper.DISCONNECT(this, "Client with same name already connected. Change name and reconnect.").toBuffer());
            } catch (IOException e) {
                App.log(packetSendExceptionMessage(PacketType.DISCONNECT, PacketType.CONNECT, p.getSender()), LogLevel.WARN);
            }
            tryCloseSocket(client);
            key.cancel();
            return;
        }
        ClientStatus cs = new ClientStatus(p.getSender(), p.getTimestamp(), key);
        clients.put(key, cs);
        App.log("Received CONNECT from '" + cs.getName() + "'", LogLevel.INFO);
        try {
            cs.getSocket().write(PacketHelper.ACK(this).toBuffer());
        } catch (IOException e) {
            App.log(packetSendExceptionMessage(PacketType.ACK, PacketType.CONNECT, cs.getName()), LogLevel.WARN);
            clients.remove(cs.getKey());
            key.cancel();
            cs.tryCloseSocket();
        }
    }

    /**
     * Handles the DISCONNECT packet received from the client.
     * Removes the client from the list of connected clients, cancels the client's key, and tries to close the client's socket.
     * 
     * @param p The DISCONNECT packet received from the client.
     * @param cs The ClientStatus object associated with the client who sent the DISCONNECT packet.
     */
    private void handleDisconnect(Packet p, ClientStatus cs) {
        App.log("Received DISCONNECT from '" + cs.getName() + "' with reason '" + p.getContent() + "'. Client was connected for "
                + clients.get(cs.getKey()).getTimeConnected().until(Instant.now(), ChronoUnit.SECONDS) + " seconds", LogLevel.INFO);
        clients.remove(cs.getKey());
        cs.getKey().cancel();
        cs.tryCloseSocket();
    }

    /**
     * Handles a heartbeat packet from a client.
     * If the client needs a heartbeat, logs the receipt of the heartbeat and sends an acknowledgement.
     * If the client does not need a heartbeat, logs the receipt of the heartbeat with a warning.
     * 
     * @param p the heartbeat packet received from the client
     * @param cs the status of the client
     */
    private void handleHeartbeat(Packet p, ClientStatus cs) {
        if (cs.needsHeartbeat()) {
            App.log("Received HEARTBEAT from '" + cs.getName() + "'", LogLevel.INFO);
            cs.heartbeatAck();
        } else {
            App.log("Received HEARTBEAT from '" + cs.getName() + "' but no HEARTBEAT needed", LogLevel.WARN);
        }
    }

    /**
     * Handles the acknowledgement packet received from the client.
     * If the client has not yet sent a connection acknowledgement, it logs the acknowledgement and sets the connection acknowledgement flag.
     * If the client has already sent a connection acknowledgement, it logs a warning message indicating that no acknowledgement is needed.
     * 
     * @param p The acknowledgement packet received from the client.
     * @param cs The status of the client.
     */
    private void handleAck(Packet p, ClientStatus cs) {
        if (!cs.isConnectionAck()) {
            App.log("Received ACK for CONNECT from " + cs.getName(), LogLevel.INFO);
            cs.setConnectionAck();
        } else {
            App.log("Received ACK from '" + cs.getName() + "' but no ACK needed", LogLevel.WARN);
        }
    }

    /**
     * Handles a math packet received from a client.
     * Sends an ACK packet to the client and adds the math request to the request queue.
     * If an IOException occurs while sending the ACK packet, removes the client from the list of clients and cancels its key.
     * 
     * @param p the math packet received from the client
     * @param cs the client status object associated with the client
     */
    private void handleMath(Packet p, ClientStatus cs) {
        App.log("Received MATH from '" + cs.getName() + "'", LogLevel.INFO);
        try {
            cs.getSocket().write(PacketHelper.ACK(this).toBuffer());
        } catch (IOException e) {
            App.log(packetSendExceptionMessage(PacketType.ACK, PacketType.MATH, cs.getName()), LogLevel.WARN);
            clients.remove(cs.getKey());
            cs.getKey().cancel();
            cs.tryCloseSocket();
            return;
        }

        // only add request if ACK was sent
        requests.add(new MathRequest(p, cs));
    }

    /**
     * Handles the RESULT packet received from the client and removes the client from the server.
     * A client should not send a RESULT packet.
     * 
     * @param p The packet received from the client.
     * @param cs The status of the client.
     */
    private void handleResult(Packet p, ClientStatus cs) {
        App.log(invalidPacketExceptionMessage(PacketType.RESULT, cs), LogLevel.WARN);
        try {
            cs.getSocket().write(PacketHelper.DISCONNECT(this, "Client dropped due to invalid " + PacketType.RESULT + " sent").toBuffer());
        } catch (IOException e) {
            App.log(packetSendExceptionMessage(PacketType.DISCONNECT, null, cs.getName()), LogLevel.WARN);
        }
        clients.remove(cs.getKey());
        cs.getKey().cancel();
        cs.tryCloseSocket();
    }

    /**
     * Closes the given SocketChannel.
     * 
     * @param socket the SocketChannel to be closed
     */
    private void tryCloseSocket(SocketChannel socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // do nothing, doesn't matter will be closed anyway
        }
    }

    /**
     * Returns a string containing an exception message for a failed packet send operation.
     * 
     * @param sendType the type of packet that failed to send
     * @param @Nullable forType the type of packet that the failed packet was intended for (optional)
     * @param receiver the intended recipient of the failed packet
     * @return a string containing the exception message
     */
    private static String packetSendExceptionMessage(PacketType sendType, @Nullable PacketType forType, String receiver) {
        return "Exception sending " + sendType + (forType != null ? " for " + forType : "") + " to '" + receiver + "'! Assuming disconnect";
    }

    private static String invalidPacketExceptionMessage(@Nullable PacketType receivedType, @Nullable ClientStatus cs) {
        return "Invalid packet " + (receivedType != null ? " type " + receivedType : " format ") + " received from " + (cs != null ? "'" + cs.getName() + "'" : " unknown client ")
                + "! Dropping client. Client was connected for " + (cs != null ? cs.getTimeConnected().until(Instant.now(), ChronoUnit.SECONDS) : "UNKNOWN") + " seconds";
    }

    @Override
    public String toString() {
        return "Server/" + HOST + ":" + PORT;
    }

    private static class ClientStatus {
        private boolean connectAck;
        private int heartbeatTimeout;
        private boolean heartbeatSent;
        private final String name;
        private SelectionKey key;
        private final Instant timeConnected;

        public ClientStatus(String name, Instant timeConnected, SelectionKey key) {
            this.connectAck = false;
            this.name = name;
            this.key = key;
            this.timeConnected = timeConnected;
        }

        public String getName() { return name; }

        public boolean isConnectionAck() { return connectAck; }

        public void setConnectionAck() {
            this.connectAck = true;
        }

        public boolean needsHeartbeat() {
            return heartbeatTimeout >= HEARTBEAT_TIMEOUT;
        }

        public void heartbeatAck() {
            this.heartbeatTimeout = 0;
            this.heartbeatSent = false;
        }

        public void incHeartbeatTimeout() {
            this.heartbeatTimeout++;
        }

        public void setHeartbeatSent() {
            this.heartbeatSent = true;
        }

        public boolean getHeartbeatSent() { return heartbeatSent; }

        public SocketChannel getSocket() { return (SocketChannel) key.channel(); }

        public void tryCloseSocket() {
            try {
                getSocket().close();
            } catch (IOException e) {
                // do nothing, doesn't matter will be closed anyway
            }
        }

        public SelectionKey getKey() { return key; }

        public Instant getTimeConnected() { return timeConnected; }

        @Override
        public String toString() {
            return "ClientStatus[" + name + "]";
        }
    }

    public static class MathRequest {
        private final Packet packet;
        private final ClientStatus client;

        public MathRequest(Packet packet, ClientStatus client) {
            this.packet = packet;
            this.client = client;
        }

        public Packet getPacket() { return packet; }

        public ClientStatus getClient() { return client; }
    }
}
