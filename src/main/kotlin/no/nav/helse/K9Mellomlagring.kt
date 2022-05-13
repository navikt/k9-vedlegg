package no.nav.helse

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.jackson.*
import io.ktor.metrics.micrometer.*
import io.ktor.routing.*
import io.prometheus.client.hotspot.DefaultExports
import no.nav.helse.dokument.DokumentService
import no.nav.helse.dokument.VirusScanner
import no.nav.helse.dokument.api.ContentTypeService
import no.nav.helse.dokument.api.customIdV1Apis
import no.nav.helse.dokument.api.dokumentV1Apis
import no.nav.helse.dokument.crypto.Cryptography
import no.nav.helse.dokument.eier.EierResolver
import no.nav.helse.dokument.storage.Storage
import no.nav.helse.dusseldorf.ktor.auth.AuthStatusPages
import no.nav.helse.dusseldorf.ktor.auth.allIssuers
import no.nav.helse.dusseldorf.ktor.auth.idToken
import no.nav.helse.dusseldorf.ktor.auth.multipleJwtIssuers
import no.nav.helse.dusseldorf.ktor.core.*
import no.nav.helse.dusseldorf.ktor.health.*
import no.nav.helse.dusseldorf.ktor.jackson.JacksonStatusPages
import no.nav.helse.dusseldorf.ktor.jackson.dusseldorfConfigured
import no.nav.helse.dusseldorf.ktor.metrics.MetricsRoute
import no.nav.helse.dusseldorf.ktor.metrics.init
import no.nav.security.token.support.ktor.RequiredClaims
import no.nav.security.token.support.ktor.asIssuerProps
import no.nav.security.token.support.ktor.tokenValidationSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.k9Mellomlagring() {
    val appId = environment.config.id()
    logProxyProperties()
    DefaultExports.initialize()
    val logger = LoggerFactory.getLogger("no.nav.k9.k9Mellomlagring")


    val config = environment.config
    val allIssuers = config.asIssuerProps().keys
    val configuration = Configuration(config)

    install(Authentication) {
        allIssuers
            .filterNot { it == "azure" }
            .forEach { issuer: String ->
                tokenValidationSupport(
                    name = issuer,
                    config = config,
                    requiredClaims = RequiredClaims(
                        issuer = issuer,
                        claimMap = arrayOf("acr=Level4")
                    )
                )
            }

        allIssuers
            .filter { it == "azure" }
            .forEach { issuer: String ->
                tokenValidationSupport(
                    name = issuer,
                    config = config,
                    requiredClaims = RequiredClaims(
                        issuer = issuer,
                        claimMap = arrayOf("role=access_as_application")
                    )
                )
            }
    }

    install(ContentNegotiation) {
        jackson {
            k9MellomlagringConfigured()
        }
    }

    install(StatusPages) {
        DefaultStatusPages()
        JacksonStatusPages()
        AuthStatusPages()
    }

    val storage = configuration.getStorageConfigured()

    install(CallIdRequired)

    val dokumentService = DokumentService(
        cryptography = Cryptography(
            encryptionPassphrase = configuration.getEncryptionPassphrase(),
            decryptionPassphrases = configuration.getDecryptionPassphrases()
        ),
        storage = storage,
        virusScanner = getVirusScanner(configuration)
    )

    val eierResolver = EierResolver()

    val contentTypeService = ContentTypeService()

    install(Routing) {
        authenticate(*allIssuers.toTypedArray()) {
            requiresCallId {
                dokumentV1Apis(
                    dokumentService = dokumentService,
                    eierResolver = eierResolver,
                    contentTypeService = contentTypeService,
                    baseUrl = configuration.getBaseUrl()
                )
                customIdV1Apis(
                    dokumentService = dokumentService,
                    eierResolver = eierResolver,
                    contentTypeService = contentTypeService,
                    støtterExpirationFraRequest = configuration.støtterExpirationFraRequest()
                )
            }
        }

        DefaultProbeRoutes()
        MetricsRoute()
        HealthRoute(
            healthService = HealthService(
                setOf(
                    StorageHealthCheck(
                        storage = storage
                    )
                )
            )
        )
    }

    install(MicrometerMetrics) {
        init(appId)
    }

    install(CallId) {
        fromXCorrelationIdHeader()
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        call.request.log()
    }

    install(CallLogging) {
        correlationIdAndRequestIdInMdc()
        logRequests()
        mdc("id_token_jti") { call ->
            try {
                val idToken = call.idToken()
                logger.info("Issuer [{}]", idToken.issuer())
                idToken.getId()
            } catch (cause: Throwable) {
                null
            }
        }
    }
}

private fun getVirusScanner(config: Configuration): VirusScanner? {
    if (!config.enableVirusScan()) return null
    return VirusScanner(url = config.getVirusScanUrl())
}

internal fun ObjectMapper.k9MellomlagringConfigured() = dusseldorfConfigured()
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

class StorageHealthCheck(
    private val storage: Storage
) : HealthCheck {

    private companion object {
        private val logger: Logger = LoggerFactory.getLogger(StorageHealthCheck::class.java)
        private val name = "StorageHealthCheck"
    }

    override suspend fun check(): Result {
        return try {
            storage.ready()
            Healthy(name = name, result = "Tilkobling mot storage OK.")
        } catch (cause: Throwable) {
            logger.error("Feil ved tilkobling mot storage.", cause)
            UnHealthy(name = name, result = cause.message ?: "Feil ved tilkobling mot storage.")
        }
    }
}
