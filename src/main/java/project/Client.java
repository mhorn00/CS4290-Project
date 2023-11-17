package project;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.json.JSONException;

import project.App.LogLevel;
import project.PacketHelper.Packet;
import project.PacketHelper.PacketType;

/**
 * Represents a client that connects to a server and sends math expressions to be evaluated.
 * The client listens for packets from the server and handles them accordingly.
 */
class Client {
    public final String NAME;
    public final String HOST;
    public final int PORT;
    public final SocketChannel socket;
    public boolean isConnected;
    public volatile boolean expectResult;
    public volatile boolean shouldExit;

    public Client(String host, int port, String name) throws IOException {
        this.HOST = host;
        this.PORT = port;
        this.NAME = name;
        this.isConnected = false;
        this.expectResult = false;
        this.shouldExit = false;
        this.socket = SocketChannel.open();
    }

    /**
     * Starts the client by establishing a socket connection with the server and sending a CONNECT packet.
     * Then, listens for incoming packets and handles them accordingly.
     */
    public void start() {
        App.log("Starting client " + NAME, LogLevel.INFO);

        // Establish socket and send CONNECT
        try {
            socket.connect(new InetSocketAddress(HOST, PORT));
            socket.write(PacketHelper.CONNECT(this).toBuffer());
        } catch (IOException e) {
            App.log("Exception connecting to " + HOST + ":" + PORT, LogLevel.ERROR);
            return;
        }
        App.log("Sent CONNECT to " + HOST + ":" + PORT, LogLevel.INFO);

        // Start listening for packets
        while (!shouldExit) {
            ByteBuffer buffer = ByteBuffer.allocate(2048);
            try {
                socket.read(buffer);
            } catch (IOException e) {
                if (shouldExit) {
                    return;
                }
                App.log("Exception reading from socket. Terminating...", LogLevel.ERROR);
                System.exit(-1);
                return;
            }
            
            // ignore empty packets
            if (new String(buffer.array()).trim().isEmpty()) {
                continue;
            }

            // parse packet
            Packet p;
            try {
                p = PacketHelper.parse(buffer);
            } catch (JSONException e) {
                App.log("Invalid packet received from server. Terminating...", LogLevel.ERROR);
                System.exit(-1);
                return;
            }

            // handle packet
            switch (p.getType()) {
            case HEARTBEAT:
                handleHeartbeat(p);
                break;
            case RESULT:
                handleResult(p);
                break;
            case ACK:
                handleAck(p);
                break;
            case DISCONNECT:
                handleDisconnect(p);
                break;
            default:
                handleInvalidPacket(p.getType(), p.getSender());
                break;
            }
        }
    }

    /**
     * Handles an invalid packet received from the server.
     * @param type The type of the invalid packet.
     * @param sender The sender of the invalid packet.
     */
    private void handleInvalidPacket(PacketType type, String sender) {
        App.log("Invalid packet " + type + " received from '" + sender + "'! Terminating...", LogLevel.ERROR);
        try {
            socket.write(PacketHelper.DISCONNECT(this, "Invalid packet " + type + " received from server! Disconnecting...").toBuffer());
        } catch (IOException e) {
            App.log("Exception sending DISCONNECT to socket. Terminating anyway", LogLevel.ERROR);
        }
        System.exit(-1);
    }

    /**
     * Handles a heartbeat packet by sending a heartbeat packet back to the sender.
     * 
     * @param p the received heartbeat packet
     */
    private void handleHeartbeat(Packet p) {
        // App.log("Received HEARTBEAT from '" + p.getSender() + "'", LogLevel.INFO);
        try {
            socket.write(PacketHelper.HEARTBEAT(this).toBuffer());
        } catch (IOException e) {
            App.log("Exception sending HEARTBEAT to '" + p.getSender() + "'! Terminating...", LogLevel.ERROR);
            System.exit(-1);
        }
    }

