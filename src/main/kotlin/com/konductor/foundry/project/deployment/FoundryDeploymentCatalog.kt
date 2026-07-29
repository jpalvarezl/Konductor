package com.konductor.foundry.project.deployment

import com.azure.ai.projects.DeploymentsClient
import com.azure.ai.projects.models.Deployment
import com.azure.ai.projects.models.ModelDeployment

/**
 * Application-facing discovery seam for deployments in the configured Foundry project.
 *
 * Implementations return one entry per exact deployment name, ordered lexicographically by name. If the service
 * repeats a name across pages, the first response wins. Fakes can implement this interface without depending on the
 * Azure SDK.
 */
/** Stable discriminator used by Foundry Projects for model deployment DTOs. */
const val FOUNDRY_MODEL_DEPLOYMENT_TYPE: String = "ModelDeployment"

interface FoundryDeploymentCatalog {
    fun listDeployments(): List<FoundryDeployment>

    fun getDeployment(name: String): FoundryDeployment
}

/** Deployment metadata safe to expose outside the Azure SDK adapter. */
data class FoundryDeployment(
    val name: String,
    val type: String,
    val modelName: String? = null,
    val modelVersion: String? = null,
    val modelPublisher: String? = null,
    val capabilities: Map<String, String> = emptyMap(),
    val sku: FoundryDeploymentSku? = null,
    val connectionName: String? = null,
)

/** SKU metadata for a Foundry model deployment. */
data class FoundryDeploymentSku(
    val capacity: Long,
    val family: String?,
    val name: String?,
    val size: String?,
    val tier: String?,
)

/**
 * Production adapter over Azure AI Projects 2.2.0.
 *
 * The adapter accepts the real SDK [DeploymentsClient] directly and maps SDK [Deployment]/[ModelDeployment] values
 * into application-owned DTOs. Client construction, credentials, and ownership remain with the composition layer.
 */
class AzureFoundryDeploymentCatalog(
    private val deploymentsClient: DeploymentsClient,
) : FoundryDeploymentCatalog {
    override fun listDeployments(): List<FoundryDeployment> =
        canonicalizeDeployments(deploymentsClient.listDeployments().map(::toFoundryDeployment))

    override fun getDeployment(name: String): FoundryDeployment =
        toFoundryDeployment(deploymentsClient.getDeployment(name))

    private fun toFoundryDeployment(deployment: Deployment): FoundryDeployment {
        val name = requireNotNull(deployment.name) { "Foundry returned a deployment without a name." }
        val type = requireNotNull(deployment.type) { "Foundry deployment '$name' has no type." }.toString()
        val model = deployment as? ModelDeployment
        val sku = model?.sku

        return FoundryDeployment(
            name = name,
            type = type,
            modelName = model?.modelName,
            modelVersion = model?.modelVersion,
            modelPublisher = model?.modelPublisher,
            capabilities = model?.capabilities.orEmpty().toSortedMap(),
            sku = sku?.let {
                FoundryDeploymentSku(
                    capacity = it.capacity,
                    family = it.family,
                    name = it.name,
                    size = it.size,
                    tier = it.tier,
                )
            },
            connectionName = model?.connectionName,
        )
    }
}

/** Applies the application-owned ordering and duplicate-name policy after SDK models have been mapped. */
internal fun canonicalizeDeployments(deployments: Iterable<FoundryDeployment>): List<FoundryDeployment> =
    deployments
        .distinctBy(FoundryDeployment::name)
        .sortedBy(FoundryDeployment::name)
