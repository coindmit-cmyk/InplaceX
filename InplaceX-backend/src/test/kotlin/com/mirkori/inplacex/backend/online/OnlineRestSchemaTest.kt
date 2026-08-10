package com.mirkori.inplacex.backend.online

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineRestSchemaTest {
    @Test
    fun `rest schema publishes targeted invite request and bounded incoming collection`() {
        val schemaFile = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { root -> File(root, "schemas/online/v1/rest.schema.json") }
            .first(File::isFile)
        val root = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val definitions = root.getValue("\$defs").jsonObject

        val createProperties = definitions.getValue("FriendInviteCreateCommand")
            .jsonObject
            .getValue("properties")
            .jsonObject
        assertEquals(
            "common.schema.json#/\$defs/Uuid",
            createProperties.getValue("targetPlayerId").jsonObject.getValue("\$ref").jsonPrimitive.content,
        )

        val collection = definitions.getValue("FriendInviteCollection").jsonObject
        assertEquals("array", collection.getValue("type").jsonPrimitive.content)
        assertEquals(50, collection.getValue("maxItems").jsonPrimitive.content.toInt())
        assertEquals(
            "#/\$defs/FriendInvite",
            collection.getValue("items").jsonObject.getValue("\$ref").jsonPrimitive.content,
        )
        assertTrue(
            root.getValue("oneOf")
                .toString()
                .contains("#/\$defs/FriendInviteCollection"),
        )
    }
}
