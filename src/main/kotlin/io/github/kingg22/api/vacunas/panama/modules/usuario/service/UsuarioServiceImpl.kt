package io.github.kingg22.api.vacunas.panama.modules.usuario.service

import io.github.kingg22.api.vacunas.panama.modules.fabricante.entity.toFabricanteDto
import io.github.kingg22.api.vacunas.panama.modules.fabricante.service.FabricanteService
import io.github.kingg22.api.vacunas.panama.modules.persona.entity.toPersonaDto
import io.github.kingg22.api.vacunas.panama.modules.persona.service.PersonaService
import io.github.kingg22.api.vacunas.panama.modules.usuario.dto.RegisterUserDto
import io.github.kingg22.api.vacunas.panama.modules.usuario.dto.RestoreDto
import io.github.kingg22.api.vacunas.panama.modules.usuario.dto.RolDto
import io.github.kingg22.api.vacunas.panama.modules.usuario.dto.RolesEnum
import io.github.kingg22.api.vacunas.panama.modules.usuario.dto.UsuarioDto
import io.github.kingg22.api.vacunas.panama.modules.usuario.dto.toUsuario
import io.github.kingg22.api.vacunas.panama.modules.usuario.entity.toUsuarioDto
import io.github.kingg22.api.vacunas.panama.modules.usuario.persistence.UsuarioPersistenceService
import io.github.kingg22.api.vacunas.panama.modules.usuario.service.RegistrationResult.RegistrationError
import io.github.kingg22.api.vacunas.panama.response.ActualApiResponse
import io.github.kingg22.api.vacunas.panama.response.ApiError
import io.github.kingg22.api.vacunas.panama.response.ApiResponseCode
import io.github.kingg22.api.vacunas.panama.response.ApiResponseFactory.createApiErrorBuilder
import io.github.kingg22.api.vacunas.panama.response.ApiResponseFactory.createContentResponse
import io.github.kingg22.api.vacunas.panama.response.ApiResponseFactory.createResponseBuilder
import io.github.kingg22.api.vacunas.panama.response.returnIfErrors
import io.github.kingg22.api.vacunas.panama.util.FormatterUtil.formatToSearch
import io.github.kingg22.api.vacunas.panama.util.HaveIBeenPwnedPasswordChecker
import io.github.kingg22.api.vacunas.panama.util.bcryptHash
import io.github.kingg22.api.vacunas.panama.util.bcryptMatch
import io.github.kingg22.api.vacunas.panama.util.logger
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.util.UUID

