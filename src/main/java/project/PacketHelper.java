package project;

import java.nio.ByteBuffer;
import java.time.Instant;

import org.json.JSONException;
import org.json.JSONObject;

public class PacketHelper {

    static Packet ACK(Object sender) {
        return new Packet(PacketType.ACK, sender);
    }

    static Packet CONNECT(Object sender) {
        return new Packet(PacketType.CONNECT, sender, null);
    }

    static Packet DISCONNECT(Object sender, String content) {
        return new Packet(PacketType.DISCONNECT, sender, content);
    }

    static Packet HEARTBEAT(Object sender) {
        return new Packet(PacketType.HEARTBEAT, sender);
    }

    static Packet MATH(Object sender, String content) {
        return new Packet(PacketType.MATH, sender, content);
    }

    static Packet RESULT(Object sender, String content) {
        return new Packet(PacketType.RESULT, sender, content);
    }

    static Packet parse(ByteBuffer buffer) throws JSONException {
        try {
            return new Packet(new String(buffer.array()).trim());
        } catch (JSONException e) {
            throw new JSONException("Invalid JSON");
        }
    }

    public static class Packet {
        private final PacketType TYPE;
        private final String SENDER;
        private final Instant TIMESTAMP;
        private final String CONTENT;
        private final JSONObject json;

        public Packet(PacketType type, Object sender) {
            this(type, sender, null);
        }

        public Packet(PacketType type, Object sender, String content) {
            this.TYPE = type;
            this.SENDER = sender.toString();
            this.TIMESTAMP = Instant.now();
            this.CONTENT = content;
            this.json = jsonify();
        }

        public Packet(String json) throws JSONException{
            try {
                this.json = new JSONObject(json);
            } catch (Exception e) {
                throw new JSONException("Invalid JSON");
            }
            this.TYPE = PacketType.valueOf(this.json.getString("type"));
            this.SENDER = this.json.getString("sender");
            this.TIMESTAMP = Instant.parse(this.json.getString("timestamp"));
            this.CONTENT = this.json.has("content") ? this.json.getString("content") : null;
        }

        private JSONObject jsonify() {
            JSONObject jobj =  new JSONObject();
            jobj.put("type", this.TYPE);
            jobj.put("sender", SENDER);
            jobj.put("timestamp", TIMESTAMP.toString());
            if (this.CONTENT != null) jobj.put("content", CONTENT);
            return jobj;
        }

        public PacketType getType() {
            return TYPE;
        }

        public String getSender() {
            return SENDER;
        }

        public Instant getTimestamp() {
            return TIMESTAMP;
        }

        public String getContent() {
            return CONTENT;
        }

        public ByteBuffer toBuffer() {
            return ByteBuffer.wrap(json.toString().getBytes());
        }
    }

    public enum PacketType {
        CONNECT("CONNECT"), 
        DISCONNECT("DISCONNECT"), 
        ACK("ACK"), 
        HEARTBEAT("HEARTBEAT"), 
        MATH("MATH"), 
        RESULT("RESULT");

        private final String type;

        PacketType(String type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return type;
        }
    } 
}
