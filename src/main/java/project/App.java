package project;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class App {

    public static void main(String[] args) {
        Map<String, Object> arguments = parseArgs(args);
        if (arguments.containsKey("server")) {
            if (arguments.containsKey("port") && arguments.containsKey("host")) {
                try {
                    Server server = new Server((String)arguments.get("host"), (int)arguments.get("port"));
                    server.start();
                } catch (IOException e) {
                    log("Exception starting server", LogLevel.ERROR);
                    e.printStackTrace();
                }
            } else {
                log("Missing port or host argument", LogLevel.ERROR);
                helpMsg();
            }
        } else if (arguments.containsKey("client")) {
            if (arguments.containsKey("port") && arguments.containsKey("host") && arguments.containsKey("name")) {
                try {
                    Client client = new Client((String)arguments.get("host"), (int)arguments.get("port"), (String)arguments.get("name"));
                    client.start();
                } catch (IOException e) {
                    log("Exception starting client", LogLevel.ERROR);
                    e.printStackTrace();
                }
            } else {
                log("Missing port, host, or name argument", LogLevel.ERROR);
                helpMsg();
            }
        } else {
            log("Missing client or server argument", LogLevel.ERROR);
            helpMsg();
        }
    }


    public static Map<String, Object> parseArgs(String[] args) {
        Map<String, Object> out = new HashMap<String, Object>();
        if (args == null || args.length == 0) {
            log("Missing args", LogLevel.ERROR);
            helpMsg();
            return null;
        }
        for (int i=0; i<args.length; i++) {
            if (args[i].equals("-server")) {
                out.put("server", true);
            } else if (args[i].equals("-client")) {
                out.put("client", true);
            }else if (args[i].startsWith("-")) {
                if (args.length >= i+1) {
                    if (args[i].equals("-port")) {
                        if (args[i+1].matches("[0-9]+")){
                            out.put("port", Integer.parseInt(args[i+1]));
                        } else {
                            log("Invalid port number", LogLevel.ERROR);
                            helpMsg();
                        }
                    } else if (args[i].equals("-host")) {
                        if (args[i+1].matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}") || args[i+1].equals("localhost")){
                            out.put("host", args[i+1]);
                        } else {
                            log("Invalid host address", LogLevel.ERROR);
                            helpMsg();
                        }
                    } else if (args[i].equals("-name")) {
                        out.put("name", args[i+1]);
                    }
                } else {
                    log("Missing value for arg"+ args[i], LogLevel.ERROR);
                    helpMsg();
                }
            }  
        }
        return out;
    }

    public static void helpMsg() {
        log("Usage: java -jar NetworkingProject.jar -server -port <port> -host <host>", LogLevel.INFO);
        log("Usage: java -jar NetworkingProject.jar -client -port <port> -host <host> -name <name>", LogLevel.INFO);
        System.exit(-1);
    }

    public static void log(String msg, LogLevel level) {
        System.out.println("["+LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))+"] ["+level+"] "+msg);
    }

    public enum LogLevel {
        INFO("INFO"),
        WARN("WARN"), 
        ERROR("ERROR");

        private final String level;

        LogLevel(String level) {
            this.level = level;
        }

        @Override
        public String toString() {
            return level;
        }
    }
}

/*
 * Server app requirements:
 *  DONE - Keep track of connected users, who, when, and how long
 *  DONE - wait for client request then log connection
 *  DONE - multiple simultaneous connections
 *  DONE - string requests for basic math functions, log requests
 *  DONE - send back result in order of request received
 *  DONE - close connection upon client request
 *  DONE - (potentially use heartbeat to track if client disconnects ungracefully)
 * 
 * Client app requirements:
 *  DONE - connect to server with name and wait for ack
 *  DONE - send request for math func
 *  DONE - close connection upon user request
 *  DONE - (respond to heartbeat)
 * 
 *  Protocol:
 *  - json packets
 *  - types: connect, disconnect, ack, heartbeat, math, result
 */