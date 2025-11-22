package io.github.kingg22.api.vacunas.panama

import io.github.kingg22.api.vacunas.panama.util.logger
import io.quarkus.liquibase.common.runtime.NativeImageResourceAccessor
import io.quarkus.runtime.ImageMode
import io.quarkus.runtime.Startup
import io.quarkus.runtime.util.StringUtil
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseConnection
import liquibase.database.DatabaseFactory
import liquibase.resource.ClassLoaderResourceAccessor
import liquibase.resource.CompositeResourceAccessor
import liquibase.resource.DirectoryResourceAccessor
import liquibase.resource.ResourceAccessor
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.FileNotFoundException
import java.nio.file.Paths
import java.time.ZoneOffset.UTC
import java.util.Optional
import java.util.TimeZone
import kotlin.jvm.optionals.getOrElse
import kotlin.jvm.optionals.getOrNull

/* Copy of https://github.com/quarkusio/quarkus/issues/14682#issuecomment-1205682175 */
@ApplicationScoped
@Startup
class LiquibaseSetup {
    companion object {
        private const val FILE_SYSTEM_PREFIX = "filesystem:"
    }
    private val logger = logger()

    @ConfigProperty(name = "quarkus.liquibase.enabled")
    private lateinit var active: Optional<Boolean>

    @ConfigProperty(name = "quarkus.liquibase.contexts")
    private lateinit var contexts: Optional<List<String>>

    @ConfigProperty(name = "quarkus.liquibase.labels")
    private lateinit var labels: Optional<List<String>>

    @ConfigProperty(name = "quarkus.datasource.reactive.url")
    private lateinit var datasourceUrl: List<String>

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    private lateinit var datasourceUrlJdbc: Optional<String>

    @ConfigProperty(name = "quarkus.datasource.username")
    private lateinit var datasourceUsername: String

    @ConfigProperty(name = "quarkus.datasource.password")
    private lateinit var datasourcePassword: String

    @ConfigProperty(name = "quarkus.liquibase.change-log")
    private lateinit var changeLogLocation: String

    @ConfigProperty(name = "quarkus.liquibase.search-path")
    private lateinit var searchPath: Optional<List<String>>

    @PostConstruct
    fun init() {
        TimeZone.setDefault(TimeZone.getTimeZone(UTC))
        if (active.getOrElse { false }) {
            runLiquibaseMigration()
        }
    }

    private fun runLiquibaseMigration() {
        var liquibase: Liquibase? = null
        try {
            logger.info("Starting Liquibase migration")
            val contexts = createContexts()
            val labels = createLabels()
            logger.info("liquibase setup to $datasourceUrl")
            logger.info("Liquibase with contexts [$contexts] and labels [$labels]")

            val uniqueDatasource = when {
                datasourceUrl.size > 1 -> datasourceUrl.first { url ->
                    url.contains("vertx-reactive") || url.contains("\\d*".toRegex())
                }

                datasourceUrl.size == 1 -> datasourceUrl.first()

                datasourceUrlJdbc.isPresent -> datasourceUrlJdbc.get()

                else -> error(
                    "Datasource url must not be empty for Liquibase to run. Found URL: $datasourceUrl and JDBC: '${datasourceUrlJdbc.getOrNull()}'",
                )
            }.replaceFirst("vertx-reactive", "jdbc")

            val fixedUrl = if (!uniqueDatasource.startsWith("jdbc:")) {
                logger.warn("Fixed datasource url to 'jdbc:$uniqueDatasource'")
                "jdbc:$uniqueDatasource"
            } else {
                uniqueDatasource
            }
            logger.info("Using datasource url: '$fixedUrl'")

            resolveResourceAccessor().use { resourceAccessor ->
                val conn: DatabaseConnection = DatabaseFactory.getInstance().openConnection(
                    fixedUrl,
                    datasourceUsername,
                    datasourcePassword,
                    null,
                    resourceAccessor,
                )
                liquibase = Liquibase(parseChangeLog(changeLogLocation), resourceAccessor, conn)
                liquibase.update(contexts, labels)
                logger.info("Liquibase migration finished successfully")
            }
        } catch (e: Exception) {
            logger.error("Liquibase Migration Exception: ", e)
        } finally {
            liquibase?.close()
        }
    }

    /** Copy of [io.quarkus.liquibase.LiquibaseFactory] */
    @Throws(FileNotFoundException::class)
    private fun resolveResourceAccessor(): ResourceAccessor {
        val rootAccessor = CompositeResourceAccessor()
        return if (ImageMode.current().isNativeImage) {
            nativeImageResourceAccessor(rootAccessor)
        } else {
            defaultResourceAccessor(rootAccessor)
        }
    }

    @Throws(FileNotFoundException::class)
    private fun defaultResourceAccessor(rootAccessor: CompositeResourceAccessor): ResourceAccessor {
        rootAccessor.addResourceAccessor(
            ClassLoaderResourceAccessor(Thread.currentThread().getContextClassLoader()),
        )

        if (!changeLogLocation.startsWith(FILE_SYSTEM_PREFIX) && searchPath.isEmpty) {
            return rootAccessor
        }

        if (searchPath.isEmpty) {
            return rootAccessor.addResourceAccessor(
                DirectoryResourceAccessor(
                    Paths.get(
                        StringUtil
                            .changePrefix(changeLogLocation, FILE_SYSTEM_PREFIX, ""),
                    ).parent,
                ),
            )
        }

        for (searchPath in searchPath.get()) {
            rootAccessor.addResourceAccessor(DirectoryResourceAccessor(Paths.get(searchPath)))
        }
        return rootAccessor
    }

    private fun nativeImageResourceAccessor(rootAccessor: CompositeResourceAccessor): ResourceAccessor =
        rootAccessor.addResourceAccessor(NativeImageResourceAccessor())

    private fun parseChangeLog(changeLog: String): String {
        if (changeLog.startsWith(FILE_SYSTEM_PREFIX) && searchPath.isEmpty) {
            return Paths.get(StringUtil.changePrefix(changeLog, FILE_SYSTEM_PREFIX, ""))
                .fileName.toString()
        }

        if (changeLog.startsWith(FILE_SYSTEM_PREFIX)) {
            return StringUtil.changePrefix(changeLog, FILE_SYSTEM_PREFIX, "")
        }

        if (changeLog.startsWith("classpath:")) {
            return StringUtil.changePrefix(changeLog, "classpath:", "")
        }

        return changeLog
    }

    private fun createContexts() = Contexts(contexts.getOrElse { emptyList() })

    private fun createLabels() = LabelExpression(labels.getOrElse { emptyList() })
}
