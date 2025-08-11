package io.github.kingg22.api.vacunas.panama.modules.usuario.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.kingg22.api.vacunas.panama.modules.usuario.domain.UsuarioModel
import io.github.kingg22.api.vacunas.panama.modules.usuario.entity.Usuario
import io.mcarle.konvert.api.KonvertFrom
import io.mcarle.konvert.api.KonvertTo
import io.mcarle.konvert.api.Mapping
import io.quarkus.runtime.annotations.RegisterForReflection
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PastOrPresent
import jakarta.validation.constraints.Size
import java.io.Serializable
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.util.UUID

/** DTO for [io.github.kingg22.api.vacunas.panama.modules.usuario.entity.Usuario] */
@RegisterForReflection
@KonvertTo(
    Usuario::class,
    mappings = [
        Mapping("usuario", "username"), Mapping("clave", "password"),
        Mapping("fabricante", ignore = true), Mapping("persona", ignore = true),
    ],
)
@KonvertTo(UsuarioModel::class)
@KonvertFrom(Usuario::class, [Mapping("username", "usuario"), Mapping("password", "clave")])
@JvmRecord
data class UsuarioDto(
    val id: UUID? = null,

    val username: String? = null,

    @all:Size(min = 8, max = 70, message = "La contraseña no es válida")
    @all:JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    val password: String,

    @all:JsonProperty(value = "created_at")
    @all:PastOrPresent
    val createdAt: LocalDateTime = LocalDateTime.now(UTC),

    @all:JsonProperty(value = "updated_at")
    @all:PastOrPresent
    val updatedAt: LocalDateTime? = null,

    @all:JsonProperty(value = "last_used")
    val lastUsed: LocalDateTime? = null,

    @param:NotEmpty(message = "Los roles no puede estar vacíos")
    @field:NotEmpty(message = "Los roles no puede estar vacíos")
    @param:Valid
    @field:Valid
    val roles: Set<RolDto>,

    @all:JsonIgnore
    val disabled: Boolean = true,
) : Serializable {
    override fun toString() = UsuarioDto::class.simpleName +
        "(id: $id, username: $username, createdAt: $createdAt, updatedAt: $updatedAt, lastUsed: $lastUsed, roles: $roles)"

    companion object
}
