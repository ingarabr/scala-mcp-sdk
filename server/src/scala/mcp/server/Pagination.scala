package mcp.server

import mcp.protocol.{Cursor, ErrorData, McpError}

/** Configuration for list pagination.
  *
  * @param defaultPageSize
  *   Number of items to return per page (default: 50)
  */
case class PaginationConfig(
    defaultPageSize: Int = 50
)

object PaginationConfig {
  val Default: PaginationConfig = PaginationConfig()
}

/** Result of paginating a list.
  *
  * @param items
  *   The current page of items
  * @param nextCursor
  *   Cursor for the next page, if more items exist
  */
case class PaginatedResult[A](
    items: List[A],
    nextCursor: Option[Cursor]
)

/** Cursor utilities for pagination.
  *
  * Cursor format: `{listHash}:{startIndex}`
  *
  * The listHash ensures the cursor is invalidated if the list changes. The startIndex indicates where to resume pagination.
  */
object PaginationCursor {

  /** Encode a cursor from list hash and start index. */
  def encode(listHash: Int, startIndex: Int): Cursor =
    s"$listHash:$startIndex"

  /** Decode a cursor into (listHash, startIndex). Returns None if format is invalid. */
  def decode(cursor: Cursor): Option[(Int, Int)] =
    cursor.split(':') match {
      case Array(hashStr, indexStr) =>
        for {
          hash <- hashStr.toIntOption
          index <- indexStr.toIntOption
        } yield (hash, index)
      case _ => None
    }

  /** Compute a stable hash for a list based on item identifiers.
    *
    * @param items
    *   The list of items
    * @param getId
    *   Function to extract an identifier from each item
    */
  def listHash[A](items: List[A], getId: A => String): Int =
    items.map(getId).hashCode()
}

/** Pagination helper for list operations.
  *
  * Provides cursor-based pagination with automatic cursor validation and generation.
  */
object Paginator {

  /** Paginate a list using the given cursor and configuration.
    *
    * @param items
    *   The full list of items
    * @param cursor
    *   Optional cursor from the client request
    * @param config
    *   Pagination configuration
    * @param getId
    *   Function to extract an identifier from each item (used for list hash)
    * @return
    *   Either an error (invalid cursor) or the paginated result
    */
  def paginate[A](
      items: List[A],
      cursor: Option[Cursor],
      config: PaginationConfig,
      getId: A => String
  ): Either[ErrorData, PaginatedResult[A]] = {
    val currentHash = PaginationCursor.listHash(items, getId)

    cursor match {
      case None =>
        // First page - start from beginning
        val page = items.take(config.defaultPageSize)
        val nextCursor =
          if items.size > config.defaultPageSize
          then Some(PaginationCursor.encode(currentHash, config.defaultPageSize))
          else None
        Right(PaginatedResult(page, nextCursor))

      case Some(cursorValue) =>
        PaginationCursor.decode(cursorValue) match {
          case None =>
            Left(McpError.invalidParams("Invalid cursor format"))

          case Some((cursorHash, startIndex)) =>
            if cursorHash != currentHash then Left(McpError.invalidParams("Cursor expired: list has changed"))
            else if startIndex < 0 || startIndex > items.size then Left(McpError.invalidParams("Invalid cursor: index out of bounds"))
            else {
              val remaining = items.drop(startIndex)
              val page = remaining.take(config.defaultPageSize)
              val nextIndex = startIndex + config.defaultPageSize
              val nextCursor =
                if nextIndex < items.size
                then Some(PaginationCursor.encode(currentHash, nextIndex))
                else None
              Right(PaginatedResult(page, nextCursor))
            }
        }
    }
  }
}
