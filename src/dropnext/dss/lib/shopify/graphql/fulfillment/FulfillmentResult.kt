package dropnext.dss.lib.shopify.graphql.fulfillment

sealed interface FulfillmentResult<out T> {
  data class Ok<T>(val value: T) : FulfillmentResult<T>

  sealed class Err : FulfillmentResult<Nothing> {
    data class UserError(val messages: List<String>) : Err()
    data class GraphqlError(val raw: String) : Err()
    data class NotFound(val detail: String) : Err()
    data class Network(val message: String) : Err()
    data object MissingToken : Err()
  }
}

fun FulfillmentResult.Err.toMessage(): String = when (this) {
  is FulfillmentResult.Err.UserError -> messages.joinToString("; ")
  is FulfillmentResult.Err.GraphqlError -> raw
  is FulfillmentResult.Err.NotFound -> detail
  is FulfillmentResult.Err.Network -> message
  FulfillmentResult.Err.MissingToken -> "missing Shopify Admin token for shop"
}
