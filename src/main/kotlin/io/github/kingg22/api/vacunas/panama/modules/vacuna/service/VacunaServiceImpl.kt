package io.github.kingg22.api.vacunas.panama.modules.vacuna.service

import io.github.kingg22.api.vacunas.panama.modules.doctor.service.DoctorService
import io.github.kingg22.api.vacunas.panama.modules.paciente.service.PacienteService
import io.github.kingg22.api.vacunas.panama.modules.sede.service.SedeService
import io.github.kingg22.api.vacunas.panama.modules.vacuna.domain.DosisModel
import io.github.kingg22.api.vacunas.panama.modules.vacuna.dto.InsertDosisDto
import io.github.kingg22.api.vacunas.panama.modules.vacuna.dto.getNumeroDosisAsEnum
import io.github.kingg22.api.vacunas.panama.modules.vacuna.entity.toDosisDto
import io.github.kingg22.api.vacunas.panama.modules.vacuna.entity.toListDosisDto
import io.github.kingg22.api.vacunas.panama.modules.vacuna.persistence.VacunaPersistenceService
import io.github.kingg22.api.vacunas.panama.response.ActualApiResponse
import io.github.kingg22.api.vacunas.panama.response.ApiResponseCode
import io.github.kingg22.api.vacunas.panama.response.ApiResponseFactory.createResponseBuilder
import io.github.kingg22.api.vacunas.panama.response.returnIfErrors
import io.github.kingg22.api.vacunas.panama.util.logger
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class VacunaServiceImpl(
    private val vacunaPersistenceService: VacunaPersistenceService,
    private val doctorService: DoctorService,
    private val sedeService: SedeService,
    private val pacienteService: PacienteService,
) : VacunaService {
    private val log = logger()

    override suspend fun createDosis(insertDosisDto: InsertDosisDto): ActualApiResponse {
        val contentResponse = createResponseBuilder()
        val paciente = pacienteService.getPacienteById(insertDosisDto.pacienteId)
        val vacuna = vacunaPersistenceService.findVacunaById(insertDosisDto.vacunaId)
        val sede = sedeService.getSedeById(insertDosisDto.sedeId)
        val doctor = insertDosisDto.doctorId?.let {
            doctorService.getDoctorById(it)
        }

        when {
            paciente == null -> {
                contentResponse.withError(
                    code = ApiResponseCode.NOT_FOUND,
                    message = "Paciente no encontrado",
                    property = "paciente_id",
                )
                return contentResponse.build()
            }

            vacuna == null -> {
                contentResponse.withError(
                    code = ApiResponseCode.NOT_FOUND,
                    message = "Vacuna no encontrada",
                    property = "vacuna_id",
                )
                return contentResponse.build()
            }

            sede == null -> {
                contentResponse.withError(
                    code = ApiResponseCode.NOT_FOUND,
                    message = "Sede no encontrada",
                    property = "sede_id",
                )
                return contentResponse.build()
            }

            doctor == null -> {
                contentResponse.withWarning(
                    code = ApiResponseCode.NOT_FOUND,
                    message = "Doctor no encontrado",
                    property = "doctor_id",
                )
            }
        }

        vacunaPersistenceService.findTopDosisByPacienteAndVacuna(paciente, vacuna)?.let {
            log.debug("Última dosis encontrada, ID: {}", it.id)
            val numeroDosis = it.numeroDosis.getNumeroDosisAsEnum()
            if (!numeroDosis.isValidNew(insertDosisDto.numeroDosis)) {
                contentResponse.withError(
                    ApiResponseCode.VALIDATION_FAILED,
                    "La dosis ${insertDosisDto.numeroDosis} no es válida. Último número de dosis $numeroDosis",
                    "numero_dosis",
                )
                return@let
            }
            log.debug("Nueva dosis cumple las reglas de secuencia en número de dosis")
        } ?: run { log.debug("El paciente no tiene dosis previas") }
        contentResponse.build().returnIfErrors()?.let { return it as ActualApiResponse }

        val dosis = vacunaPersistenceService.createAndSaveDosis(
            DosisModel(
                pacienteId = insertDosisDto.pacienteId,
                vacunaId = insertDosisDto.vacunaId,
                sedeId = insertDosisDto.sedeId,
                numeroDosis = insertDosisDto.numeroDosis.value,
                lote = insertDosisDto.lote,
                doctorId = insertDosisDto.doctorId,
                fechaAplicacion = insertDosisDto.fechaAplicacion,
            ),
        )
        contentResponse.withData("dosis", dosis.toDosisDto())
        return contentResponse.build()
    }

    /*
    @Cacheable(
        cacheNames = [CacheDuration.HUGE_VALUE],
        key = "'vacunas'",
        unless = "#result==null or #result.isEmpty()",
    )
    @CacheResult(cacheName = CacheDuration.HUGE_VALUE)
     */
    override suspend fun getVacunasFabricante() = vacunaPersistenceService.findAllVacunasWithFabricantes()

    override suspend fun getDosisByIdPacienteIdVacuna(idPaciente: UUID, idVacuna: UUID) =
        vacunaPersistenceService.findAllDosisByPacienteIdAndVacunaId(idPaciente, idVacuna).toListDosisDto()
}
