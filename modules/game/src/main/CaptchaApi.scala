package lila.game

import scala.util.Random

import lila.core.captcha.{ Captcha, CaptchaApi as ICaptchaApi, Solutions, WithCaptcha }

/** Captchas are tiny Go problems: "Black to play and capture".
  * They are generated procedurally, and exactly one move captures.
  */
final private class CaptchaApi()(using Executor) extends ICaptchaApi:

  def any: Captcha = Impl.challenges.head

  def get(id: GameId): Fu[Captcha] = fuccess(Impl.find(id) | Impl.default)

  def validate(gameId: GameId, move: String): Fu[Boolean] =
    get(gameId).map(_.solutions.toList.contains(move))

  def validateSync(data: WithCaptcha): Boolean =
    (Impl.find(data.gameId) | Impl.default).solutions.toList.contains(data.move)

  def newCaptcha() = Impl.generate(Random.nextLong()).foreach(Impl.add)

  private object Impl:

    val size = goban.BoardSize.Nine

    lazy val default: Captcha = Iterator.from(1).flatMap(i => generate(i.toLong)).next()

    var challenges = NonEmptyList.one(default)
    private val capacity = 256

    def add(c: Captcha): Unit =
      if find(c.gameId).isEmpty then challenges = NonEmptyList(c, challenges.toList.take(capacity))

    def find(id: GameId): Option[Captcha] = challenges.find(_.gameId == id)

    /** A white group in atari surrounded by black, plus some noise. */
    def generate(seed: Long): Option[Captcha] =
      val rng = Random(seed)
      val n = size.size
      import goban.{ Board, Point, Position, Stone }
      val x = 2 + rng.nextInt(n - 4)
      val y = 2 + rng.nextInt(n - 4)
      val horizontal = rng.nextBoolean()
      val length = 1 + rng.nextInt(2)
      val group = (0 until length).toList.map: i =>
        if horizontal then Point(x + i, y, n) else Point(x, y + i, n)
      var board = Board.empty(size)
      group.foreach(p => board = board.setStone(p, Stone.white))
      val libs = board.group(group.head).liberties.toList.sortBy(_.index)
      val solution = libs(rng.nextInt(libs.size))
      (libs.filterNot(_ == solution)).foreach(p => board = board.setStone(p, Stone.black))
      // sprinkle a few unrelated stones
      (1 to 4).foreach: _ =>
        val p = Point.fromIndex(rng.nextInt(size.points))
        if board.isEmpty(p) && board.neighbours(p).forall(board.isEmpty) && p != solution then
          board = board.setStone(p, if rng.nextBoolean() then Stone.black else Stone.white)
      val pos = Position(board, chess.Black)
      val capturing = pos.legalPoints.filter(p => pos.play(goban.Move.Play(p)).exists(_.lastCaptures.nonEmpty))
      Option.when(capturing == List(solution)):
        Captcha(
          gameId = GameId(f"cap${(seed & 0xfffff).toInt}%05x".take(8).padTo(8, '0')),
          board = board.serialize,
          size = n,
          color = chess.Black,
          solutions = NonEmptyList.one(solution.sgf(n)),
          moves = Map.empty
        )
