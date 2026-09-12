package dropnext.dss.domain


/** How many products a shop has, as Shopify counts them: up to a cap (10,000 by default), past which [count] is a lower bound. */
data class ProductCount(val count: Int, val isExact: Boolean)
