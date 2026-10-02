package eu.europa.ec.corelogic.verana

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DataIntegrityTest {

    private val vtjsc = VeranaDevnetFixtures.vtjsc
    private val ecosystemDocument = VeranaDevnetFixtures.ecosystemDidDocument

    @Test
    fun `verifies the devnet VTJSC against the ecosystem DID document`() {
        assertTrue(DataIntegrity.verifyEddsaJcs2022(vtjsc, ecosystemDocument))
    }

    @Test
    fun `rejects a VTJSC whose subject was changed`() {
        val subject = vtjsc.getValue("credentialSubject").jsonObject
        val tampered = vtjsc.with(
            "credentialSubject",
            JsonObject(subject + ("jsonSchema" to buildJsonObject { put("\$ref", "vpr:verana:vna-devnet-1:cs:9") })),
        )

        assertFalse(DataIntegrity.verifyEddsaJcs2022(tampered, ecosystemDocument))
    }

    @Test
    fun `rejects a VTJSC whose validity was extended`() {
        val tampered = vtjsc.with("validUntil", JsonPrimitive("2099-01-01T00:00:00.000Z"))

        assertFalse(DataIntegrity.verifyEddsaJcs2022(tampered, ecosystemDocument))
    }

    @Test
    fun `rejects a key that is not an assertion method of the issuer`() {
        val withoutAssertion = ecosystemDocument.copy(assertionMethods = emptyList())

        assertFalse(DataIntegrity.verifyEddsaJcs2022(vtjsc, withoutAssertion))
    }

    @Test
    fun `rejects a VTJSC that names another issuer`() {
        val otherIssuer = vtjsc.with("issuer", JsonPrimitive("did:webvh:QmOther:other.example"))

        assertFalse(DataIntegrity.verifyEddsaJcs2022(otherIssuer, ecosystemDocument))
    }

    @Test
    fun `rejects another cryptosuite`() {
        val proof = vtjsc.getValue("proof").jsonObject
        val otherSuite = vtjsc.with("proof", JsonObject(proof + ("cryptosuite" to JsonPrimitive("eddsa-rdfc-2022"))))

        assertFalse(DataIntegrity.verifyEddsaJcs2022(otherSuite, ecosystemDocument))
    }

    @Test
    fun `reads the issuer from a string or an object`() {
        assertEquals(VeranaDevnetFixtures.ECOSYSTEM_DID, DataIntegrity.issuerOf(vtjsc))
        assertEquals(
            "did:web:issuer.example",
            DataIntegrity.issuerOf(buildJsonObject {
                put("issuer", buildJsonObject { put("id", "did:web:issuer.example") })
            }),
        )
    }

    @Test
    fun `accepts a credential inside its validity window or without one`() {
        assertTrue(DataIntegrity.withinValidity(JsonObject(emptyMap()), NOW))
        assertTrue(DataIntegrity.withinValidity(vtjsc, NOW))
    }

    @Test
    fun `refuses an expired, not yet valid or unreadable window`() {
        assertFalse(DataIntegrity.withinValidity(buildJsonObject { put("validUntil", "2026-09-01T00:00:00Z") }, NOW))
        assertFalse(DataIntegrity.withinValidity(buildJsonObject { put("validFrom", "2026-11-01T00:00:00Z") }, NOW))
        assertFalse(DataIntegrity.withinValidity(buildJsonObject { put("validUntil", "soon") }, NOW))
        assertFalse(DataIntegrity.withinValidity(buildJsonObject { put("validFrom", 1) }, NOW))
    }

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-02T00:00:00Z")
    }
}
