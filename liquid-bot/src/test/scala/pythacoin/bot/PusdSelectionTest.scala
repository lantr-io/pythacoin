package pythacoin.bot

import org.scalatest.funsuite.AnyFunSuite

class PusdSelectionTest extends AnyFunSuite {

    private def pick(items: Seq[(String, Long)], needed: Long): Seq[String] =
        PusdSelection.greedyByAmount[(String, Long)](items, _._2, needed).map(_._1)

    test("returns nothing when needed <= 0") {
        assert(pick(Seq("a" -> 100L, "b" -> 50L), needed = 0L).isEmpty)
        assert(pick(Seq("a" -> 100L), needed = -10L).isEmpty)
    }

    test("picks the single largest UTxO when it covers the need") {
        // a:100 covers needed=80 alone; b:50, c:30 untouched.
        assert(pick(Seq("a" -> 100L, "b" -> 50L, "c" -> 30L), needed = 80L) == Seq("a"))
    }

    test("greedy descending: picks the two largest when one isn't enough") {
        // needed=120, a:100 alone is 100 (< 120), so pick a then b → 150.
        assert(pick(Seq("a" -> 100L, "b" -> 50L, "c" -> 30L), needed = 120L) == Seq("a", "b"))
    }

    test("input order doesn't matter — sort by amount") {
        assert(pick(Seq("c" -> 30L, "b" -> 50L, "a" -> 100L), needed = 120L) == Seq("a", "b"))
    }

    test("exact match needs no extra UTxO") {
        assert(pick(Seq("a" -> 100L, "b" -> 50L), needed = 100L) == Seq("a"))
    }

    test("returns all items when total is still insufficient (caller must catch)") {
        assert(pick(Seq("a" -> 10L, "b" -> 5L), needed = 100L).toSet == Set("a", "b"))
    }

    test("ties: deterministic on input order within equal amounts") {
        // Both a and b have amount=50; sortBy is stable, so insertion order wins.
        val r = PusdSelection.greedyByAmount[(String, Long)](
          Seq("a" -> 50L, "b" -> 50L, "c" -> 50L),
          _._2,
          needed = 80L
        )
        assert(r.map(_._1) == Seq("a", "b"))
    }
}
