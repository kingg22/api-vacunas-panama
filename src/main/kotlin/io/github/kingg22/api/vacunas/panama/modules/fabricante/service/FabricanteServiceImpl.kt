package io.github.kingg22.api.vacunas.panama.modules.fabricante.service

import io.github.kingg22.api.vacunas.panama.modules.fabricante.entity.toFabricanteDto
import io.github.kingg22.api.vacunas.panama.modules.fabricante.persistence.FabricantePersistenceService
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class FabricanteServiceImpl(private val fabricantePersistenceService: FabricantePersistenceService) :
    FabricanteService {
    override suspend fun getFabricante(licencia: String) =
        fabricantePersistenceService.findByLicencia(licencia)?.toFabricanteDto()

    override suspend fun getFabricanteByUserID(idUser: UUID) =
        fabricantePersistenceService.findByUsuarioId(idUser)?.toFabricanteDto()
}
