package dropnext.dss.lib.dss

sealed interface FulfillmentResult<out T> {
  data class Ok<T>(val value: T) : FulfillmentResult<T>

  sealed class Err : FulfillmentResult<Nothing> {
    data class UserError(val messages: List<String>) : Err()
    data class GraphQlError(val raw: String) : Err()
    data class NotFound(val detail: String) : Err()
    data class Network(val message: String) : Err()
  }
}

fun FulfillmentResult.Err.toMessage(): String = when (this) {
  is FulfillmentResult.Err.UserError -> messages.joinToString("; ")
  is FulfillmentResult.Err.GraphQlError -> raw
  is FulfillmentResult.Err.NotFound -> detail
  is FulfillmentResult.Err.Network -> message
}
