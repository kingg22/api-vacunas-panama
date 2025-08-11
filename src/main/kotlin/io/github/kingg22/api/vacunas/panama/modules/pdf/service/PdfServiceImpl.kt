package io.github.kingg22.api.vacunas.panama.modules.pdf.service

import com.itextpdf.html2pdf.HtmlConverter
import io.github.kingg22.api.vacunas.panama.modules.paciente.dto.PacienteDto
import io.github.kingg22.api.vacunas.panama.modules.pdf.dto.PdfDto
import io.github.kingg22.api.vacunas.panama.modules.vacuna.dto.DosisDto
import io.github.kingg22.api.vacunas.panama.util.logger
import jakarta.enterprise.context.ApplicationScoped
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

@ApplicationScoped
class PdfServiceImpl : PdfService {
    private val log = logger()
    private val iconImageBase64: String by lazy {
        val resource = Thread.currentThread().contextClassLoader.getResourceAsStream("images/icon.png")
        requireNotNull(resource) { "No se encontró el recurso icon.png en el classpath." }

        return@lazy resource.use { input ->
            Base64.getEncoder().encodeToString(input.readBytes())
        }
    }

    /*
    @Cacheable(
        cacheNames = [CacheDuration.MASSIVE_VALUE],
        key = "'certificate:'.concat(#idCertificate)",
        unless = "#result == null or #result.length == 0",
    )
    @CacheResult(cacheName = CacheDuration.MASSIVE_VALUE)
     */
    override suspend fun generatePdf(
        pacienteDto: PacienteDto,
        dosisDtos: List<DosisDto>,
        idCertificate: UUID,
    ): ByteArray = this.generatePdf(idCertificate, this.generatePdfDto(pacienteDto, dosisDtos))

    /*
    @Cacheable(
        cacheNames = [CacheDuration.MASSIVE_VALUE],
        key = "'certificate64:'.concat(#idCertificate)",
        unless = "#result == null or #result.length == 0",
    )
    @CacheResult(cacheName = CacheDuration.MASSIVE_VALUE)
     */
    override suspend fun generatePdfBase64(
        pacienteDto: PacienteDto,
        dosisDtos: List<DosisDto>,
        idCertificate: UUID,
    ): String =
        Base64.getEncoder().encodeToString(this.generatePdf(idCertificate, this.generatePdfDto(pacienteDto, dosisDtos)))

    /**
     * Generador de certificados PDF basados en una plantilla
     *
     * @param idCertificado Para colocar en caché el certificado
     * @param pdfDto DTO para añadir información al HTML
     * @return byte[] con el PDF
     */
    private fun generatePdf(idCertificado: UUID, pdfDto: PdfDto): ByteArray {
        log.debug("Generando PDF con ID: {}", idCertificado)
        val template = generateHtmlTemplate(idCertificado, pdfDto)
        val outputStream = ByteArrayOutputStream()
        HtmlConverter.convertToPdf(template, outputStream)
        return outputStream.toByteArray()
    }

    private fun generatePdfDto(pacienteDto: PacienteDto, dosisDtos: List<DosisDto>): PdfDto {
        val identificacion = obtenerIdentificacion(pacienteDto)
        val personaDto = pacienteDto.persona
        val nombres = "${personaDto.nombre ?: ""} ${personaDto.nombre2 ?: ""}".trim()
        val apellidos = "${personaDto.apellido1 ?: ""} ${personaDto.apellido2 ?: ""}".trim()

        log.debug("Received a request to generate PDF with Paciente DTOs")
        log.debug("Dosis a agregar: {}", dosisDtos)
        log.debug("Paciente ID: {}", personaDto.id)
        log.debug("Identificación a colocar: {}", identificacion)

        val pdfDto = PdfDto(
            nombres,
            apellidos,
            identificacion,
            personaDto.fechaNacimiento?.toLocalDate(),
            personaDto.id,
            dosisDtos,
        )

        if (log.isDebugEnabled) {
            log.debug(pdfDto.toString())
        }
        return pdfDto
    }

