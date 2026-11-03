package goban

import chess.Color

/** A built-in Monte-Carlo Go engine, used where lichess would ask Stockfish
  * (through fishnet) for a move. Levels 1 to 8 trade playouts for strength.
  *
  * The search is a flat UCB1 bandit over candidate moves at the root, each
  * evaluated by light random playouts that never fill their own eyes. It is
  * not a strong engine, but it plays sensible shape-free Go on 9×9 and makes
  * a fair opponent for beginners on larger boards.
  */
object Bot:

  case class Config(playouts: Int, randomness: Double, maxMillis: Int)

  def config(level: Int): Config = level.max(1).min(8) match
    case 1 => Config(40, 0.6, 300)
    case 2 => Config(150, 0.3, 400)
    case 3 => Config(400, 0.15, 600)
    case 4 => Config(1000, 0.08, 900)
    case 5 => Config(2500, 0.04, 1300)
    case 6 => Config(6000, 0.02, 1800)
    case 7 => Config(12000, 0, 2500)
    case _ => Config(25000, 0, 3500)

  case class Result(move: Move, winRate: Double, playouts: Int)

  def move(game: Game, level: Int, seed: Long = System.nanoTime()): Result =
    val cfg = config(level)
    val rng = java.util.SplittableRandom(seed)
    val pos = game.position
    val size = pos.board.width
    // bigger boards need longer playouts: scale budget by area to keep latency bounded
    val budget0 = math.max(30, (cfg.playouts * 81L / (size * size)).toInt * (if size > 9 then 2 else 1))
    val me = pos.color
    val candidates: Array[Move] =
      (pos.legalPoints.filterNot(p => FastBoard.isOwnEye(pos.board, p, me)).map(Move.Play(_)) :+ Move.Pass).toArray
    val priors: Array[Double] = candidates.map(prior(pos, _))
    // every candidate deserves a few playouts, even at low levels
    val budget = math.max(budget0, candidates.length * 3)
    if candidates.length == 1 then Result(Move.Pass, 0.5, 0)
    else if rng.nextDouble() < cfg.randomness then
      val playable = candidates.filterNot(_.isPass)
      Result(playable(rng.nextInt(playable.length)), 0.5, 0)
    else
      val wins = Array.fill(candidates.length)(0d)
      val visits = Array.fill(candidates.length)(0)
      val base = FastBoard(pos.board)
      val seen = pos.seen + pos.board.hash
      var total = 0
      val deadline = System.currentTimeMillis() + cfg.maxMillis
      // every candidate gets one playout first
      while total < candidates.length || (total < budget && (total % 16 != 0 || System.currentTimeMillis() < deadline)) do
        val i =
          if total < candidates.length then total
          else
            var best = 0
            var bestScore = Double.MinValue
            val logT = math.log(total.toDouble)
            var j = 0
            while j < candidates.length do
              val s = wins(j) / visits(j) + 0.7 * math.sqrt(logT / visits(j)) + priors(j) / (visits(j) + 1)
              if s > bestScore then
                bestScore = s
                best = j
              j += 1
            best
        val board = base.copy()
        val first = candidates(i)
        val passes = first match
          case Move.Pass => pos.consecutivePasses + 1
          case Move.Play(p) =>
            board.play(p.index, me)
            0
        val won =
          if passes >= 2 then Scoring.area(pos.board, Set.empty, game.komi).winner.contains(me)
          else
            board.playout(!me, rng, seen, passes)
            board.score(game.komi.value, me) > 0
        if won then wins(i) += 1
        visits(i) += 1
        total += 1
      def value(k: Int) = wins(k) / visits(k).max(1) + 0.1 * priors(k)
      val minVisits = total / candidates.length / 2
      val passIdx0 = candidates.length - 1
      // pick the best stone placement; ties never go to passing
      var best = 0
      var j = 1
      while j < passIdx0 do
        if visits(j) >= minVisits && value(j) > value(best) then best = j
        j += 1
      // only pass if it is clearly better than playing on
      if value(passIdx0) > value(best) + 0.05 then best = passIdx0
      // after the opponent passed, pass back as long as passing still wins
      val passIdx = candidates.length - 1
      val passRate = wins(passIdx) / visits(passIdx).max(1)
      val choice =
        if pos.consecutivePasses > 0 && passRate > 0.5 then passIdx
        else best
      Result(candidates(choice), wins(choice) / visits(choice).max(1), total)

  /** Cheap tactical knowledge that random playouts are blind to. */
  private def prior(pos: Position, move: Move): Double = move match
    case Move.Pass =>
      // passing while the board is still mostly empty is almost always wrong
      val stones = pos.board.stoneCount(chess.Color.Black) + pos.board.stoneCount(chess.Color.White)
      if stones * 3 < pos.board.points then -3.0 else 0
    case Move.Play(p) =>
      pos.play(move).fold(
        _ => -10,
        after =>
          val me = pos.color
          val captured = after.lastCaptures.size
          val myLibs = after.board.group(p).liberties.size
          val savesAtari = pos.board
            .neighbours(p)
            .exists(n => pos.board.colorAt(n).contains(me) && pos.board.group(n).liberties.size == 1) && myLibs >= 2
          val ataris = after.board
            .neighbours(p)
            .exists(n => after.board.colorAt(n).contains(!me) && after.board.group(n).liberties.size == 1)
          val n = pos.board.width
          val line = math.min(math.min(p.x(n), n - 1 - p.x(n)), math.min(p.y(n), n - 1 - p.y(n)))
          val early = pos.board.stoneCount(chess.Color.Black) + pos.board.stoneCount(chess.Color.White) < n
          (if captured > 0 then 2.0 + math.min(captured, 5) * 0.3 else 0) +
            (if savesAtari then 1.5 else 0) +
            (if ataris then 0.3 else 0) +
            (if myLibs == 1 && captured == 0 then -2.0 else 0) +
            (if early && line == 0 then -1.0 else 0)
      )

  /** Estimate which stones are dead by running playouts from the final position. */
  def estimateDead(game: Game, playouts: Int = 300, seed: Long = 42L): Set[Point] =
    val pos = game.position
    val rng = java.util.SplittableRandom(seed)
    val base = FastBoard(pos.board)
    val n = base.cells.length
    val ownedByStone = Array.fill(n)(0)
    var k = 0
    while k < playouts do
      val b = base.copy()
      b.playout(pos.color, rng, Set.empty, 0)
      val owner = b.ownership
      var i = 0
      while i < n do
        if base.cells(i) != Stone.empty && owner(i) == base.cells(i) then ownedByStone(i) += 1
        i += 1
      k += 1
    val dead = scala.collection.mutable.Set.empty[Point]
    pos.board.allPoints.foreach: p =>
      if !pos.board.isEmpty(p) && !dead.contains(p) then
        val g = pos.board.group(p)
        val avg = g.stones.toList.map(s => ownedByStone(s.index)).sum.toDouble / (g.stones.size * playouts)
        if avg < 0.5 then dead ++= g.stones
    dead.toSet

