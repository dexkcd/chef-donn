package ph.chefdonn.domain

import kotlinx.serialization.json.Json

/**
 * The one Json config for events/commands everywhere (hub log, wire, client queue).
 * ignoreUnknownKeys + encodeDefaults let old and new app versions coexist on one mesh.
 */
val DomainJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}
