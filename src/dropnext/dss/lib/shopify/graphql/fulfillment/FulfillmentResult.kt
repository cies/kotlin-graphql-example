package dropnext.dss.lib.shopify.graphql.fulfillment

sealed interface FulfillmentResult<out T> {
  data class Ok<T>(val value: T) : FulfillmentResult<T>

  sealed class Err : FulfillmentResult<Nothing> {
    data class UserError(val messages: List<String>) : Err()
    data class GraphqlError(val raw: String) : Err()
    data class NotFound(val detail: String) : Err()
    data class Network(val message: String) : Err()
  }
}
