package dropnext.dss.workflow

import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.parseCreatedFulfillmentId
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Applies [mutations] to Shopify. Empty [EffectShopifyMutationsResult.errors] means success.
 */
suspend fun effectShopifyMutations(
  shopifyGqlService: ShopifyGraphqlService,
  mutations: List<ShopifyMutation>,
): EffectShopifyMutationsResult {
  val newFulfillmentIds = mutableListOf<Long>()
  mutations.forEach { mutation ->
    when (mutation) {
      is ShopifyMutation.FulfillmentCancel -> {
        val error = cancelShopifyFulfillment(shopifyGqlService, mutation.fulfillmentId)
        if (error != null) {
          return EffectShopifyMutationsResult(newFulfillmentIds = newFulfillmentIds, errors = listOf(error))
        }
      }
      is ShopifyMutation.FulfillmentCreate -> {
        when (val created = createShopifyFulfillment(shopifyGqlService, mutation)) {
          is CreateShopifyFulfillmentResult.Err ->
            return EffectShopifyMutationsResult(
              newFulfillmentIds = newFulfillmentIds,
              errors = listOf(created.error),
            )
          is CreateShopifyFulfillmentResult.Ok ->
            newFulfillmentIds.addAll(created.fulfillmentIds)
        }
      }
    }
  }
  return EffectShopifyMutationsResult(newFulfillmentIds = newFulfillmentIds, errors = emptyList())
}

private suspend fun cancelShopifyFulfillment(
  shopifyGqlService: ShopifyGraphqlService,
  fulfillmentId: String,
): ShopifyError? {
  val response = try {
    shopifyGqlService.cancelFulfillment(fulfillmentId)
  } catch (e: Exception) {
    return ShopifyError.Network(e.message ?: "network error")
  }
  val userErrors = response.data?.fulfillmentCancel?.userErrors.orEmpty()
  // "already canceled" errors are no-ops; everything else is a real failure.
  val realErrors = userErrors.filter { !it.message.contains("already", ignoreCase = true) }
  if (realErrors.isNotEmpty()) {
    return ShopifyError.UserError(realErrors.map { it.message })
  }
  val graphqlErrors = response.errors
  if (!graphqlErrors.isNullOrEmpty()) {
    return ShopifyError.GraphqlError(graphqlErrors.joinToString("; ") { it.message })
  }
  return null
}

private sealed interface CreateShopifyFulfillmentResult {
  data class Ok(val fulfillmentIds: List<Long>) : CreateShopifyFulfillmentResult
  data class Err(val error: ShopifyError) : CreateShopifyFulfillmentResult
}

private suspend fun createShopifyFulfillment(
  shopifyGqlService: ShopifyGraphqlService,
  create: ShopifyMutation.FulfillmentCreate,
): CreateShopifyFulfillmentResult {
  val lineItemsByFulfillmentOrder =
    create.lineItems
      .groupBy { it.fulfillmentOrderId }
      .map { (fulfillmentOrderId, lineItems) ->
        FulfillmentOrderLineItemsInput(
          fulfillmentOrderId = fulfillmentOrderId,
          fulfillmentOrderLineItems = lineItems.map { lineItem ->
            FulfillmentOrderLineItemInput(id = lineItem.lineItemId, quantity = lineItem.quantity)
          },
        )
      }

  val tracking = FulfillmentTrackingInput(
    company = create.carrier,
    number = create.trackingNumber,
    url = create.trackingUrl,
  )

  val response = try {
    shopifyGqlService.createFulfillmentWithLineItems(
      lineItemsByFulfillmentOrder = lineItemsByFulfillmentOrder,
      tracking = tracking,
      notifyCustomer = create.notifyCustomer,
    )
  } catch (e: Exception) {
    return CreateShopifyFulfillmentResult.Err(ShopifyError.Network(e.message ?: "network error"))
  }

  val createErrors = response.data?.fulfillmentCreate?.userErrors.orEmpty()
  if (createErrors.isNotEmpty()) {
    return CreateShopifyFulfillmentResult.Err(ShopifyError.UserError(createErrors.map { it.message }))
  }
  val graphqlErrors = response.errors
  if (!graphqlErrors.isNullOrEmpty()) {
    return CreateShopifyFulfillmentResult.Err(
      ShopifyError.GraphqlError(graphqlErrors.joinToString("; ") { it.message }),
    )
  }

  val fulfillment = response.data?.fulfillmentCreate?.fulfillment
    ?: return CreateShopifyFulfillmentResult.Err(
      ShopifyError.UserError(listOf("fulfillment missing in response")),
    )

  val fulfillmentId = parseCreatedFulfillmentId(fulfillment)
  if (fulfillmentId == null) {
    log.warn {
      "sync-shipments could not resolve fulfillment id from response gid=${fulfillment.id} " +
        "tracking=${create.trackingNumber}"
    }
  }
  return CreateShopifyFulfillmentResult.Ok(listOfNotNull(fulfillmentId))
}