    /**
     * Handles an ACK packet received from the server.
     * If the client is not connected, sends an ACK for CONNECT and starts the keyboard input thread.
     * If the client is connected and expecting a result, logs the ACK for MATH.
     * Otherwise, logs that an ACK was received but not needed.
     *
     * @param p The ACK packet received from the server.
     */
    private void handleAck(Packet p) {
        if (!isConnected) {
            App.log("Received ACK for CONNECT from '" + p.getSender() + "'", LogLevel.INFO);
            try {
                socket.write(PacketHelper.ACK(this).toBuffer());
            } catch (IOException e) {
                App.log("Exception sending ACK for CONNECT to '" + p.getSender() + "'! Terminating...", LogLevel.ERROR);
                System.exit(-1);
                return;
            }
            // Start keyboard input thread after successful connection
            new KeyboardInputThread(this).start();
            isConnected = true;
        } else if (isConnected && expectResult) {
            App.log("Received ACK for MATH from '" + p.getSender() + "'", LogLevel.INFO);
        } else {
            App.log("Received ACK from '" + p.getSender() + "' but no ACK needed", LogLevel.WARN);
        }
    }

    /**
     * Handles the DISCONNECT packet received from the server.
     * Closes the socket and terminates the program.
     * 
     * @param p the DISCONNECT packet received from the server
     */
    private void handleDisconnect(Packet p) {
        App.log("Received DISCONNECT from '" + p.getSender() + "'" + (p.getContent() != null ? " with reason '" + p.getContent() + "'" : "") + ". Terminating...", LogLevel.INFO);
        try {
            socket.close();
        } catch (IOException e) {
            // do nothing, doesn't matter will be closed anyway
        }
        System.exit(0);
    }

    
    /**
     * Handles the received packet of type RESULT.
     * Logs the received message and sets the expectResult flag to false.
     * Sends an ACK packet to the sender.
     * If an IOException occurs while sending the ACK packet, logs an error and terminates the program.
     * 
     * @param p the received packet of type RESULT
     */
    private void handleResult(Packet p) {
        App.log("Received RESULT from '" + p.getSender() + "': " + p.getContent(), LogLevel.INFO);
        expectResult = false;
        try {
            socket.write(PacketHelper.ACK(this).toBuffer());
        } catch (IOException e) {
            App.log("Exception sending ACK for RESULT to '" + p.getSender() + "'! Terminating...", LogLevel.ERROR);
            System.exit(-1);
            return;
        }
    }

    @Override
    public String toString() {
        return "Client[" + NAME + "]/" + HOST + ":" + PORT;
    }

    /**
     * Thread that listens for keyboard input and sends it to the server
     * 
     * @param client
     */
    static class KeyboardInputThread extends Thread {
        private final Client client;
        
        public KeyboardInputThread(Client client) {
            this.client = client;
        }

        @Override
        public void run() {
            App.log("Type a math expression to send to the server, or type 'exit' to disconnect", LogLevel.INFO);
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                String input = "";

                // Try to read from stdin
                try {
                    input = reader.readLine();
                } catch (IOException e) {
                    App.log("Exception reading input", LogLevel.WARN);
                }

                // if input is not empty, trim it
                if (input != null && !input.trim().replaceAll("\\s", "").isEmpty()) {
                    input = input.trim().replaceAll("\\s", "");

                    // if input is too long, warn and continue
                    if (input.length() > 1000) {
                        App.log("Input too long. Max 1000 characters", LogLevel.WARN);
                        continue;
                    }

                    // if input is exit and client is connected, send DISCONNECT and exit
                    if (input.equals("exit") && client.isConnected) {
                        client.shouldExit = true;
                        App.log("Disconnecting...", LogLevel.INFO);
                        try {
                            client.socket.write(PacketHelper.DISCONNECT(client, "Client requested disconnect").toBuffer());
                            client.socket.close();
                        } catch (IOException e) {
                            App.log("Exception sending DISCONNECT to server", LogLevel.ERROR);
                        }
                        System.exit(0);
                        return;

                        // else send math packet to server
                    } else if (client.isConnected && !client.expectResult) {
                        try {
                            client.socket.write(PacketHelper.MATH(client, input).toBuffer());
                            client.expectResult = true;
                        } catch (IOException e) {
                            App.log("Exception sending MATH to server. Assuming Disconnect...", LogLevel.ERROR);
                            try {
                                client.socket.close();
                            } catch (IOException e1) {
                                // do nothing, doesn't matter will be closed anyway
                            }
                            System.exit(-1);
                            return;
                        }
                    } else if (client.isConnected && client.expectResult) {
                        App.log("Already waiting for result. Please wait...", LogLevel.WARN);
                    }
                }
            }
        }
    }
}