package dropnext.dss.lib.shopify.token


/**
 * What looking up something that hangs off a shop's Admin token came to. [Unavailable] is kept apart from [Missing]
 * because the two ask for opposite answers: a shop without a token does not get one by asking again, while a token
 * source that did not answer (the monolith down or redeploying) may well answer the next time.
 */
sealed interface ShopLookup<out T> {
  data class Found<out T>(val value: T) : ShopLookup<T>

  data object Missing : ShopLookup<Nothing>

  /** The source of truth could not be asked; a caller must not read this as "the shop has no token". */
  data object Unavailable : ShopLookup<Nothing>
}

inline fun <T, R> ShopLookup<T>.map(transform: (T) -> R): ShopLookup<R> = when (this) {
  is ShopLookup.Found -> ShopLookup.Found(transform(value))
  ShopLookup.Missing -> ShopLookup.Missing
  ShopLookup.Unavailable -> ShopLookup.Unavailable
}
