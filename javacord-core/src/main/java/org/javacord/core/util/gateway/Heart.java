package org.javacord.core.util.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neovisionaries.ws.client.WebSocketFrame;
import org.apache.logging.log4j.Logger;
import org.javacord.core.DiscordApiImpl;
import org.javacord.core.util.logging.LoggerUtil;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Every animal has a heart.
 * As we all know that websockets are animals, they obliviously have a heart, too.
 */
public class Heart {

    /**
     * The logger of this class.
     */
    private static final Logger stethoscope = LoggerUtil.getLogger(Heart.class);

    private final DiscordApiImpl api;
    private final Consumer<WebSocketFrame> heartbeatFrameSender;
    private final BiConsumer<Integer, String> closeFrameSender;
    private final boolean voice;

    private final AtomicReference<Future<?>> heartbeatTimer = new AtomicReference<>();
    private final AtomicBoolean heartbeatAckReceived = new AtomicBoolean();
    // -1 means no sequence number received yet (voice gateway v8 expects seq_ack: -1 in that case)
    private volatile int lastSeq = -1;

    // Used to calculate the gateway latency
    private volatile long lastHeartbeatSentTimeNanos = -1;

    /**
     * Ba boom, ba boom, ba boom, ba boom, ...
     *
     * @param api                  The heart of every Javacord bot.
     * @param heartbeatFrameSender A consumer that forwards the given frame to the corresponding web socket.
     * @param closeFrameSender     A bi consumer that sends a close frame with the given code and reason.
     * @param voice                Voice websocket hearts beat differently.
     */
    public Heart(DiscordApiImpl api, Consumer<WebSocketFrame> heartbeatFrameSender,
                 BiConsumer<Integer, String> closeFrameSender, boolean voice) {
        this.api = api;
        this.heartbeatFrameSender = heartbeatFrameSender;
        this.closeFrameSender = closeFrameSender;
        this.voice = voice;
    }

    /**
     * Handles the given packet.
     * Usually used to update the last sequence number and listen for acks.
     *
     * @param packet The packet to handle.
     */
    public void handlePacket(JsonNode packet) {
        if (!voice) {
            // For normal websockets, the last sequence number is sent in the heartbeat
            if (packet.has("s") && !packet.get("s").isNull()) {
                lastSeq = packet.get("s").asInt();
            }
        } else {
            // Voice gateway v8 sends a top-level sequence number which must be acknowledged
            // in heartbeats and resumes via seq_ack
            if (packet.has("seq") && !packet.get("seq").isNull()) {
                lastSeq = packet.get("seq").asInt();
            }
        }
        int heartbeatAckOp = voice ? VoiceGatewayOpcode.HEARTBEAT_ACK.getCode() : GatewayOpcode.HEARTBEAT_ACK.getCode();
        if (packet.get("op").asInt() == heartbeatAckOp) {
            long gatewayLatency = System.nanoTime() - lastHeartbeatSentTimeNanos;
            if (!voice) {
                api.setLatestGatewayLatencyNanos(gatewayLatency);
            }
            stethoscope.debug("Heartbeat ACK received (voice: {}, packet: {}). Took {} ms to receive ACK",
                    voice, packet, TimeUnit.NANOSECONDS.toMillis(gatewayLatency));
            heartbeatAckReceived.set(true);
        }
    }

    /**
     * Ba boom, ba boom, ba boom, ba boom, ..
     * .
     * @param interval Unlike a human heart, websocket hearts have a fixed beating interval.
     */
    public void startBeating(int interval) {
        // first heartbeat should assume last heartbeat was answered properly
        heartbeatAckReceived.set(true);
        heartbeatTimer.updateAndGet(future -> {
            if (future != null) {
                // If there was an old heart beating, crush it
                future.cancel(false);
            }
            return api.getThreadPool().getScheduler().scheduleWithFixedDelay(() -> {
                try {
                    if (heartbeatAckReceived.getAndSet(false)) {
                        beat();
                    } else {
                        stethoscope.debug("Heartbeat not answered properly. This might be because of a busy websocket");
                        if (voice) {
                            // ACKs only arrive in reply to beats, so skipping the beat here would leave the
                            // flag false forever and silently stop all heartbeats until the server times the
                            // session out. Force a close so the websocket reconnects/resumes promptly instead.
                            closeFrameSender.accept(
                                    WebSocketCloseReason.HEARTBEAT_NOT_PROPERLY_ANSWERED.getNumericCloseCode(),
                                    WebSocketCloseReason.HEARTBEAT_NOT_PROPERLY_ANSWERED.getCloseReason());
                        }
                    }
                } catch (Throwable t) {
                    // R.I.P.
                    stethoscope.error("Failed to send heartbeat or close web socket!", t);
                }
            }, 0, interval, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * Ba boom, ba boom, ba boom, ba boom, ...
     */
    public void beat() {
        ObjectNode heartbeatPacket = JsonNodeFactory.instance.objectNode()
                .put("op", voice ? VoiceGatewayOpcode.HEARTBEAT.getCode() : GatewayOpcode.HEARTBEAT.getCode());

        // voice gateway v8 puts the beat inside d { t, seq_ack }
        if (voice) {
            ObjectNode dataNode = JsonNodeFactory.instance.objectNode()
                    .put("t", System.currentTimeMillis())
                    .put("seq_ack", lastSeq);
            heartbeatPacket.set("d", dataNode);
        } else {
            heartbeatPacket.put("d", Math.max(lastSeq, 0));
        }

        WebSocketFrame heartbeatFrame = WebSocketFrame.createTextFrame(heartbeatPacket.toString());
        heartbeatFrameSender.accept(heartbeatFrame);
        lastHeartbeatSentTimeNanos = System.nanoTime();
        // Ba boom, ba boom, ba boom, ba boom, ...
        stethoscope.debug("Sent heartbeat (voice: {}, packet: {})", voice, heartbeatPacket);
    }

    /**
     * Records the sequence number of a voice gateway binary message (its first two bytes, big-endian).
     *
     * @param seq The sequence number.
     */
    void handleBinarySequence(int seq) {
        lastSeq = seq;
    }

    /**
     * Forgets the last sequence number, for a new (not resumed) session.
     */
    void resetSequence() {
        lastSeq = -1;
    }

    /**
     * Gets the last sequence number received from the gateway, or -1 if none was received yet.
     *
     * @return The last sequence number.
     */
    int getLastSeq() {
        return lastSeq;
    }

    /**
     * Squashes the poor heart (stop it from beating).
     */
    public void squash() {
        heartbeatTimer.updateAndGet(future -> {
            if (future != null) {
                future.cancel(false);
            }
            return null;
        });
    }

}
