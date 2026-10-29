package org.javacord.core.util.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.neovisionaries.ws.client.WebSocket
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import org.javacord.api.entity.channel.ServerVoiceChannel
import org.javacord.api.entity.server.Server
import org.javacord.api.entity.user.User
import org.javacord.core.DiscordApiImpl
import org.javacord.core.audio.AudioConnectionImpl
import org.javacord.core.util.concurrent.ThreadPoolImpl
import org.javacord.core.util.dave.DaveFrameEncryptor
import org.javacord.core.util.dave.LibDave
import org.javacord.core.util.dave.LibDaveLoader
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the voice gateway adapter with gateway packets. The websocket, UDP socket and libdave are mocked.
 */
@Subject(AudioWebSocketAdapter)
class AudioWebSocketAdapterTest extends Specification {

    static final Byte PT_ON = 1 as Byte
    static final Byte PT_OFF = 0 as Byte

    static final String SELF_ID = '806350925723205642'
    static final String OTHER_ID = '1017608119855108177'
    static final long CHANNEL_ID = 1184647280066822234L
    static final Pointer SESSION = new Pointer(1L)
    static final Pointer ENCRYPTOR = new Pointer(2L)
    static final Pointer DECRYPTOR = new Pointer(3L)
    static final Pointer RATCHET = new Pointer(4L)
    static final Pointer RESULT = new Pointer(5L)

    LibDave lib = Mock()
    WebSocket ws = Mock()
    AudioUdpSocket udp = Mock()
    AudioConnectionImpl connection = Mock()
    CompletableFuture readyFuture = new CompletableFuture()
    AudioWebSocketAdapter adapter

    Object savedInstance
    Object savedLoadAttempted

    def setup() {
        swapLibDave(lib, true)

        lib.daveMaxSupportedProtocolVersion() >> (short) 1
        lib.daveSessionCreate(*_) >> SESSION
        lib.daveEncryptorCreate() >> ENCRYPTOR
        lib.daveDecryptorCreate() >> DECRYPTOR
        lib.daveSessionGetKeyRatchet(SESSION, SELF_ID) >> RATCHET
        lib.daveSessionGetMarshalledKeyPackage(SESSION, _ as PointerByReference, _ as LongByReference) >> { args ->
            Memory memory = new Memory(1)
            memory.setByte(0, (byte) 0x42)
            args[1].setValue(memory)
            args[2].setValue(1L)
        }

        DiscordApiImpl api = Mock()
        ThreadPoolImpl threadPool = Mock()
        threadPool.getScheduler() >> Mock(ScheduledExecutorService)
        threadPool.getDaemonScheduler() >> Mock(ScheduledExecutorService)
        api.getThreadPool() >> threadPool
        api.getObjectMapper() >> new ObjectMapper()
        User self = Mock()
        self.getIdAsString() >> SELF_ID
        self.getId() >> Long.parseLong(SELF_ID)
        api.getYourself() >> self
        Server server = Mock()
        server.getApi() >> api
        server.getIdAsString() >> '1004950494147526747'
        ServerVoiceChannel channel = Mock()
        channel.getApi() >> api
        channel.getId() >> CHANNEL_ID
        channel.getConnectedUserIds() >> ([] as Set)
        connection.getChannel() >> channel
        connection.getServer() >> server
        connection.getReadyFuture() >> readyFuture
        connection.getSpeakingFlags() >> ([] as Set)
        // an unreachable endpoint: the constructor's connect attempt fails fast and schedules a retry
        connection.getEndpoint() >> '127.0.0.1:1'

        adapter = new AudioWebSocketAdapter(connection)
        adapter.@reconnect = false
        ((AtomicReference) adapter.@websocket).set(ws)
        adapter.@socket = udp
    }

    def cleanup() {
        swapLibDave(savedInstance, savedLoadAttempted)
    }

    private void swapLibDave(Object instance, Object loadAttempted) {
        def instanceField = LibDaveLoader.getDeclaredField('instance')
        def attemptedField = LibDaveLoader.getDeclaredField('loadAttempted')
        [instanceField, attemptedField]*.accessible = true
        if (savedLoadAttempted == null) {
            savedInstance = instanceField.get(null)
            savedLoadAttempted = attemptedField.get(null)
        }
        instanceField.set(null, instance)
        attemptedField.set(null, loadAttempted)
    }