@ApplicationScoped
class UsuarioServiceImpl(
    private val compromisedPasswordChecker: HaveIBeenPwnedPasswordChecker,
    private val usuarioPersistenceService: UsuarioPersistenceService,
    private val registrationStrategyFactory: RegistrationStrategyFactory,
    private val rolPermisoService: RolPermisoService,
    private val personaService: PersonaService,
    private val fabricanteService: FabricanteService,
    private val tokenService: TokenService,
) : UsuarioService {
    private val log = logger()

    override suspend fun getUsuarioByIdentifier(identifier: String): UsuarioDto? {
        val byUsername = usuarioPersistenceService.findByUsername(identifier)?.toUsuarioDto()?.also {
            log.debug("Found user by username: {}", it.id)
        }
        if (byUsername != null) return byUsername

        val formatted = formatToSearch(identifier)
        val byPersona = usuarioPersistenceService.findByCedulaOrPasaporteOrCorreo(
            formatted.cedula,
            formatted.pasaporte,
            formatted.correo,
        )?.toUsuarioDto()?.also {
            log.debug("Found user: {}, with credentials of Persona", it.id)
        }
        if (byPersona != null) return byPersona

        return usuarioPersistenceService.findByLicenciaOrCorreo(identifier, identifier)?.toUsuarioDto()?.also {
            log.debug("Found user: {}, with credentials of Fabricante", it.id)
        }
    }

    override suspend fun getUsuarioById(id: UUID) = usuarioPersistenceService.findUsuarioById(id)?.toUsuarioDto()

    override suspend fun getProfile(id: UUID): ActualApiResponse {
        val builder = createResponseBuilder()
        personaService.getPersonaByUserID(id)?.let { persona -> builder.withData("persona", persona) }
        fabricanteService.getFabricanteByUserID(id)?.let { fabricante -> builder.withData("fabricante", fabricante) }
        return builder.build()
    }

    override suspend fun getLogin(id: UUID): ActualApiResponse {
        val response = createResponseBuilder()
        val usuario = usuarioPersistenceService.findUsuarioById(id)
        if (usuario != null) {
            if (usuario.persona != null) {
                response.withData("persona", usuario.persona!!.toPersonaDto())
            }
            if (usuario.fabricante != null) {
                response.withData("fabricante", usuario.fabricante!!.toFabricanteDto())
            }
            response.withData(
                tokenService.generateTokens(
                    usuario.toUsuarioDto(),
                    mapOf("persona" to usuario.persona?.id, "fabricante" to usuario.fabricante?.id),
                ),
            )
        } else {
            response.withError(
                ApiResponseCode.NOT_FOUND,
                "El usuario no ha sido encontrado, intente nuevamente.",
            )
        }
        return response.build()
    }

    override suspend fun createUser(registerUserDto: RegisterUserDto, scope: Set<String>): ActualApiResponse {
        val response = createContentResponse()
        val usuarioDto = registerUserDto.usuario

        if (scope.isNotEmpty()) {
            response.addErrors(validateAuthoritiesRegister(usuarioDto, scope))
            response.returnIfErrors()?.let { return it as ActualApiResponse }
        }
        response.addWarnings(validateWarningsRegistration(usuarioDto))

        val validationResult = validateRegistration(registerUserDto)
        if (validationResult is RegistrationError) {
            response.addErrors(validationResult.errors)
            return response
        }

        val strategy = registrationStrategyFactory.getStrategy(registerUserDto)
            ?: return response.apply {
                addError(
                    createApiErrorBuilder {
                        withCode(ApiResponseCode.API_UPDATE_UNSUPPORTED)
                        withMessage("No se encontró estrategia válida para registrarse")
                    },
                )
            }

        val finalResponse = strategy.create(registerUserDto)
        response.mergeContentResponse(finalResponse)
        return response
    }

    override suspend fun createUser(usuarioDto: UsuarioDto, personaId: UUID?, fabricanteId: UUID?): UsuarioDto {
        val roles = rolPermisoService.convertToExistRol(usuarioDto.roles)
        val encodedPassword = usuarioDto.password.bcryptHash()

        return usuarioPersistenceService.createUser(
            usuarioDto = usuarioDto,
            personaId = personaId,
            fabricanteId = fabricanteId,
            encodedPassword = encodedPassword,
            roles = roles,
        ).let {
            log.trace("User entity created: {}", it.toString())
            log.trace("User created: {}", it.toUsuarioDto().toString())
            log.trace("Have user: {}", it.persona?.usuario)
            it.toUsuarioDto()
        }
    }

    override suspend fun changePassword(restoreDto: RestoreDto): ActualApiResponse {
        val response = createResponseBuilder()
        val usuarioOpt = getUsuarioByIdentifier(restoreDto.username)

        if (usuarioOpt == null || usuarioOpt.id == null) {
            response.withError(
                ApiResponseCode.NOT_FOUND,
                "La persona con la identificación dada no fue encontrada",
                "username",
            )
            return response.build()
        }

        val usuario = usuarioOpt
        response.withError(validateChangePassword(usuario, restoreDto))

        personaService.getPersonaByUserID(usuario.id)?.let {
            if (it.fechaNacimiento == null) {
                response.withError(
                    ApiResponseCode.MISSING_INFORMATION,
                    "Operación no permitida. La fecha de nacimiento de la persona es null",
                    "fecha_nacimiento",
                )
                return@let
            }
            if (!it.fechaNacimiento.toLocalDate().isEqual(restoreDto.fechaNacimiento)) {
                response.withError(
                    ApiResponseCode.VALIDATION_FAILED,
                    "La fecha de nacimiento no coincide con la registrada",
                    "fecha_nacimiento",
                )
            }
        } ?: response.withError(
            ApiResponseCode.VALIDATION_FAILED,
            "No se pudo encontrar la persona asociada al usuario",
        )

        if (!response.hasErrors()) {
            val persistenceUsuario = usuario.copy(password = restoreDto.newPassword.bcryptHash()).toUsuario()
            usuarioPersistenceService.saveUsuario(persistenceUsuario)
        }

        return response.build()
    }

    override suspend fun updateLastUsed(id: UUID) {
        val usuario = usuarioPersistenceService.findUsuarioById(id)
        if (usuario == null) {
            log.error("Cannot find a user with id {} for update last used", id)
        } else {
            usuario.lastUsed = LocalDateTime.now(UTC)
            usuarioPersistenceService.saveUsuario(usuario)
        }
    }

    private suspend fun validateChangePassword(usuario: UsuarioDto, restoreDto: RestoreDto): List<ApiError> {
        val newPassword = "new_password"
        val builder = createResponseBuilder()
        if (restoreDto.newPassword.bcryptMatch(usuario.password)) {
            builder.withError(
                ApiResponseCode.VALIDATION_FAILED,
                "La nueva contraseña no puede ser igual a la contraseña actual",
                newPassword,
            )
        }
        if (usuario.username != null && restoreDto.newPassword.contains(usuario.username, true)) {
            builder.withError(
                ApiResponseCode.VALIDATION_FAILED,
                "La nueva contraseña no puede tener su username",
                newPassword,
            )
        }
        if (compromisedPasswordChecker.isPasswordCompromised(restoreDto.newPassword)) {
            builder.withError(
                ApiResponseCode.VALIDATION_FAILED,
                "La nueva contraseña está comprometida, utilice contraseñas seguras",
                newPassword,
            )
        }
        return builder.build().errors
    }

    private fun validateAuthoritiesRegister(usuarioDto: UsuarioDto, authentication: Set<String>): List<ApiError> {
        val authenticatedRoles = authentication
            .mapNotNull { it.removePrefix("ROLE_").takeIf(String::isNotBlank) }
            .mapNotNull { runCatching { RolesEnum.valueOf(it) }.getOrNull() }
            .toSet()

        val errors = mutableListOf<ApiError>()

        if (!usuarioDto.roles.all { canRegisterRole(it, authenticatedRoles) }) {
            errors += createApiErrorBuilder {
                withCode(ApiResponseCode.ROL_HIERARCHY_VIOLATION)
                withProperty("roles[]")
                withMessage("No puede asignar roles superiores a su rol actual")
            }
        }

        if (!hasUserManagementPermissions(authenticatedRoles.map { it.name }.toSet())) {
            errors += createApiErrorBuilder {
                withCode(ApiResponseCode.PERMISSION_DENIED)
                withMessage("No tienes permisos para registrar")
            }
        }

        return errors
    }

    private fun validateWarningsRegistration(usuarioDto: UsuarioDto): List<ApiError> {
        val apiErrorList = mutableListOf<ApiError>()
        if (usuarioDto.roles.any { rolDto -> rolDto.permisos.isNotEmpty() }) {
            apiErrorList += createApiErrorBuilder {
                withCode(ApiResponseCode.INFORMATION_IGNORED)
                withProperty("roles[].permisos[]")
                withMessage(
                    "Los permisos de los roles son ignorados al crear un usuario. Para crear o relacionar nuevos permisos a un rol debe utilizar otra opción",
                )
            }
        }
        if (usuarioDto.roles.any { rD -> rD.id == null && rD.nombre?.isBlank() == false }) {
            apiErrorList +=
                createApiErrorBuilder {
                    withCode(ApiResponseCode.NON_IDEMPOTENCE)
                    withProperty("roles[]")
                    withMessage("Utilice ID al realizar peticiones")
                }
        }
        return apiErrorList
    }

    private fun canRegisterRole(rolDto: RolDto, authenticatedRoles: Set<RolesEnum>): Boolean {
        val maxRolPriority = authenticatedRoles.maxBy { it.priority }.priority
        return rolDto.nombre != null && RolesEnum.valueOf(rolDto.nombre.uppercase()).priority <= maxRolPriority
    }

    private fun hasUserManagementPermissions(authenticatedAuthorities: Set<String>) =
        authenticatedAuthorities.contains("ADMINISTRATIVO_WRITE") ||
            authenticatedAuthorities.contains("AUTORIDAD_WRITE") ||
            authenticatedAuthorities.contains("USER_MANAGER_WRITE")

    private suspend fun validateRegistration(registerUserDto: RegisterUserDto): RegistrationResult {
        val errors = mutableListOf<ApiError>()
        val usuarioDto = registerUserDto.usuario

        if (isUsernameRegistered(usuarioDto.username)) {
            errors += createApiErrorBuilder {
                withCode(ApiResponseCode.ALREADY_TAKEN)
                withProperty("username")
                withMessage("El nombre de usuario ya está en uso")
            }
        }

        if (compromisedPasswordChecker.isPasswordCompromised(usuarioDto.password)) {
            errors += createApiErrorBuilder {
                withCode(ApiResponseCode.COMPROMISED_PASSWORD)
                withProperty("password")
                withMessage("La contraseña proporcionada está comprometida. Por favor use otra contraseña")
            }
        }

        return if (errors.isEmpty()) RegistrationResult.RegistrationSuccess(Any()) else RegistrationError(errors)
    }

    suspend fun isUsernameRegistered(username: String?) =
        username != null && usuarioPersistenceService.findByUsername(username) != null
}
