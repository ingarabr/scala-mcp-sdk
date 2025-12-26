package mcp.server

import mcp.protocol.{Constants, ErrorData}
import munit.FunSuite

class PaginationSuite extends FunSuite {

  case class Item(id: String, value: Int)

  val testItems: List[Item] = (1 to 10).map(i => Item(s"item-$i", i)).toList

  test("paginate returns first page when no cursor provided") {
    val config = PaginationConfig(defaultPageSize = 3)
    val result = Paginator.paginate(testItems, None, config, _.id)

    assert(result.isRight)
    val paginated = result.toOption.get
    assertEquals(paginated.items.map(_.id), List("item-1", "item-2", "item-3"))
    assert(paginated.nextCursor.isDefined)
  }

  test("paginate returns all items when list is smaller than page size") {
    val config = PaginationConfig(defaultPageSize = 50)
    val result = Paginator.paginate(testItems, None, config, _.id)

    assert(result.isRight)
    val paginated = result.toOption.get
    assertEquals(paginated.items.size, 10)
    assertEquals(paginated.nextCursor, None)
  }

  test("paginate continues from cursor position") {
    val config = PaginationConfig(defaultPageSize = 3)

    // Get first page
    val firstResult = Paginator.paginate(testItems, None, config, _.id)
    assert(firstResult.isRight)
    val firstPage = firstResult.toOption.get
    val cursor = firstPage.nextCursor

    // Get second page using cursor
    val secondResult = Paginator.paginate(testItems, cursor, config, _.id)
    assert(secondResult.isRight)
    val secondPage = secondResult.toOption.get
    assertEquals(secondPage.items.map(_.id), List("item-4", "item-5", "item-6"))
    assert(secondPage.nextCursor.isDefined)
  }

  test("paginate returns no nextCursor on last page") {
    val config = PaginationConfig(defaultPageSize = 3)

    // Manually create cursor to start at index 9 (last item)
    val listHash = PaginationCursor.listHash(testItems, _.id)
    val cursor = Some(PaginationCursor.encode(listHash, 9))

    val result = Paginator.paginate(testItems, cursor, config, _.id)
    assert(result.isRight)
    val paginated = result.toOption.get
    assertEquals(paginated.items.map(_.id), List("item-10"))
    assertEquals(paginated.nextCursor, None)
  }

  test("paginate returns error for invalid cursor format") {
    val config = PaginationConfig(defaultPageSize = 3)
    val result = Paginator.paginate(testItems, Some("invalid-cursor"), config, _.id)

    assert(result.isLeft)
    val error = result.swap.toOption.get
    assertEquals(error.code, Constants.INVALID_PARAMS)
    assert(error.message.contains("Invalid cursor format"))
  }

  test("paginate returns error for expired cursor (list changed)") {
    val config = PaginationConfig(defaultPageSize = 3)

    // Create cursor with wrong hash
    val cursor = Some(PaginationCursor.encode(12345, 3))

    val result = Paginator.paginate(testItems, cursor, config, _.id)
    assert(result.isLeft)
    val error = result.swap.toOption.get
    assertEquals(error.code, Constants.INVALID_PARAMS)
    assert(error.message.contains("Cursor expired"))
  }

  test("paginate returns error for out of bounds cursor") {
    val config = PaginationConfig(defaultPageSize = 3)
    val listHash = PaginationCursor.listHash(testItems, _.id)
    val cursor = Some(PaginationCursor.encode(listHash, 100))

    val result = Paginator.paginate(testItems, cursor, config, _.id)
    assert(result.isLeft)
    val error = result.swap.toOption.get
    assertEquals(error.code, Constants.INVALID_PARAMS)
    assert(error.message.contains("out of bounds"))
  }

  test("paginate handles empty list") {
    val config = PaginationConfig(defaultPageSize = 3)
    val result = Paginator.paginate(List.empty[Item], None, config, _.id)

    assert(result.isRight)
    val paginated = result.toOption.get
    assertEquals(paginated.items, List.empty)
    assertEquals(paginated.nextCursor, None)
  }

  test("PaginationCursor encode/decode roundtrip") {
    val hash = 12345
    val index = 42
    val encoded = PaginationCursor.encode(hash, index)
    val decoded = PaginationCursor.decode(encoded)

    assertEquals(decoded, Some((hash, index)))
  }

  test("PaginationCursor decode returns None for malformed cursor") {
    assertEquals(PaginationCursor.decode("not-a-cursor"), None)
    assertEquals(PaginationCursor.decode("abc:def"), None)
    assertEquals(PaginationCursor.decode("123"), None)
    assertEquals(PaginationCursor.decode(""), None)
  }

  test("PaginationCursor listHash is stable for same items") {
    val hash1 = PaginationCursor.listHash(testItems, _.id)
    val hash2 = PaginationCursor.listHash(testItems, _.id)
    assertEquals(hash1, hash2)
  }

  test("PaginationCursor listHash changes when list changes") {
    val hash1 = PaginationCursor.listHash(testItems, _.id)
    val modifiedItems = testItems :+ Item("item-11", 11)
    val hash2 = PaginationCursor.listHash(modifiedItems, _.id)
    assertNotEquals(hash1, hash2)
  }

  test("full pagination flow through all pages") {
    val config = PaginationConfig(defaultPageSize = 3)

    // Collect all pages using recursion
    def collectPages(cursor: Option[String], acc: List[Item], pageCount: Int): (List[Item], Int) =
      Paginator.paginate(testItems, cursor, config, _.id) match {
        case Left(_)          => fail(s"Pagination failed on page $pageCount")
        case Right(paginated) =>
          val newAcc = acc ++ paginated.items
          paginated.nextCursor match {
            case Some(next) => collectPages(Some(next), newAcc, pageCount + 1)
            case None       => (newAcc, pageCount + 1)
          }
      }

    val (allItems, pageCount) = collectPages(None, List.empty, 0)

    assertEquals(pageCount, 4) // 10 items / 3 per page = 4 pages
    assertEquals(allItems.map(_.id), testItems.map(_.id))
  }
}