    private void text(String json) {
        adapter.onTextMessage(ws, json)
    }

    private static byte[] daveBinary(int seq, int op, int transitionId, int... body) {
        byte[] frame = new byte[5 + body.length]
        frame[0] = (byte) (seq >> 8)
        frame[1] = (byte) seq
        frame[2] = (byte) op
        frame[3] = (byte) (transitionId >> 8)
        frame[4] = (byte) transitionId
        body.eachWithIndex { b, i -> frame[5 + i] = (byte) b }
        return frame
    }

    private static String sessionDescription(int daveVersion) {
        '{"op":4,"seq":3,"d":{"mode":"aead_aes256_gcm_rtpsize","secret_key":[1,2,3],' +
                "\"dave_protocol_version\":${daveVersion}}}"
    }

    def 'connect resolves at the session description even though DAVE is still handshaking'() {
        when:
            text(sessionDescription(1))

        then: 'the regression: kmusic waited for DAVE execute_transition, which never comes for transition 0'
            readyFuture.isDone()
            readyFuture.get() == connection
            1 * udp.setDaveEncryptor({ it instanceof DaveFrameEncryptor })
            1 * udp.startSending()
            1 * ws.sendBinary({ byte[] f -> f[0] == 26 })
    }

    def 'protocol version 0 still gets a passthrough DAVE session that can upgrade via epoch 1'() {
        when:
            text(sessionDescription(0))

        then:
            readyFuture.isDone()
            1 * udp.setDaveEncryptor({ it instanceof DaveFrameEncryptor })
            1 * udp.startSending()
            0 * ws.sendBinary(_)
            adapter.getDaveManager().getState().name() == 'PASSTHROUGH'

        when:
            text('{"op":24,"seq":4,"d":{"protocol_version":1,"epoch":1}}')

        then:
            1 * ws.sendBinary({ byte[] f -> f[0] == 26 })
            adapter.getDaveManager().getState().name() == 'AWAITING_GROUP'
    }

    def 'connect resolves without DAVE when libdave is unavailable'() {
        given:
            swapLibDave(null, true)

        when:
            text(sessionDescription(1))

        then:
            readyFuture.isDone()
            1 * udp.startSending()
            adapter.getDaveManager() == null
    }

    def 'connect still resolves, without E2EE, when libdave cannot create a session'() {
        given:
            LibDave broken = Mock()
            broken.daveSessionCreate(*_) >> null
            swapLibDave(broken, true)

        when:
            text(sessionDescription(1))

        then:
            readyFuture.isDone()
            1 * udp.startSending()
            adapter.getDaveManager() == null
    }

    def 'after a resume, DAVE messages go out on the new websocket'() {
        given:
            WebSocket resumed = Mock()
            lib.daveSessionProcessWelcome(*_) >> null
            text(sessionDescription(1))
            ((AtomicReference) adapter.@websocket).set(resumed)

        when: 'a failed welcome makes the manager send op 31 and a new key package'
            adapter.onBinaryMessage(resumed, daveBinary(6, 30, 4, 0x01))

        then:
            1 * resumed.sendFrame({ it.getPayloadText().contains('"op":31') })
            1 * resumed.sendBinary({ byte[] f -> f[0] == 26 })
            0 * ws.sendFrame(_)
            0 * ws.sendBinary(_)
    }

    def 'resume acknowledges the last sequence number and identify starts a new sequence'() {
        given:
            String resume = null
            ws.sendFrame(_) >> { args -> resume = args[0].getPayloadText(); ws }
            text('{"op":5,"seq":12,"d":{"speaking":1,"ssrc":1,"user_id":"1"}}')
            text("{\"op\":11,\"d\":{\"user_ids\":[\"${OTHER_ID}\"]}}")

        when:
            adapter.sendResume(ws)

        then:
            new ObjectMapper().readTree(resume).get('d').get('seq_ack').asInt() == 12

        when:
            adapter.sendIdentify(ws)

        then:
            adapter.@heart.getLastSeq() == -1
            adapter.@connectedUserIds.isEmpty()
    }

