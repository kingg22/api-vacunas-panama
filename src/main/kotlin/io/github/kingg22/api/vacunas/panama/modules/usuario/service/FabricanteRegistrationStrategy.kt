package io.github.kingg22.api.vacunas.panama.modules.usuario.service

import io.github.kingg22.api.vacunas.panama.modules.fabricante.dto.FabricanteDto
import io.github.kingg22.api.vacunas.panama.modules.fabricante.service.FabricanteService
import io.github.kingg22.api.vacunas.panama.modules.usuario.dto.RegisterUserDto
import io.github.kingg22.api.vacunas.panama.modules.usuario.service.RegistrationResult.RegistrationError
import io.github.kingg22.api.vacunas.panama.modules.usuario.service.RegistrationResult.RegistrationSuccess
import io.github.kingg22.api.vacunas.panama.response.ActualApiResponse
import io.github.kingg22.api.vacunas.panama.response.ApiResponseCode
import io.github.kingg22.api.vacunas.panama.response.ApiResponseFactory.createApiErrorBuilder
import io.github.kingg22.api.vacunas.panama.response.ApiResponseFactory.createContentResponse
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class FabricanteRegistrationStrategy(
    private val fabricanteService: FabricanteService,
    private val usuarioService: UsuarioService,
) : RegistrationStrategy {

    override suspend fun validate(registerUserDto: RegisterUserDto): RegistrationResult {
        val licencia = registerUserDto.licenciaFabricante
            ?: return RegistrationError(
                createApiErrorBuilder {
                    withCode(ApiResponseCode.MISSING_INFORMATION)
                    withMessage("Falta licencia fabricante")
                },
            )

        return fabricanteService.getFabricante(licencia)?.let { fabricante ->
            when {
                fabricante.usuario?.disabled == true -> RegistrationError(
                    createApiErrorBuilder {
                        withCode(ApiResponseCode.PERMISSION_DENIED)
                        withMessage("No puede registrarse")
                    },
                )

                fabricante.usuario?.id != null -> RegistrationError(
                    createApiErrorBuilder {
                        withCode(ApiResponseCode.ALREADY_EXISTS)
                        withMessage("Ya tiene usuario")
                    },
                )

                else -> RegistrationSuccess(fabricante)
            }
        } ?: RegistrationError(
            createApiErrorBuilder {
                withCode(ApiResponseCode.NOT_FOUND)
                withMessage("Fabricante no encontrado")
            },
        )
    }

    override suspend fun create(registerUserDto: RegisterUserDto): ActualApiResponse {
        val resultValidate = validate(registerUserDto)
        return when (resultValidate) {
            is RegistrationError -> createContentResponse().apply {
                addErrors(resultValidate.errors)
            }

            is RegistrationSuccess -> {
                val fabricante =
                    resultValidate.outcome as? FabricanteDto
                        ?: return createContentResponse().apply {
                            addError(
                                createApiErrorBuilder {
                                    withCode(ApiResponseCode.API_UPDATE_UNSUPPORTED)
                                    withMessage("No se puede crear fabricante")
                                },
                            )
                        }

                val fabricanteDto = fabricante.copy(
                    usuario = usuarioService.createUser(
                        registerUserDto.usuario,
                        null,
                        fabricante.entidad.id,
                    ),
                )

                createContentResponse().apply {
                    addData("fabricante", fabricanteDto)
                }
            }
        }
    }
}
