package phantom.core.transport

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RelayMessageSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    @Test
    fun send_serializesAndDeserializes() {
        val msg = RelayMessage.Send(
            to = "aabbcc",
            from = "ddeeff",
            payload = "base64==",
            messageId = "msg-1",
        )
        val encoded = json.encodeToString<RelayMessage>(msg)
        val decoded = json.decodeFromString<RelayMessage>(encoded)
        assertIs<RelayMessage.Send>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun deliver_roundTrip() {
        val msg = RelayMessage.Deliver(from = "aabbcc", payload = "base64==", messageId = "msg-2")
        val decoded = json.decodeFromString<RelayMessage>(json.encodeToString<RelayMessage>(msg))
        assertIs<RelayMessage.Deliver>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun ack_roundTrip() {
        val msg = RelayMessage.Ack(messageId = "msg-3", status = "relayed")
        val decoded = json.decodeFromString<RelayMessage>(json.encodeToString<RelayMessage>(msg))
        assertIs<RelayMessage.Ack>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun unknownTypeField_isIgnored() {
        val raw = """{"type":"deliver","from":"aa","payload":"bb","messageId":"cc","extra":"ignored"}"""
        val decoded = json.decodeFromString<RelayMessage>(raw)
        assertIs<RelayMessage.Deliver>(decoded)
    }

    @Test
    fun send_containsTypeDiscriminator() {
        val encoded = json.encodeToString<RelayMessage>(
            RelayMessage.Send(to = "a", from = "b", payload = "c", messageId = "d")
        )
        assert(encoded.contains("\"type\":\"send\""))
    }
}