/** Mutable board used for playouts. Not thread safe; one per search. */
final private class FastBoard(val size: Int, val cells: Array[Byte]):

  private val n = size * size
  private val mark = Array.fill(n)(0)
  private var markGen = 0
  private val stack = Array.fill(n)(0)

  def copy(): FastBoard = FastBoard(size, cells.clone())

  inline private def forNeighbours(i: Int)(inline f: Int => Unit): Unit =
    val x = i % size
    if x > 0 then f(i - 1)
    if x < size - 1 then f(i + 1)
    if i >= size then f(i - size)
    if i < n - size then f(i + size)

  /** Does the group at `i` have at least one liberty? If `collect`, the group's stones
    * are left in `stack(0 until result)`.
    */
  private def libertiesAndStones(i: Int): (Boolean, Int) =
    markGen += 1
    val color = cells(i)
    var top = 0
    var count = 0
    var hasLib = false
    stack(top) = i
    top += 1
    mark(i) = markGen
    val members = scala.collection.mutable.ArrayBuffer.empty[Int]
    while top > 0 do
      top -= 1
      val cur = stack(top)
      members += cur
      count += 1
      forNeighbours(cur): nb =>
        val c = cells(nb)
        if c == Stone.empty then hasLib = true
        else if c == color && mark(nb) != markGen then
          mark(nb) = markGen
          stack(top) = nb
          top += 1
    var k = 0
    while k < members.length do
      stack(k) = members(k)
      k += 1
    (hasLib, count)

  private def removeGroupIfDead(i: Int): Int =
    val (hasLib, count) = libertiesAndStones(i)
    if hasLib then 0
    else
      var k = 0
      while k < count do
        cells(stack(k)) = Stone.empty
        k += 1
      count

  /** Plays without superko checks. Returns false (and leaves the board unchanged) on suicide. */
  def play(i: Int, color: Color): Boolean =
    val me = Stone.of(color)
    val enemy = Stone.of(!color)
    cells(i) = me
    var captured = 0
    forNeighbours(i): nb =>
      if cells(nb) == enemy then captured += removeGroupIfDead(nb)
    if captured == 0 && !libertiesAndStones(i)._1 then
      cells(i) = Stone.empty
      false
    else true

  /** If the group at `i` has exactly one liberty, return it, else -1. */
  def singleLiberty(i: Int): Int =
    markGen += 1
    val color = cells(i)
    var top = 0
    var lib = -1
    var many = false
    stack(top) = i
    top += 1
    mark(i) = markGen
    while top > 0 && !many do
      top -= 1
      val cur = stack(top)
      forNeighbours(cur): nb =>
        val c = cells(nb)
        if c == Stone.empty then
          if lib == -1 then lib = nb else if lib != nb then many = true
        else if c == color && mark(nb) != markGen then
          mark(nb) = markGen
          stack(top) = nb
          top += 1
    if many then -1 else lib

  /** A move capturing the group that just played, if it is in atari. */
  private def captureNear(last: Int): Int =
    if last < 0 || cells(last) == Stone.empty then -1 else singleLiberty(last)

  def isOwnEye(i: Int, color: Color): Boolean =
    FastBoard.isOwnEye(cells, size, i, Stone.of(color))

  def playout(start: Color, rng: java.util.SplittableRandom, seen: Set[Long], initialPasses: Int): Unit =
    var color = start
    var passes = initialPasses
    var moves = 0
    val maxMoves = n * 3
    val empties = Array.fill(n)(0)
    var last = -1
    while passes < 2 && moves < maxMoves do
      // capture the group that just moved if it is in atari
      val cap = captureNear(last)
      if cap >= 0 && rng.nextInt(10) < 9 && play(cap, color) then
        passes = 0
        last = cap
        color = !color
        moves += 1
      else
        var count = 0
        var i = 0
        while i < n do
          if cells(i) == Stone.empty then
            empties(count) = i
            count += 1
          i += 1
        var played = false
        // try random empty points until one is legal and not an own eye
        while !played && count > 0 do
          val k = rng.nextInt(count)
          val p = empties(k)
          empties(k) = empties(count - 1)
          count -= 1
          if !isOwnEye(p, color) && play(p, color) then
            played = true
            last = p
        if played then passes = 0
        else
          passes += 1
          last = -1
        color = !color
        moves += 1

  /** Area ownership: stone color, or the color of an empty point's neighbours (eyes). */
  def ownership: Array[Byte] =
    val owner = Array.fill(n)(Stone.empty)
    var i = 0
    while i < n do
      if cells(i) != Stone.empty then owner(i) = cells(i)
      else
        var b = false
        var w = false
        forNeighbours(i): nb =>
          if cells(nb) == Stone.black then b = true
          else if cells(nb) == Stone.white then w = true
        if b && !w then owner(i) = Stone.black
        else if w && !b then owner(i) = Stone.white
      i += 1
    owner

  /** Score from `color`'s perspective, after a playout (all territories are eyes by then). */
  def score(komi: Double, color: Color): Double =
    val owner = ownership
    var black = 0d
    var white = komi
    var i = 0
    while i < n do
      if owner(i) == Stone.black then black += 1
      else if owner(i) == Stone.white then white += 1
      i += 1
    if color.black then black - white else white - black