    def 'no DAVE session is created after disconnect'() {
        given:
            adapter.disconnect()

        when:
            text(sessionDescription(1))

        then:
            0 * lib.daveSessionCreate(*_)
            adapter.getDaveManager() == null
            readyFuture.isDone()
    }

    def 'a welcome with transition 0 establishes E2EE without any execute or ready message'() {
        given:
            lib.daveSessionProcessWelcome(*_) >> RESULT
            text(sessionDescription(1))

        when:
            adapter.onBinaryMessage(ws, daveBinary(4, 30, 0, 0x01))

        then:
            1 * lib.daveEncryptorSetKeyRatchet(ENCRYPTOR, RATCHET)
            1 * lib.daveEncryptorSetPassthroughMode(ENCRYPTOR, PT_OFF)
            0 * ws.sendFrame(_)
            adapter.getDaveManager().getState().name() == 'ESTABLISHED'
    }

    def 'clients_connect user_ids are recognized, including ones that arrive before the session description'() {
        given:
            String[] recognized = null
            text("{\"op\":11,\"seq\":1,\"d\":{\"user_ids\":[\"${OTHER_ID}\"]}}")
            text(sessionDescription(1))

        when:
            adapter.onBinaryMessage(ws, daveBinary(4, 30, 0, 0x01))

        then:
            1 * lib.daveSessionProcessWelcome(*_) >> { args ->
                recognized = args[3]
                RESULT
            }
            recognized as Set == [SELF_ID, OTHER_ID] as Set
    }

    def 'client_disconnect stops add proposals for the user but a welcome may still list them'() {
        given:
            String[] forProposals = null
            String[] forWelcome = null
            text("{\"op\":11,\"d\":{\"user_ids\":[\"${OTHER_ID}\"]}}")
            text(sessionDescription(1))
            text("{\"op\":13,\"d\":{\"user_id\":\"${OTHER_ID}\"}}")

        when:
            adapter.onBinaryMessage(ws, daveBinary(5, 27, 0, 0x01))
            adapter.onBinaryMessage(ws, daveBinary(6, 30, 0, 0x01))

        then:
            1 * lib.daveSessionProcessProposals(*_) >> { args -> forProposals = args[3] }
            1 * lib.daveSessionProcessWelcome(*_) >> { args ->
                forWelcome = args[3]
                RESULT
            }
            forProposals as List == [SELF_ID]
            forWelcome as Set == [SELF_ID, OTHER_ID] as Set
    }

    def 'sequence numbers from JSON and binary frames are acknowledged'() {
        when:
            text(sessionDescription(1))

        then:
            adapter.@heart.getLastSeq() == 3

        when:
            adapter.onBinaryMessage(ws, daveBinary(0x0102, 25, 0, 0x01))

        then:
            adapter.@heart.getLastSeq() == 0x0102
    }

    def 'heartbeats carry seq_ack'() {
        given:
            String sent = null
            ws.sendFrame(_) >> { args -> sent = args[0].getPayloadText(); ws }
            text('{"op":5,"seq":9,"d":{"speaking":1,"ssrc":1,"user_id":"1"}}')

        when:
            adapter.@heart.beat()

        then:
            new ObjectMapper().readTree(sent).get('d').get('seq_ack').asInt() == 9
    }

    def 'a new session description replaces the DAVE session and frees the old one first'() {
        given:
            text(sessionDescription(1))

        when:
            text(sessionDescription(1))

        then:
            1 * udp.setDaveEncryptor(null)

        then:
            1 * lib.daveEncryptorDestroy(ENCRYPTOR)
            1 * lib.daveSessionDestroy(SESSION)
    }

    def 'teardown detaches the encryptor from the send loop before freeing it'() {
        given:
            text(sessionDescription(1))

        when:
            adapter.cleanupDaveResources()

        then:
            1 * udp.setDaveEncryptor(null)

        then:
            1 * lib.daveEncryptorDestroy(ENCRYPTOR)
            adapter.getDaveManager() == null
    }
}