    /** Obtiene la identificación del paciente en el orden de prioridad correcto.  */
    private fun obtenerIdentificacion(pacienteDto: PacienteDto) = pacienteDto.persona.let { persona ->
        persona.cedula.takeIf { !it.isNullOrBlank() } ?: persona.pasaporte.takeIf { !it.isNullOrBlank() }
            ?: pacienteDto.identificacionTemporal.takeIf { !it.isNullOrBlank() } ?: (
            persona.id?.toString() ?: run {
                val fakeId = "INVALID-${UUID.randomUUID()}"
                log.error("ID de la persona es null. Generando identificador temporal: $fakeId")
                fakeId
            }
            )
    }

    private fun generateHtmlTemplate(certificateId: UUID, pdfDto: PdfDto): String {
        val base64Image = "data:image/png;base64,$iconImageBase64"
        val templateWithValues = template.replace("{{base64Image}}", base64Image).replace("{{nombres}}", pdfDto.nombres)
            .replace("{{apellidos}}", pdfDto.apellidos).replace("{{identificacion}}", pdfDto.identificacion)
            .replace("{{certificate_id}}", certificateId.toString())
            .replace("{{fecha_nacimiento}}", pdfDto.fechaNacimiento?.toString() ?: "N/A")

        // Se agrega de forma dinámica todas las dosis encontradas a la tabla HTML
        val dosisRows = StringBuilder()
        pdfDto.dosis.forEach { dosisDto ->
            val fabricantes = dosisDto.vacuna.fabricantes.joinToString {
                it.entidad.nombre ?: ""
            }.ifBlank {
                "N/A"
            }.trim()

            dosisRows.append(
                "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>".format(
                    dosisDto.numeroDosis,
                    dosisDto.vacuna.nombre,
                    fabricantes,
                    dosisDto.fechaAplicacion,
                    dosisDto.sede.entidad.nombre,
                ),
            )
        }

        return templateWithValues.replace("{{dosis}}", dosisRows.toString())
    }

    companion object {
        private val template = """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                    <title>Certificado Vacunas Panama</title>
                    <meta charset="UTF-8">
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                        }

                        .title {
                            font-size: 14px;
                            font-weight: bold;
                        }

                        .subtitle {
                            font-size: 12px;
                            font-weight: bold;
                        }

                        .section {
                            margin-bottom: 10px;
                        }

                        .vaccination-details {
                            font-size: 12px;
                        }

                        table {
                            width: 100%;
                            border-collapse: collapse;
                            margin-top: 10px;
                        }

                        table, th, td {
                            border: 1px solid black;
                        }

                        th, td {
                            padding: 8px;
                            text-align: left;
                        }

                        .logo {
                            position: absolute;
                            top: 20px;
                            right: 20px;
                            width: 150px;
                        }
                    </style>
                </head>
                <body>
                <div class="section">
                    <div class="title">PANAMA DIGITAL VACCINES CERTIFICATE</div>
                    <div class="title">CERTIFICADO DIGITAL DE VACUNAS PANAMA</div>
                </div>

                <img src="{{base64Image}}" alt="Logo" class="logo">

                <div class="section">
                    <br>
                    <div class="subtitle">Apellido(s), Nombre(s):</div>
                    <div class="subtitle">Last Names(s), First Name:</div>
                    <span>{{apellidos}}, {{nombres}}</span><br/>
                    <br>
                    <div class="subtitle">Identificacion (Cédula/Pasaporte/ID temporal/ID):</div>
                    <div class="subtitle">Identification (Panamanian ID card/Passport/Temporary ID/ID):</div>
                    <span>{{identificacion}}</span><br/>
                    <br>
                    <div class="subtitle">Fecha de nacimiento:</div>
                    <div class="subtitle">Date of Birth:</div>
                    <span>{{fecha_nacimiento}}</span>
                    <br><br>
                    <div class="subtitle">ID certificado:</div>
                    <div class="subtitle">Certificate ID:</div>
                    <span>{{certificate_id}}</span>
                </div>
                <br>
                <div class="section">
                    <div class="subtitle">Datos de la vacunación / Vaccination details</div>
                    <table>
                        <thead>
                        <tr>
                            <th>Número de dosis</th>
                            <th>Nombre de vacuna</th>
                            <th>Fabricante</th>
                            <th>Fecha de vacunación</th>
                            <th>Sede de vacunación</th>
                        </tr>
                        </thead>
                        <tbody>
                        {{dosis}}
                        </tbody>
                    </table>
                </div>
                </body>
                </html>
        """.trimIndent()
    }
}