private object FastBoard:
  def apply(board: Board): FastBoard = new FastBoard(board.width, board.toArray)
  def apply(size: Int, cells: Array[Byte]): FastBoard = new FastBoard(size, cells)

  def isOwnEye(board: Board, p: Point, color: Color): Boolean =
    isOwnEye(board.toArray, board.width, p.index, Stone.of(color))

  /** An empty point whose orthogonal neighbours are all ours, and whose diagonals
    * are not controlled by the enemy (no more than one, or none on the edge).
    */
  def isOwnEye(cells: Array[Byte], size: Int, i: Int, me: Byte): Boolean =
    if cells(i) != Stone.empty then false
    else
      val x = i % size
      val y = i / size
      var ok = true
      if x > 0 && cells(i - 1) != me then ok = false
      if ok && x < size - 1 && cells(i + 1) != me then ok = false
      if ok && y > 0 && cells(i - size) != me then ok = false
      if ok && y < size - 1 && cells(i + size) != me then ok = false
      if !ok then false
      else
        val enemy: Byte = if me == Stone.black then Stone.white else Stone.black
        var bad = 0
        var diag = 0
        def check(dx: Int, dy: Int) =
          val nx = x + dx
          val ny = y + dy
          if nx >= 0 && ny >= 0 && nx < size && ny < size then
            diag += 1
            if cells(ny * size + nx) == enemy then bad += 1
        check(-1, -1)
        check(1, -1)
        check(-1, 1)
        check(1, 1)
        if diag < 4 then bad == 0 else bad <= 1
